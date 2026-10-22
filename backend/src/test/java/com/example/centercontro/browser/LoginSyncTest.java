package com.example.centercontro.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginSyncTest {

    @TempDir
    Path tempDir;

    @Test
    void importsLoginCookiesAndKeyFromDailyProfile() throws IOException {
        Path daily = createDailyProfile("{\"os_crypt\":{\"encrypted_key\":\"DPAPI-DAILY\",\"audit_enabled\":\"1\"},"
                + "\"profile\":{\"last_used\":\"Default\"}}", "SQLite-db sessionid value-123");
        Path dataDir = tempDir.resolve("app");
        Path managed = dataDir.resolve("edge-profile");
        Files.createDirectories(managed.resolve("Default").resolve("Network"));
        Files.writeString(managed.resolve("Local State"),
                "{\"os_crypt\":{\"encrypted_key\":\"DPAPI-OLD\"},\"other\":42}");
        Files.write(managed.resolve("Default").resolve("Network").resolve("Cookies"),
                "old-cookie-db".getBytes(StandardCharsets.ISO_8859_1));

        withDailyProfile(daily, () -> {
            LoginSync.SyncResult result = LoginSync.prepareBeforeLaunch(dataDir.toString());
            assertTrue(result.imported(), result.message());

            String merged = Files.readString(managed.resolve("Local State"));
            assertTrue(merged.contains("DPAPI-DAILY"));
            assertFalse(merged.contains("DPAPI-OLD"));
            assertTrue(merged.contains("\"other\""));

            assertEquals(
                    Files.size(daily.resolve("Default").resolve("Network").resolve("Cookies")),
                    Files.size(managed.resolve("Default").resolve("Network").resolve("Cookies")));
            assertTrue(Files.exists(dataDir.resolve("login-sync.json")));

            Map<String, Object> status = LoginSync.describe(dataDir.toString());
            assertEquals(Boolean.TRUE, status.get("imported"));
            assertEquals("Default", status.get("sourceProfile"));
        });
    }

    @Test
    void keepsAutomationCookiesWhenDailyProfileIsLoggedOut() throws IOException {
        Path daily = createDailyProfile(
                "{\"os_crypt\":{\"encrypted_key\":\"DPAPI-DAILY\"}}", "SQLite-db ttwid only");
        Path dataDir = tempDir.resolve("app2");
        Path managed = dataDir.resolve("edge-profile");
        Files.createDirectories(managed.resolve("Default").resolve("Network"));
        Files.write(managed.resolve("Default").resolve("Network").resolve("Cookies"),
                "automation-login".getBytes(StandardCharsets.ISO_8859_1));

        withDailyProfile(daily, () -> {
            LoginSync.SyncResult result = LoginSync.prepareBeforeLaunch(dataDir.toString());
            assertFalse(result.imported());
            assertTrue(result.message().contains("没有抖音登录态"));
            assertEquals("automation-login",
                    Files.readString(managed.resolve("Default").resolve("Network").resolve("Cookies")));
        });
    }

    @Test
    void reportsNotImportedWithoutMarker() throws IOException {
        Path dataDir = Files.createDirectories(tempDir.resolve("app3"));
        Map<String, Object> status = LoginSync.describe(dataDir.toString());
        assertEquals(Boolean.FALSE, status.get("imported"));
        assertFalse(((String) status.get("hint")).isBlank());
    }

    private Path createDailyProfile(String localState, String cookies) throws IOException {
        Path daily = tempDir.resolve("daily");
        Files.createDirectories(daily.resolve("Default").resolve("Network"));
        Files.writeString(daily.resolve("Local State"), localState);
        Files.write(daily.resolve("Default").resolve("Network").resolve("Cookies"),
                cookies.getBytes(StandardCharsets.ISO_8859_1));
        return daily;
    }

    private void withDailyProfile(Path daily, ThrowingRunnable action) throws IOException {
        String previous = System.getProperty("centercontrol.edge-user-data");
        System.setProperty("centercontrol.edge-user-data", daily.toString());
        try {
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty("centercontrol.edge-user-data");
            } else {
                System.setProperty("centercontrol.edge-user-data", previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}