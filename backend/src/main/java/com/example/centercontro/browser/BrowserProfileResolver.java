package com.example.centercontro.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 为 CDP 桥接维护一个独立的持久 Edge 配置。
 *
 * <p>普通 Edge 默认配置在浏览器运行后无法从外部直接接入 CDP；Chromium 136 以后
 * 也会拒绝默认 User Data 的远程调试。通过 junction 绕过该限制会让自动化浏览器和
 * 日常浏览器共用锁文件，既不可靠，也会把日常 Edge 的登录态“抢”掉——这正是旧版本的
 * 问题。这里改用独立配置目录，并且：
 *
 * <ul>
 *   <li>启动前校验配置目录的真实路径，绝不允许落在日常 Edge 的 User Data 里；</li>
 *   <li>只清理明确使用本程序配置的 Edge 进程，绝不结束日常 Edge；</li>
 *   <li>需要日常登录态时由 {@link LoginSync} 以“只读 + 复制”的方式接力，
 *       日常 Edge 运行时直接跳过。</li>
 * </ul>
 */
final class BrowserProfileResolver {

    private static final Logger log = LoggerFactory.getLogger(BrowserProfileResolver.class);

    private BrowserProfileResolver() {
    }

    static ResolvedProfile resolve(String dataDir) {
        removeLegacyJunction(dataDir);
        Path profileDir = managedProfileDir(dataDir);
        assertIsolatedProfile(profileDir);
        try {
            Files.createDirectories(profileDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建自动化浏览器配置目录失败: " + profileDir, e);
        }
        Path executable = EdgeEnvironment.edgeExecutable();
        if (executable == null) {
            throw new IllegalStateException("未找到系统 Edge，无法启动浏览器桥接");
        }
        return new ResolvedProfile("Edge", executable, profileDir, "Default");
    }

    /**
     * 安全闸门：自动化配置必须是独立目录。
     *
     * <p>旧版本曾用 junction 指向日常 Edge 的 User Data，导致两边共用锁文件和 Cookie，
     * 日常 Edge 的登录态会被抢掉。这里在启动前解析真实路径，一旦发现指向日常配置
     * （或本身是链接）就拒绝启动，从根上杜绝这种配置再次出现。
     */
    private static void assertIsolatedProfile(Path profileDir) {
        Path dailyUserData = EdgeEnvironment.dailyUserDataDir();
        if (dailyUserData != null && Files.isDirectory(dailyUserData)
                && EdgeEnvironment.isInside(profileDir, dailyUserData)) {
            throw new IllegalStateException(
                    "自动化浏览器配置目录落在日常 Edge 用户数据目录内，已拒绝启动以免影响日常登录: "
                            + profileDir + "（日常配置: " + dailyUserData + "）");
        }
        if (Files.exists(profileDir, LinkOption.NOFOLLOW_LINKS) && EdgeEnvironment.isLink(profileDir)) {
            throw new IllegalStateException(
                    "自动化浏览器配置目录是链接/联接，已拒绝启动以免与日常 Edge 共用配置: " + profileDir);
        }
    }

    /**
     * 只清理占用“本程序独立配置”的 Edge，绝不结束用户日常 Edge。
     *
     * <p>只有 CDP 端口不存在时才调用。进程判定按 --user-data-dir 的真实路径比较，
     * 而不是简单的字符串包含，避免日常 Edge 的路径恰好是配置目录前缀时被误杀。
     * 关闭时先发 WM_CLOSE 让浏览器把 Cookie 落盘，超时才强制结束。
     */
    static void closeManagedProcesses(ResolvedProfile profile) {
        List<ProcessHandle> managed = EdgeEnvironment.processesUsing(profile.userDataDir());
        if (managed.isEmpty()) {
            return;
        }
        log.info("清理 {} 个占用自动化浏览器配置但没有调试端口的 Edge 进程: {}",
                managed.size(),
                managed.stream().map(handle -> Long.toString(handle.pid())).toList());
        EdgeEnvironment.closeGracefully(managed);
    }

    static boolean isCdpAlive(String cdpUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(cdpUrl + "/json/version"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    static void waitForCdp(String cdpUrl, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (isCdpAlive(cdpUrl)) {
                return;
            }
            sleep(500);
        }
        throw new IllegalStateException("浏览器调试端口未就绪: " + cdpUrl);
    }

    static Process launch(ResolvedProfile profile, String cdpUrl, String url, boolean debugging) {
        try {
            List<String> command = new ArrayList<>();
            command.add(profile.executable().toString());
            if (debugging) {
                command.add("--remote-debugging-port=" + port(cdpUrl));
                command.add("--remote-allow-origins=*");
            }
            command.add("--user-data-dir=" + profile.userDataDir());
            command.add("--profile-directory=" + profile.profileName());
            command.add("--no-first-run");
            command.add("--no-default-browser-check");
            command.add("--start-maximized");
            command.add("--mute-audio");
            command.add("--edge-skip-compat-layer-relaunch");
            command.add(url);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return builder.start();
        } catch (Exception e) {
            throw new IllegalStateException("启动系统浏览器失败: " + e.getMessage(), e);
        }
    }

    static Path managedProfileDir(String dataDir) {
        return dataRoot(dataDir).resolve("edge-profile");
    }

    /**
     * 旧版本曾在运行目录创建一个指向日常 Edge User Data 的 junction。
     * 该链接已不再使用；删除链接本身不会删除或修改它指向的默认浏览器配置。
     */
    private static void removeLegacyJunction(String dataDir) {
        Path legacy = dataRoot(dataDir).resolve("edge-user-data-link");
        try {
            if (!Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    legacy, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isSymbolicLink() && !attributes.isOther()) {
                log.warn("发现同名普通目录，未清理旧浏览器链接: {}", legacy);
                return;
            }
            Files.delete(legacy);
            log.info("已移除旧版浏览器 junction；日常 Edge 用户数据未被修改: {}", legacy);
        } catch (IOException e) {
            log.warn("清理旧版浏览器 junction 失败: {} ({})", legacy, e.getMessage());
        }
    }

    private static Path dataRoot(String dataDir) {
        return Paths.get(
                dataDir == null || dataDir.isBlank()
                        ? System.getProperty("user.home") + "/.centercontrol"
                        : dataDir
        ).toAbsolutePath().normalize();
    }

    private static int port(String cdpUrl) {
        try {
            URI uri = URI.create(cdpUrl);
            return uri.getPort() > 0 ? uri.getPort() : 9222;
        } catch (Exception e) {
            return 9222;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record ResolvedProfile(
            String browserName,
            Path executable,
            Path userDataDir,
            String profileName
    ) {
        String description() {
            return browserName + " 独立持久配置 " + profileName + "（" + userDataDir
                    + "，抖音登录态由日常 Edge 自动导入）";
        }
    }
}