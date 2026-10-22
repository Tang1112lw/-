package com.example.centercontro.assistant;

import com.example.centercontro.browser.Comment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SessionStatus(
        String id,
        String name,
        String state,
        String phase,
        String roomUrl,
        String cdpUrl,
        boolean loggedIn,
        boolean autoEnabled,
        String browserTitle,
        String currentUrl,
        int commentCount,
        Map<String, Integer> sentActions,
        Comment lastComment,
        List<Comment> recentComments,
        CommentPlan lastDecision,
        CommentPlan pendingDecision,
        Instant pendingCommentAt,
        Integer pendingIntervalSeconds,
        String lastAction,
        String lastError,
        Instant startedAt,
        Instant nextRunAt,
        List<String> logs,
        Map<String, Object> policy
) {
}
