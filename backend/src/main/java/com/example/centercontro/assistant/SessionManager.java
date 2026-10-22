package com.example.centercontro.assistant;

import com.example.centercontro.browser.LiveActions;
import com.example.centercontro.browser.LoginSync;
import com.example.centercontro.config.AppProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();
    private final LiveAssistantService assistant;
    private final AppProperties properties;
    private final CommentCorpus corpus;

    public SessionManager(LiveAssistantService assistant, AppProperties properties, CommentCorpus corpus) {
        this.assistant = assistant;
        this.properties = properties;
        this.corpus = corpus;
        for (int i = 1; i <= Math.max(1, Math.min(3, properties.getMaxSessions())); i++) {
            String id = "s" + i;
            String cdp = i == 1
                    ? properties.getCdpUrl()
                    : "http://127.0.0.1:" + (9221 + i);
            // 浏览器配置始终在运行时从当前 Edge User Data 解析，项目不保存路径。
            String profile = "";
            String roomUrl = i == 1
                    ? loadRoomUrl().orElse(properties.getRoomUrl())
                    : properties.getRoomUrl();
            LiveSession session = new LiveSession(id, "会话 " + i, roomUrl, cdp, profile);
            session.addLog("已创建，浏览器端口 " + cdp);
            sessions.put(id, session);
        }
    }

    public List<SessionStatus> status() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(LiveSession::id))
                .map(LiveSession::snapshot)
                .toList();
    }

    public LiveSession get(String id) {
        LiveSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        return session;
    }

    public String start(String id) {
        LiveSession session = get(id);
        if ("STARTING".equals(session.state())) {
            return "会话正在启动";
        }
        if ("RUNNING".equals(session.state()) && session.browser() != null && session.browser().isAlive()) {
            return "会话已在运行";
        }
        CompletableFuture.runAsync(() -> assistant.start(session));
        return "正在启动 " + session.name();
    }

    public void startAll() {
        sessions.values().stream()
                .sorted(Comparator.comparing(LiveSession::id))
                .forEach(session -> start(session.id()));
    }

    public String stop(String id) {
        LiveSession session = get(id);
        CompletableFuture.runAsync(() -> assistant.stop(session));
        return "正在停止";
    }

    public void stopAll() {
        sessions.values().stream()
                .sorted(Comparator.comparing(LiveSession::id))
                .forEach(session -> stop(session.id()));
    }

    public LiveActions.ActionResult manual(String id, CommentPlan plan) {
        return assistant.manual(get(id), plan);
    }

    public String tick(String id) {
        LiveSession session = get(id);
        if (!"RUNNING".equals(session.state())) {
            return "会话未运行";
        }
        assistant.tick(session);
        return "已触发一次生成";
    }

    public void setAuto(String id, boolean enabled) {
        LiveSession session = get(id);
        if (session.policy() == null) {
            throw new IllegalStateException("会话尚未启动");
        }
        session.policy().setAutoEnabled(enabled);
        session.addLog(enabled ? "已开启自动动作（严格频控）" : "已关闭自动动作");
    }

    public String openLogin(String id) {
        LiveSession session = get(id);
        if (session.browser() == null) {
            return "会话尚未启动，请先点击启动";
        }
        String message = session.browser().openLogin();
        session.addLog(message);
        return message;
    }

    public String updateRoom(String id, String roomUrl) {
        String normalized = normalizeRoomUrl(roomUrl);
        String message = assistant.switchRoom(get(id), normalized);
        saveRoomUrl(normalized);
        return message;
    }

    public Map<String, Object> appStatus() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("corpusSize", corpus.totalSize());
        map.put("corpusThreads", corpus.threadCount());
        map.put("corpusScenes", corpus.sizes());
        LiveSession first = sessions.get("s1");
        map.put("roomUrl", first != null ? first.roomUrl() : properties.getRoomUrl());
        map.put("browserMode", properties.getBrowserMode());
        map.put("cdpUrl", properties.getCdpUrl());
        map.put("autoActions", properties.isAutoActions());
        map.put("maxSessions", sessions.size());
        map.put("loopMinIntervalMs", properties.getLoopMinIntervalMs());
        map.put("loopMaxIntervalMs", properties.getLoopMaxIntervalMs());
        map.put("loginSync", LoginSync.describe(properties.getDataDir()));
        map.put("advice", "默认自动执行；每轮最多一个动作，轮间隔可调。真实直播间操作有账号风险，请遵守平台规则。");
        return map;
    }

    @PreDestroy
    public void shutdown() {
        log.info("正在关闭全部会话");
        for (LiveSession session : new ArrayList<>(sessions.values())) {
            try {
                session.shutdown();
            } catch (Exception e) {
                log.warn("关闭会话 {} 失败: {}", session.id(), e.getMessage());
            }
        }
    }

    private String normalizeRoomUrl(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("请输入直播间地址");
        }
        String value = input.trim();
        Matcher matcher = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE).matcher(value);
        if (matcher.find()) {
            value = matcher.group();
        }
        value = value.replaceAll("[，。；;]+$", "");
        if (value.length() > 2000) {
            throw new IllegalArgumentException("直播间地址过长");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("直播间地址格式不正确");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("直播间地址必须是 http/https");
        }
        if (host == null || !(host.equalsIgnoreCase("douyin.com")
                || host.toLowerCase().endsWith(".douyin.com"))) {
            throw new IllegalArgumentException("只支持抖音直播间地址（douyin.com）");
        }
        // 抖音搜索结果页 / 精选页的链接里带着 live_web_rid，可以直接换成直播间直链。
        // 停在搜索页时页面上没有评论输入框，评论会全部失败。
        String rid = extractLiveWebRid(value);
        if (rid != null && !rid.isBlank()) {
            return "https://live.douyin.com/" + rid;
        }
        return value;
    }

    /** 从抖音搜索/精选页链接里取出直播间 rid（live_web_rid），取不到返回 null。 */
    private String extractLiveWebRid(String url) {
        Matcher matcher = Pattern.compile("[?&]live_web_rid=(\\d+)").matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private Optional<String> loadRoomUrl() {
        Path file = Paths.get(properties.getDataDir(), "room.txt");
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? Optional.empty() : Optional.of(normalizeRoomUrl(value));
        } catch (Exception e) {
            log.warn("读取直播间地址失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void saveRoomUrl(String roomUrl) {
        Path file = Paths.get(properties.getDataDir(), "room.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, roomUrl, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存直播间地址失败: {}", e.getMessage());
        }
    }
}
