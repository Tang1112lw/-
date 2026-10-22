package com.example.centercontro.assistant;

import com.example.centercontro.config.AppProperties;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 评论的硬限制层：频控、去重、长度、敏感词。
 *
 * <p>这里只做检查，不产生任何文本。评论内容全部来自 {@link CommentCorpus}。
 */
public class ActionPolicy {

    private static final Set<String> BANNED_WORDS = Set.of(
            "微信", "加我", "私聊", "刷单", "返利", "贷款", "赌博", "色情",
            "政治", "政治敏感", "代购", "兼职", "日结", "联系方式", "二维码"
    );
    private static final int RECENT_LIMIT = 15;

    private final AppProperties properties;
    private final Random random;
    private final Deque<String> recentTexts = new ArrayDeque<>();

    private volatile boolean autoEnabled;
    private volatile long lastActionAt = 0;
    private volatile long nextAllowedAt = 0;
    private volatile long lastSentAt = 0;

    public ActionPolicy(AppProperties properties, Random random) {
        this.properties = properties;
        this.random = random;
        this.autoEnabled = properties.isAutoActions();
    }

    public synchronized boolean allow(CommentPlan plan) {
        if (!autoEnabled || plan == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        // 间隔在"上一次动作完成时"抽一次并固定下来，而不是每次检查都重新抽，
        // 否则会出现 9 秒时判通过、10 秒时又判不通过的抖动。
        if (now < nextAllowedAt) {
            return false;
        }
        return withinHardLimits(plan);
    }

    public synchronized boolean allowManual(CommentPlan plan) {
        if (plan == null) {
            return false;
        }
        return withinHardLimits(plan);
    }

    /** 返回被拒的具体原因，便于日志直接说明白，而不是笼统的"被策略拦截"。 */
    public synchronized String rejectReason(CommentPlan plan) {
        long now = System.currentTimeMillis();
        if (!autoEnabled) {
            return "自动模式已关闭";
        }
        if (now < nextAllowedAt) {
            return "动作间隔未到（还需 " + Math.max(0, (nextAllowedAt - now) / 1000) + " 秒）";
        }
        String text = clean(plan == null ? "" : plan.text());
        if (text.isBlank()) {
            return "评论内容为空";
        }
        if (text.length() > 60) {
            return "评论超过 60 字";
        }
        if (recentTexts.contains(text)) {
            return "和最近发过的某一句完全相同（去重）";
        }
        if (BANNED_WORDS.stream().anyMatch(text::contains)) {
            return "命中敏感词";
        }
        return "通过";
    }

    private boolean withinHardLimits(CommentPlan plan) {
        String text = clean(plan.text());
        if (text.isBlank() || text.length() > 60) {
            return false;
        }
        if (recentTexts.contains(text)) {
            return false;
        }
        return BANNED_WORDS.stream().noneMatch(text::contains);
    }

    public synchronized void record(CommentPlan plan) {
        long now = System.currentTimeMillis();
        lastActionAt = now;
        int minGap = Math.max(2000, properties.getActionMinGapMs());
        int maxGap = Math.max(minGap, properties.getActionMaxGapMs());
        int gap = minGap + random.nextInt(Math.max(1, maxGap - minGap));
        // 页面允许把评论间隔调到 1 秒，比这里的动作保护间隔（默认 8-18 秒）还短。
        // 这时保护间隔必须跟着缩短，否则每轮都被"动作间隔未到"拦下，
        // 而语料游标在规划环节已经前进了，等于白丢一句评论。
        int loopMin = Math.max(1000, properties.getLoopMinIntervalMs());
        nextAllowedAt = now + Math.min(gap, loopMin);

        String text = clean(plan == null ? "" : plan.text());
        if (text.isBlank()) {
            return;
        }
        recentTexts.addLast(text);
        while (recentTexts.size() > RECENT_LIMIT) {
            recentTexts.removeFirst();
        }
        lastSentAt = now;
    }

    public long lastSentAt() {
        return lastSentAt;
    }

    public long secondsSinceLastSent() {
        long last = lastSentAt;
        return last <= 0 ? -1 : (System.currentTimeMillis() - last) / 1000;
    }

    /** 换直播间时重置所有"本场"状态，避免上一场的说话记录把新房间锁死。 */
    public synchronized void reset() {
        lastActionAt = 0;
        nextAllowedAt = 0;
        lastSentAt = 0;
        recentTexts.clear();
    }

    /** 最近发出去的话（按会话隔离，不跨会话共享）。 */
    public synchronized List<String> recentSentTexts() {
        return List.copyOf(recentTexts);
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> map = new HashMap<>();
        map.put("autoEnabled", autoEnabled);
        map.put("lastActionAt", lastActionAt);
        map.put("nextAllowedAt", nextAllowedAt);
        map.put("lastSentAt", lastSentAt);
        map.put("secondsSinceLastSent", secondsSinceLastSent());
        return map;
    }

    public void setAutoEnabled(boolean enabled) {
        this.autoEnabled = enabled;
    }

    public boolean isAutoEnabled() {
        return autoEnabled;
    }

    private String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}