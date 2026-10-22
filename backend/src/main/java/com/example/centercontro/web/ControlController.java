package com.example.centercontro.web;

import com.example.centercontro.assistant.CommentPlan;
import com.example.centercontro.assistant.LiveSession;
import com.example.centercontro.assistant.SessionManager;
import com.example.centercontro.assistant.SessionStatus;
import com.example.centercontro.browser.LiveActions;
import com.example.centercontro.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ControlController {

    private final SessionManager sessions;
    private final AppProperties properties;

    public ControlController(SessionManager sessions, AppProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return sessions.appStatus();
    }

    @GetMapping("/sessions")
    public List<SessionStatus> sessions() {
        return sessions.status();
    }

    @PostMapping("/sessions/{id}/start")
    public Map<String, Object> start(@PathVariable String id) {
        return Map.of("ok", true, "message", sessions.start(id));
    }

    @PostMapping("/sessions/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        return Map.of("ok", true, "message", sessions.stop(id));
    }

    @PostMapping("/sessions/start-all")
    public Map<String, Object> startAll() {
        sessions.startAll();
        return Map.of("ok", true, "message", "正在启动全部会话");
    }

    @PostMapping("/sessions/stop-all")
    public Map<String, Object> stopAll() {
        sessions.stopAll();
        return Map.of("ok", true, "message", "正在停止全部会话");
    }

    @PostMapping("/sessions/{id}/auto")
    public Map<String, Object> auto(@PathVariable String id, @RequestParam boolean enabled) {
        sessions.setAuto(id, enabled);
        return Map.of("ok", true, "autoEnabled", enabled);
    }

    @PostMapping("/sessions/{id}/login")
    public Map<String, Object> login(@PathVariable String id) {
        return Map.of("ok", true, "message", sessions.openLogin(id));
    }

    @PostMapping("/sessions/{id}/manual")
    public LiveActions.ActionResult manual(@PathVariable String id, @RequestBody CommentPlan plan) {
        return sessions.manual(id, plan);
    }

    @PostMapping("/sessions/{id}/tick")
    public Map<String, Object> tick(@PathVariable String id) {
        return Map.of("ok", true, "message", sessions.tick(id));
    }

    @PostMapping("/sessions/{id}/room")
    public Map<String, Object> updateRoom(@PathVariable String id, @RequestBody RoomRequest request) {
        String message = sessions.updateRoom(id, request.roomUrl());
        return Map.of(
                "ok", true,
                "message", message,
                "roomUrl", sessions.get(id).roomUrl()
        );
    }

    @GetMapping("/sessions/{id}/comments")
    public Map<String, Object> comments(@PathVariable String id,
                                        @RequestParam(defaultValue = "0") long afterSeq,
                                        @RequestParam(defaultValue = "100") int limit) {
        LiveSession session = sessions.get(id);
        return Map.of(
                "commentCount", session.commentCount(),
                "comments", session.commentsAfter(afterSeq, Math.min(200, Math.max(1, limit)))
        );
    }

    @PostMapping("/settings/interval")
    public Map<String, Object> configureInterval(@RequestBody IntervalRequest request) {
        int min = Math.max(AppProperties.MIN_INTERVAL_SECONDS,
                Math.min(AppProperties.MAX_INTERVAL_SECONDS, request.minSeconds()));
        int max = Math.max(min, Math.min(AppProperties.MAX_INTERVAL_SECONDS, request.maxSeconds()));
        properties.setLoopMinIntervalMs(min * 1000);
        properties.setLoopMaxIntervalMs(max * 1000);
        properties.savePersistedInterval();
        return Map.of("ok", true, "minSeconds", min, "maxSeconds", max);
    }

    public record IntervalRequest(int minSeconds, int maxSeconds) {
    }

    public record RoomRequest(String roomUrl) {
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("ok", false, "message", message, "at", Instant.now().toString()));
    }
}
