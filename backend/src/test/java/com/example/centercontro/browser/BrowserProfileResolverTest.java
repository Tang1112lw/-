package com.example.centercontro.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserProfileResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesADedicatedPersistentProfile() throws IOException {
        BrowserProfileResolver.ResolvedProfile profile =
                BrowserProfileResolver.resolve(tempDir.toString());

        Path expected = tempDir.toAbsolutePath().normalize().resolve("edge-profile");
        assertEquals(expected, profile.userDataDir());
        assertTrue(Files.isDirectory(profile.userDataDir()));
        assertFalse(Files.isSymbolicLink(profile.userDataDir()));
        Path dailyUserData = EdgeEnvironment.dailyUserDataDir();
        if (dailyUserData != null && Files.isDirectory(dailyUserData)) {
            assertFalse(profile.userDataDir().toRealPath().startsWith(dailyUserData.toRealPath()));
        }
        assertTrue(profile.description().contains("独立持久配置"));
    }

    /**
     * 回归测试：旧版本用 junction 指向日常 Edge，导致日常登录态被抢。
     * 现在只要配置目录落在日常 User Data 里就必须拒绝启动。
     */
    @Test
    void refusesADataDirectoryInsideTheDailyProfile() throws IOException {
        Path fakeDaily = tempDir.resolve("User Data");
        Files.createDirectories(fakeDaily.resolve("Default"));
        String previous = System.getProperty("centercontrol.edge-user-data");
        System.setProperty("centercontrol.edge-user-data", fakeDaily.toString());
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> BrowserProfileResolver.resolve(fakeDaily.toString()));
            assertTrue(error.getMessage().contains("日常 Edge"));
        } finally {
            if (previous == null) {
                System.clearProperty("centercontrol.edge-user-data");
            } else {
                System.setProperty("centercontrol.edge-user-data", previous);
            }
        }
    }

    /** 配置目录本身是 junction/符号链接时同样拒绝启动。 */
    @Test
    void refusesALinkedProfileDirectory() throws Exception {
        Path target = tempDir.resolve("outside");
        Files.createDirectories(target);
        Path dataDir = Files.createDirectories(tempDir.resolve("app"));
        Path link = dataDir.resolve("edge-profile");
        boolean created = false;
        try {
            Process process = new ProcessBuilder(
                    "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            created = process.waitFor() == 0 && Files.exists(link);
        } catch (Exception ignored) {
            created = false;
        }
        if (!created) {
            return; // 环境不允许创建 junction 时跳过
        }
        try {
            assertThrows(IllegalStateException.class,
                    () -> BrowserProfileResolver.resolve(dataDir.toString()));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    /** 没有同步标记时状态必须是“未同步”，且不能抛异常。 */
    @Test
    void reportsNotImportedWithoutMarker() {
        Map<String, Object> status = LoginSync.describe(tempDir.toString());
        assertEquals(Boolean.FALSE, status.get("imported"));
        assertTrue(status.containsKey("hint"));
    }
}