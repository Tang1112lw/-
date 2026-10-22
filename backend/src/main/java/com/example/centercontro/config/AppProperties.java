package com.example.centercontro.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Files;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "centercontrol")
public class AppProperties {

    /** 评论间隔可设置范围（秒）：1 秒到 24 小时，页面上可以自由填。 */
    public static final int MIN_INTERVAL_SECONDS = 1;
    public static final int MAX_INTERVAL_SECONDS = 86400;

    private String roomUrl = "https://live.douyin.com/537108818552";
    private String browserMode = "cdp";
    private String cdpUrl = "http://127.0.0.1:9222";
    private boolean launchIfMissing = true;
    private int maxSessions = 1;
    private boolean autoActions = true;
    private String dataDir = System.getProperty("user.home") + "/.centercontrol";
    private int commentPollMs = 400;
    private int actionMinGapMs = 8000;
    private int actionMaxGapMs = 18000;
    private int loopMinIntervalMs = 15000;
    private int loopMaxIntervalMs = 60000;
    private boolean openUi = true;

    public String getRoomUrl() {
        return roomUrl;
    }

    public void setRoomUrl(String roomUrl) {
        this.roomUrl = roomUrl;
    }

    public String getBrowserMode() {
        return browserMode;
    }

    public void setBrowserMode(String browserMode) {
        this.browserMode = browserMode;
    }

    public String getCdpUrl() {
        return cdpUrl;
    }

    public void setCdpUrl(String cdpUrl) {
        this.cdpUrl = cdpUrl;
    }

    public boolean isLaunchIfMissing() {
        return launchIfMissing;
    }

    public void setLaunchIfMissing(boolean launchIfMissing) {
        this.launchIfMissing = launchIfMissing;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public boolean isAutoActions() {
        return autoActions;
    }

    public void setAutoActions(boolean autoActions) {
        this.autoActions = autoActions;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getCommentPollMs() {
        return commentPollMs;
    }

    public void setCommentPollMs(int commentPollMs) {
        this.commentPollMs = commentPollMs;
    }

    public int getActionMinGapMs() {
        return actionMinGapMs;
    }

    public void setActionMinGapMs(int actionMinGapMs) {
        this.actionMinGapMs = actionMinGapMs;
    }

    public int getActionMaxGapMs() {
        return actionMaxGapMs;
    }

    public void setActionMaxGapMs(int actionMaxGapMs) {
        this.actionMaxGapMs = actionMaxGapMs;
    }

    public int getLoopMinIntervalMs() {
        return loopMinIntervalMs;
    }

    public void setLoopMinIntervalMs(int loopMinIntervalMs) {
        this.loopMinIntervalMs = loopMinIntervalMs;
    }

    public int getLoopMaxIntervalMs() {
        return loopMaxIntervalMs;
    }

    public void setLoopMaxIntervalMs(int loopMaxIntervalMs) {
        this.loopMaxIntervalMs = loopMaxIntervalMs;
    }

    public boolean isOpenUi() {
        return openUi;
    }

    public void setOpenUi(boolean openUi) {
        this.openUi = openUi;
    }

    /**
     * 启动时恢复上次在页面上设置的评论间隔，避免重启后又回到 15-60 秒。
     * 文件位置：{dataDir}/interval.txt，内容形如 "30,120"（秒）。
     */
    @PostConstruct
    public void loadPersistedInterval() {
        Path file = Path.of(dataDir, "interval.txt");
        try {
            if (!Files.exists(file)) {
                return;
            }
            String[] parts = Files.readString(file).trim().split("[,\\s]+");
            if (parts.length < 2) {
                return;
            }
            int min = Integer.parseInt(parts[0].trim());
            int max = Integer.parseInt(parts[1].trim());
            if (min >= MIN_INTERVAL_SECONDS && max >= min && max <= MAX_INTERVAL_SECONDS) {
                loopMinIntervalMs = min * 1000;
                loopMaxIntervalMs = max * 1000;
            }
        } catch (Exception ignored) {
            // 文件坏了就按 application.yml 的默认值走，不影响启动
        }
    }

    public void savePersistedInterval() {
        try {
            Path dir = Path.of(dataDir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("interval.txt"),
                    (loopMinIntervalMs / 1000) + "," + (loopMaxIntervalMs / 1000) + System.lineSeparator());
        } catch (Exception ignored) {
        }
    }
}
