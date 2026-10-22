package com.example.centercontro.browser;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edge 安装位置、日常配置目录与进程占用的解析工具。
 *
 * <p>这个类只做“看”和“判断”，不写任何文件。所有涉及日常 Edge 的比较都会先解析真实路径
 * （junction / symlink 都会被解析成目标），避免历史上那种“指向日常配置的链接”再次让
 * 自动化浏览器和日常浏览器共用同一份 Cookie。
 */
final class EdgeEnvironment {

    /** 允许高级用户/测试把“日常 Edge 配置”指到别处，默认取 %LOCALAPPDATA%\Microsoft\Edge\User Data。 */
    private static final String DAILY_USER_DATA_PROPERTY = "centercontrol.edge-user-data";
    private static final String DAILY_USER_DATA_ENV = "CENTERCONTROL_EDGE_USER_DATA";

    private static final Pattern USER_DATA_SWITCH =
            Pattern.compile("--user-data-dir=(?:\"([^\"]*)\"|([^\\s\"]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST_USED_PROFILE =
            Pattern.compile("\"last_used\"\\s*:\\s*\"([^\"]+)\"");

    private static volatile Path cachedExecutable;
    private static volatile boolean executableResolved;
    private static final Map<String, CachedCheck> PROFILE_CHECKS = new ConcurrentHashMap<>();
    private static final long PROFILE_CHECK_TTL_MS = 3_000L;

    private record CachedCheck(boolean inUse, long checkedAt) {
    }

    private EdgeEnvironment() {
    }

    /** 系统 Edge 可执行文件；找不到返回 null。 */
    static Path edgeExecutable() {
        if (executableResolved) {
            return cachedExecutable;
        }
        synchronized (EdgeEnvironment.class) {
            if (executableResolved) {
                return cachedExecutable;
            }
            List<Path> candidates = new ArrayList<>();
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                candidates.add(Paths.get(local, "Microsoft", "Edge", "Application", "msedge.exe"));
            }
            candidates.add(Paths.get("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"));
            candidates.add(Paths.get("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"));
            cachedExecutable = candidates.stream()
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
            executableResolved = true;
            return cachedExecutable;
        }
    }

    /** 日常 Edge 的用户数据目录；路径可能不存在，调用方自行判断。 */
    static Path dailyUserDataDir() {
        String override = System.getProperty(DAILY_USER_DATA_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv(DAILY_USER_DATA_ENV);
        }
        if (override != null && !override.isBlank()) {
            try {
                return Paths.get(override).toAbsolutePath().normalize();
            } catch (InvalidPathException ignored) {
                // 落到默认路径
            }
        }
        return systemDefaultUserDataDir();
    }

    /**
     * 系统默认的 Edge 用户数据目录。
     *
     * <p>判断“没有写 --user-data-dir 的 Edge 进程用的是哪个配置”时必须用这个真实默认值，
     * 不能用 {@link #dailyUserDataDir()}（它可能被测试/高级配置覆盖）。
     */
    private static Path systemDefaultUserDataDir() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            return null;
        }
        return Paths.get(local, "Microsoft", "Edge", "User Data");
    }

    /** 读取 Local State 里最后使用的配置名，失败时退回 Default。 */
    static String lastUsedProfile(Path userDataDir) {
        if (userDataDir == null) {
            return "Default";
        }
        Path localState = userDataDir.resolve("Local State");
        if (Files.isRegularFile(localState)) {
            try {
                String json = Files.readString(localState);
                Matcher matcher = LAST_USED_PROFILE.matcher(json);
                if (matcher.find()) {
                    String profile = matcher.group(1);
                    if (Files.isDirectory(userDataDir.resolve(profile))) {
                        return profile;
                    }
                }
            } catch (Exception ignored) {
                // 读不到就退回默认
            }
        }
        if (Files.isDirectory(userDataDir.resolve("Default"))) {
            return "Default";
        }
        try (var children = Files.list(userDataDir)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("Profile "))
                    .sorted()
                    .findFirst()
                    .orElse("Default");
        } catch (IOException e) {
            return "Default";
        }
    }

    /** 目标配置是否正被某个 Edge 进程使用（含 junction 解析后的真实路径比较）。 */
    static boolean isProfileInUse(Path userDataDir) {
        if (userDataDir == null) {
            return false;
        }
        if (!processesUsing(userDataDir).isEmpty()) {
            return true;
        }
        // 进程信息读不全时，lockfile 是第二道保险：浏览器运行时它被独占打开。
        return isFileLocked(userDataDir.resolve("lockfile"));
    }

    /** 给高频轮询使用的带缓存版本（默认 3 秒），避免每秒枚举全部进程。 */
    static boolean isProfileInUseCached(Path userDataDir) {
        Path target = canonical(userDataDir);
        if (target == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        CachedCheck cached = PROFILE_CHECKS.get(target.toString());
        if (cached != null && now - cached.checkedAt() < PROFILE_CHECK_TTL_MS) {
            return cached.inUse();
        }
        boolean inUse = isProfileInUse(userDataDir);
        PROFILE_CHECKS.put(target.toString(), new CachedCheck(inUse, now));
        return inUse;
    }

    /** 文件是否被其他进程独占（Windows 上普通读取会直接报共享冲突）。 */
    static boolean isFileLocked(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.READ)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** 正在使用该配置的 Edge 进程。 */
    static List<ProcessHandle> processesUsing(Path userDataDir) {
        Path target = canonical(userDataDir);
        if (target == null) {
            return List.of();
        }
        List<ProcessHandle> matches = new ArrayList<>();
        for (ProcessHandle handle : edgeProcesses()) {
            if (usesProfile(handle, target)) {
                matches.add(handle);
            }
        }
        return matches;
    }

    /** 解析出的真实路径；路径不存在时退回规范化绝对路径。 */
    static Path canonical(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** child 是否落在 parent 目录内（两者都先解析真实路径）。 */
    static boolean isInside(Path child, Path parent) {
        Path normalizedChild = canonical(child);
        Path normalizedParent = canonical(parent);
        return normalizedChild != null && normalizedParent != null
                && normalizedChild.startsWith(normalizedParent);
    }

    /** 是否是符号链接或 junction（两者都不跟随）。 */
    static boolean isLink(Path path) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isSymbolicLink() || attributes.isOther();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 优雅关闭 Edge：先发 WM_CLOSE（taskkill 不带 /F），让浏览器把 Cookie 落盘；
     * 超时后再强制结束。直接 destroyForcibly 会丢掉最近刚写入的登录态。
     */
    static void closeGracefully(List<ProcessHandle> processes) {
        if (processes == null || processes.isEmpty()) {
            return;
        }
        for (ProcessHandle handle : processes) {
            try {
                new ProcessBuilder("taskkill", "/PID", Long.toString(handle.pid()))
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (Exception ignored) {
                // taskkill 不可用时走下面的强制兜底
            }
        }
        long deadline = System.currentTimeMillis() + 6_000L;
        while (System.currentTimeMillis() < deadline
                && processes.stream().anyMatch(ProcessHandle::isAlive)) {
            sleep(200);
        }
        for (ProcessHandle handle : processes) {
            if (handle.isAlive()) {
                try {
                    handle.descendants().forEach(ProcessHandle::destroyForcibly);
                } catch (Exception ignored) {
                    // 进程可能刚好退出
                }
                handle.destroyForcibly();
            }
        }
    }

    private static boolean usesProfile(ProcessHandle handle, Path canonicalTarget) {
        String commandLine = commandLineOf(handle);
        if (commandLine.isBlank()) {
            // 读不到命令行：只有目标就是系统默认配置时才保守地判定为占用。
            Path systemDefault = systemDefaultUserDataDir();
            return systemDefault != null && canonical(systemDefault).equals(canonicalTarget);
        }
        Optional<Path> explicit = userDataDirFromCommandLine(commandLine);
        if (explicit.isPresent()) {
            return canonical(explicit.get()).equals(canonicalTarget);
        }
        // 子进程（--type=...）没带 switch 时不作为判定依据，只有浏览器主进程才代表默认配置。
        if (commandLine.toLowerCase(Locale.ROOT).contains("--type=")) {
            return false;
        }
        Path systemDefault = systemDefaultUserDataDir();
        return systemDefault != null && canonical(systemDefault).equals(canonicalTarget);
    }

    private static List<ProcessHandle> edgeProcesses() {
        List<ProcessHandle> result = new ArrayList<>();
        try {
            ProcessHandle.allProcesses()
                    .filter(ProcessHandle::isAlive)
                    .forEach(handle -> {
                        String command = commandOf(handle);
                        String commandLine = commandLineOf(handle);
                        String text = (command + " " + commandLine).toLowerCase(Locale.ROOT);
                        if (text.contains("msedge.exe")) {
                            result.add(handle);
                        }
                    });
        } catch (Exception ignored) {
            // 枚举失败时按“没有进程”处理，调用方还有 lockfile / 文件锁兜底。
        }
        return result;
    }

    private static String commandOf(ProcessHandle handle) {
        try {
            return handle.info().command().orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String commandLineOf(ProcessHandle handle) {
        try {
            return handle.info().commandLine().orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private static Optional<Path> userDataDirFromCommandLine(String commandLine) {
        Matcher matcher = USER_DATA_SWITCH.matcher(commandLine);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Paths.get(value));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}