package com.example.centercontro.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.Cookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把日常 Edge 里的抖音登录态“接力”到本程序的自动化 Edge 配置。
 *
 * <p>背景：Chromium 136 以后，用默认 User Data 目录启动时无法开启 CDP 调试端口；
 * 旧版本程序用 junction 指向日常配置绕过这个限制，结果两个浏览器实例共用同一份
 * Cookie / 锁文件，日常 Edge 的登录态会被抢掉甚至写坏。
 *
 * <p>现在的做法只“读”不“抢”：日常 Edge 完全关闭时，把它的 Cookie 数据库和
 * 解密密钥复制到自动化配置（同一台机器、同一个 Windows 用户，v10 Cookie 由
 * Edge 自己用 DPAPI 解密）。日常 Edge 正在运行时绝不碰它的文件，登录同步直接跳过，
 * 自动化浏览器继续使用上一次导入的登录态。
 */
public final class LoginSync {

    private static final Logger log = LoggerFactory.getLogger(LoginSync.class);

    private static final String MARKER_FILE = "login-sync.json";
    private static final String MANAGED_PROFILE_DIR = "edge-profile";
    private static final String COOKIE_BACKUP = "Cookies.before-login-sync";

    /** 判定“已登录”的 Cookie 名。抖音主站登录后会写入 sessionid / sid_guard。 */
    private static final List<String> LOGIN_COOKIE_NAMES =
            List.of("sessionid", "sessionid_ss", "sid_guard", "sid_tt", "uid_tt_ss");

    /** 清理非抖音 Cookie 时的保留名单。 */
    private static final List<String> KEEP_DOMAIN_SUFFIXES = List.of(
            "douyin.com", "iesdouyin.com", "amemv.com", "douyinstatic.com",
            "douyinpic.com", "douyinvod.com", "bytedance.com", "snssdk.com",
            "byteimg.com", "ibytedtos.com", "zijieapi.com", "bdurl.net"
    );

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static volatile SyncResult last = skipped("尚未尝试同步");

    private LoginSync() {
    }

    public record SyncResult(
            boolean imported,
            String message,
            String sourceProfile,
            String importedAt,
            boolean dailyEdgeRunning,
            boolean hasDailyLogin
    ) {
    }

    /** 启动浏览器前的同步入口；任何情况下都不抛异常，避免阻塞启动。 */
    public static SyncResult prepareBeforeLaunch(String dataDir) {
        SyncResult result;
        try {
            result = doSync(dataDir);
        } catch (Exception e) {
            result = new SyncResult(false, "登录同步失败: " + messageOf(e), "", "",
                    false, false);
        }
        last = result;
        if (result.imported()) {
            log.info("登录同步: {}", result.message());
        } else {
            log.info("登录同步跳过: {}", result.message());
        }
        return result;
    }

    /** 给页面用的同步状态（含实时判断日常 Edge 是否在运行）。 */
    public static Map<String, Object> describe(String dataDir) {
        Map<String, Object> map = new LinkedHashMap<>();
        Path root = dataRoot(dataDir);
        JsonNode marker = readMarker(root.resolve(MARKER_FILE));
        boolean dailyRunning = EdgeEnvironment.isProfileInUseCached(EdgeEnvironment.dailyUserDataDir());

        String importedAt = marker == null ? "" : marker.path("importedAt").asString("");
        String sourceProfile = marker == null ? "" : marker.path("sourceProfile").asString("");
        boolean pendingPrune = marker != null && marker.path("pendingPrune").asBoolean(false);

        map.put("imported", !importedAt.isBlank());
        map.put("importedAt", importedAt);
        map.put("sourceProfile", sourceProfile);
        map.put("dailyEdgeRunning", dailyRunning);
        map.put("cookiePrunePending", pendingPrune);
        map.put("message", last.message());
        map.put("hint", dailyRunning
                ? "日常 Edge 正在运行，Cookie 数据库被独占；完全关闭 Edge 后重启本程序即可同步登录态。"
                : "启动程序前完全关闭日常 Edge，程序会自动把里面的抖音登录态导入自动化浏览器。");
        return map;
    }

    /** 新增一份导入后，按需清理自动化配置里非抖音域名的 Cookie。 */
    public static void pruneNonDouyinCookies(BrowserContext context, String dataDir) {
        if (context == null) {
            return;
        }
        Path root = dataRoot(dataDir);
        Path markerPath = root.resolve(MARKER_FILE);
        JsonNode marker = readMarker(markerPath);
        if (marker == null || !marker.path("pendingPrune").asBoolean(false)) {
            return;
        }
        try {
            List<Cookie> all = context.cookies();
            List<Cookie> keep = new ArrayList<>();
            for (Cookie cookie : all) {
                if (cookie.domain != null && isDouyinDomain(cookie.domain)) {
                    keep.add(cookie);
                }
            }
            if (keep.size() == all.size()) {
                markPruned(markerPath, marker);
                return;
            }
            // 先全部清空，再把抖音那部分写回去：比按域名逐个删可靠（clearCookies 的
            // 域名过滤在部分版本上不会真正删除 Cookie）。
            context.clearCookies();
            if (!keep.isEmpty()) {
                context.addCookies(keep);
            }
            log.info("登录同步：保留 {} 条抖音 Cookie，清理 {} 条其它域名 Cookie",
                    keep.size(), all.size() - keep.size());
            markPruned(markerPath, marker);
        } catch (Exception e) {
            log.warn("登录同步：清理非抖音 Cookie 失败: {}", messageOf(e));
        }
    }

    private static SyncResult doSync(String dataDir) throws IOException {
        Path root = dataRoot(dataDir);
        Path managedProfile = root.resolve(MANAGED_PROFILE_DIR);
        Path dailyUserData = EdgeEnvironment.dailyUserDataDir();

        if (dailyUserData == null || !Files.isDirectory(dailyUserData)) {
            return skipped("未找到日常 Edge 配置目录，登录同步跳过");
        }
        if (EdgeEnvironment.isProfileInUse(dailyUserData)) {
            return new SyncResult(false,
                    "日常 Edge 正在运行，Cookie 数据库被独占；完全关闭 Edge 后重启本程序即可自动同步登录态",
                    "", "", true, false);
        }

        String sourceProfile = null;
        Path sourceCookies = null;
        boolean dailyHasLogin = false;
        for (String profile : candidateProfiles(dailyUserData)) {
            Path cookies = dailyUserData.resolve(profile).resolve("Network").resolve("Cookies");
            if (!Files.isRegularFile(cookies) || EdgeEnvironment.isFileLocked(cookies)) {
                continue;
            }
            if (!hasLoginCookie(cookies)) {
                continue;
            }
            sourceProfile = profile;
            sourceCookies = cookies;
            dailyHasLogin = true;
            break;
        }
        if (sourceCookies == null) {
            return new SyncResult(false,
                    "日常 Edge 里没有抖音登录态（没有找到 sessionid 等登录 Cookie），请先在 Edge 中登录抖音",
                    "", "", false, dailyHasLogin);
        }
        if (EdgeEnvironment.isProfileInUse(managedProfile)) {
            return new SyncResult(false,
                    "本程序的自动化浏览器仍在运行，暂时无法写入配置；关闭它或重启程序后再同步",
                    "", "", false, true);
        }

        Path localState = dailyUserData.resolve("Local State");
        JsonNode osCrypt = readOsCrypt(localState);
        if (osCrypt == null) {
            return new SyncResult(false,
                    "日常 Edge 的 Local State 里没有 Cookie 解密密钥，登录同步跳过",
                    "", "", false, true);
        }

        Files.createDirectories(managedProfile.resolve("Default").resolve("Network"));
        Path managedLocalState = managedProfile.resolve("Local State");
        mergeCookieKey(managedProfile, managedLocalState, osCrypt);

        Path managedCookies = managedProfile.resolve("Default").resolve("Network").resolve("Cookies");
        if (Files.isRegularFile(managedCookies)) {
            Files.copy(managedCookies, managedProfile.resolve(COOKIE_BACKUP),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        deleteCookieJournals(managedCookies);
        Files.copy(sourceCookies, managedCookies, StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(managedCookies) != Files.size(sourceCookies)) {
            throw new IOException("Cookie 数据库复制不完整");
        }

        String importedAt = Instant.now().toString();
        String message = "已从日常 Edge（" + sourceProfile + "）导入抖音登录态，自动化浏览器无需再扫码";
        writeMarker(root.resolve(MARKER_FILE), importedAt, sourceProfile, dailyUserData,
                Files.size(sourceCookies), true, message);
        return new SyncResult(true, message, sourceProfile, importedAt, false, true);
    }

    /**
     * 把日常 Edge 的 DPAPI 加密的 Cookie 主密钥合并进自动化配置。
     *
     * <p>只替换 os_crypt.encrypted_key，保留自动化配置自己的其它字段（包括它自己的
     * app_bound_encrypted_key）。v10 Cookie 只需要这把 DPAPI 密钥，Edge 启动后会
     * 用当前 Windows 用户自己的凭据解密，因此复制过来的 Cookie 可以直接用。
     */
    private static void mergeCookieKey(Path managedProfile, Path managedLocalState, JsonNode dailyOsCrypt)
            throws IOException {
        ObjectNode managed = JSON.createObjectNode();
        if (Files.isRegularFile(managedLocalState)) {
            try {
                JsonNode parsed = JSON.readTree(managedLocalState);
                if (parsed != null && parsed.isObject()) {
                    managed = (ObjectNode) parsed;
                }
            } catch (Exception e) {
                log.warn("自动化配置的 Local State 无法解析，将重建: {}", messageOf(e));
            }
            Files.copy(managedLocalState, managedProfile.resolve("Local State.before-login-sync"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        ObjectNode osCrypt = managed.path("os_crypt").isObject()
                ? (ObjectNode) managed.get("os_crypt")
                : JSON.createObjectNode();
        osCrypt.set("encrypted_key", dailyOsCrypt.get("encrypted_key"));
        if (!osCrypt.has("audit_enabled") && dailyOsCrypt.has("audit_enabled")) {
            osCrypt.set("audit_enabled", dailyOsCrypt.get("audit_enabled"));
        }
        managed.set("os_crypt", osCrypt);

        Path temp = managedLocalState.resolveSibling("Local State.tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp, managed);
        Files.move(temp, managedLocalState, StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonNode readOsCrypt(Path localState) throws IOException {
        if (!Files.isRegularFile(localState)) {
            return null;
        }
        JsonNode node = JSON.readTree(localState);
        JsonNode osCrypt = node == null ? null : node.get("os_crypt");
        if (osCrypt == null || !osCrypt.isObject() || !osCrypt.has("encrypted_key")) {
            return null;
        }
        return osCrypt;
    }

    /** 候选配置顺序：Local State 里最后使用的 → Default → 其它 Profile。 */
    private static List<String> candidateProfiles(Path dailyUserData) {
        List<String> profiles = new ArrayList<>();
        profiles.add(EdgeEnvironment.lastUsedProfile(dailyUserData));
        profiles.add("Default");
        try (var children = Files.list(dailyUserData)) {
            children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("Profile "))
                    .sorted()
                    .forEach(profiles::add);
        } catch (IOException ignored) {
            // 只使用已有候选
        }
        return profiles.stream().distinct().toList();
    }

    /**
     * Cookie 数据库是 SQLite 文件，Cookie 名以明文存放在记录里，直接按字节找登录 Cookie 名即可，
     * 不需要引入 SQLite 驱动。找不到就认为日常 Edge 没有抖音登录态，避免把“未登录”的
     * Cookie 覆盖到自动化窗口里。
     */
    private static boolean hasLoginCookie(Path cookiesDatabase) {
        try {
            if (Files.size(cookiesDatabase) > 64L * 1024L * 1024L) {
                return false;
            }
            String content = new String(Files.readAllBytes(cookiesDatabase), StandardCharsets.ISO_8859_1);
            for (String name : LOGIN_COOKIE_NAMES) {
                if (content.contains(name)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDouyinDomain(String domain) {
        String normalized = domain.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        for (String suffix : KEEP_DOMAIN_SUFFIXES) {
            if (normalized.equals(suffix) || normalized.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    private static void deleteCookieJournals(Path cookies) throws IOException {
        if (cookies.getParent() == null) {
            return;
        }
        for (String suffix : List.of("-journal", "-wal", "-shm")) {
            Files.deleteIfExists(cookies.resolveSibling(cookies.getFileName() + suffix));
        }
    }

    private static void writeMarker(Path marker, String importedAt, String sourceProfile,
                                    Path sourceUserData, long cookieBytes, boolean pendingPrune,
                                    String message) {
        try {
            ObjectNode node = JSON.createObjectNode();
            node.put("importedAt", importedAt);
            node.put("sourceProfile", sourceProfile);
            node.put("sourceUserDataDir", sourceUserData.toString());
            node.put("cookieBytes", cookieBytes);
            node.put("pendingPrune", pendingPrune);
            node.put("message", message);
            Files.createDirectories(marker.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(marker, node);
        } catch (Exception e) {
            log.warn("写入登录同步标记失败: {}", messageOf(e));
        }
    }

    private static void markPruned(Path markerPath, JsonNode marker) {
        try {
            writeMarker(markerPath,
                    marker.path("importedAt").asString(""),
                    marker.path("sourceProfile").asString(""),
                    Paths.get(marker.path("sourceUserDataDir").asString("")),
                    marker.path("cookieBytes").asLong(0),
                    false,
                    marker.path("message").asString(""));
        } catch (Exception e) {
            log.debug("更新登录同步标记失败: {}", messageOf(e));
        }
    }

    private static JsonNode readMarker(Path marker) {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(marker);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Path dataRoot(String dataDir) {
        return Paths.get(
                dataDir == null || dataDir.isBlank()
                        ? System.getProperty("user.home") + "/.centercontrol"
                        : dataDir
        ).toAbsolutePath().normalize();
    }

    private static SyncResult skipped(String message) {
        return new SyncResult(false, message, "", "", false, false);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").trim();
    }
}