package com.example.centercontro.browser;

import com.example.centercontro.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class CommentWatcher {

    private static final Logger log = LoggerFactory.getLogger(CommentWatcher.class);

    private static final String INSTALL_JS = """
            (() => {
              if (window.__ccInstalled) return true;
              window.__ccInstalled = true;
              window.__ccBuffer = [];
              const state = new WeakMap();
              let scheduled = false;

              const textOf = (node) => (node && (node.innerText || node.textContent) || '').replace(/\\s+/g, ' ').trim();

              const parse = (el) => {
                const rawText = textOf(el);
                if (!rawText) return null;
                const userEl = el.querySelector('.v8LY0gZF')
                  || el.querySelector('[class*="nickname"]')
                  || el.querySelector('[class*="Nickname"]');
                const contentEl = el.querySelector('.cL385mHb');
                let nickname = userEl ? textOf(userEl).replace(/[：:]\\s*$/, '').trim() : '';
                let content = contentEl ? textOf(contentEl) : '';
                const idx = rawText.indexOf('：') > 0 ? rawText.indexOf('：') : rawText.indexOf(':');
                // 昵称解析兜底：抖音改版后混淆类名会变，昵称空掉会让"同一个人 10 分钟冷却"
                // 静默失效（同一个人被反复回复）。这里只要文本里有分隔符就一定能拿到昵称。
                if (!nickname && idx > 0) {
                  nickname = rawText.slice(0, idx).trim();
                }
                if (!content) {
                  content = idx > 0 ? rawText.slice(idx + 1).trim() : rawText;
                }
                return {
                  id: el.getAttribute('data-id') || '',
                  nickname,
                  content,
                  rawText,
                  at: Date.now()
                };
              };

              const scan = () => {
                scheduled = false;
                document.querySelectorAll('.webcast-chatroom___item').forEach((el) => {
                  const parsed = parse(el);
                  if (!parsed) return;
                  if (state.get(el) === parsed.rawText) return;
                  state.set(el, parsed.rawText);
                  window.__ccBuffer.push(parsed);
                });
                if (window.__ccBuffer.length > 300) {
                  window.__ccBuffer.splice(0, window.__ccBuffer.length - 300);
                }
              };

              const schedule = () => {
                if (scheduled) return;
                scheduled = true;
                setTimeout(scan, 120);
              };

              new MutationObserver(schedule).observe(document.body, {
                childList: true,
                subtree: true,
                characterData: true
              });
              window.__ccDrain = () => {
                const rows = window.__ccBuffer || [];
                window.__ccBuffer = [];
                return { installed: true, rows };
              };
              scan();
              return true;
            })()
            """;

    private static final String DRAIN_JS = """
            () => {
              if (!window.__ccInstalled || !window.__ccDrain) {
                return { installed: false, rows: [] };
              }
              return window.__ccDrain();
            }
            """;

    private final BrowserSession browser;
    private final AppProperties properties;
    private final Consumer<Comment> sink;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong seq = new AtomicLong(0);
    private final Map<String, Long> seen = new HashMap<>();
    /** 主播昵称（从页面标题解析）。主播自己发的公屏消息不该被当成观众评论喂给 AI。 */
    private volatile String anchorName = "";

    public CommentWatcher(BrowserSession browser, AppProperties properties,
                          Consumer<Comment> sink) {
        this.browser = browser;
        this.properties = properties;
        this.sink = sink;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "comments-" + browser.id());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        install();
        scheduler.scheduleWithFixedDelay(
                this::poll,
                50,
                Math.max(150, properties.getCommentPollMs()),
                TimeUnit.MILLISECONDS
        );
    }

    private void install() {
        try {
            browser.runVoid(page -> page.evaluate(INSTALL_JS));
        } catch (Exception e) {
            log.warn("评论监听注入失败: {}", e.getMessage());
        }
    }

    private void poll() {
        if (!running.get()) {
            return;
        }
        try {
            Object result = browser.run(page -> page.evaluate(DRAIN_JS));
            if (!(result instanceof Map<?, ?> map)) {
                return;
            }
            if (!Boolean.TRUE.equals(map.get("installed"))) {
                install();
                return;
            }
            Object rowsObject = map.get("rows");
            if (!(rowsObject instanceof List<?> rows)) {
                return;
            }
            for (Object rowObject : rows) {
                if (!(rowObject instanceof Map<?, ?> row)) {
                    continue;
                }
                Comment comment = toComment(row);
                if (comment != null && accept(comment)) {
                    sink.accept(comment);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.debug("评论轮询失败: {}", e.getMessage());
            }
        }
    }

    private Comment toComment(Map<?, ?> row) {
        String nickname = stringValue(row.get("nickname"));
        String content = cleanText(stringValue(row.get("content")));
        String rawText = stringValue(row.get("rawText"));
        String id = stringValue(row.get("id"));
        if (rawText.isBlank()) {
            return null;
        }
        boolean system = rawText.contains("加入了直播间")
                || rawText.contains("为主播点赞了")
                || rawText.contains("关注了主播")
                || rawText.contains("送出了")
                || rawText.contains("欢迎来到直播间")
                || rawText.contains("抖音严禁")
                || (content.isBlank() && rawText.endsWith("来了"))
                // 主播/助教自己在公屏上说的话：不是观众评论。
                // 不排除的话，前端会把它当成普通观众发言混在评论列表里。
                || isFromAnchor(nickname);
        return new Comment(
                seq.incrementAndGet(),
                id,
                nickname,
                content.isBlank() ? cleanText(rawText) : content,
                rawText,
                system,
                Instant.now()
        );
    }

    private boolean isFromAnchor(String nickname) {
        String anchor = anchorName;
        if (anchor == null || anchor.isBlank() || nickname == null || nickname.isBlank()) {
            return false;
        }
        return anchor.equals(nickname.trim());
    }

    /**
     * 主播用富文本输入框发的消息，innerText 里会带 {@code <div><br></div>} 这种字面量，
     * 直接喂给模型会变成乱码一样的内容。这里把标签剥掉。
     */
    private String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("<[^>]{0,60}>", " ").replaceAll("\\s+", " ").trim();
    }

    public void setAnchorName(String anchorName) {
        this.anchorName = anchorName == null ? "" : anchorName.trim();
    }

    private boolean accept(Comment comment) {
        long now = System.currentTimeMillis();
        String key = comment.id().isBlank()
                ? comment.nickname() + "|" + comment.text() + "|" + (now / 60_000)
                : comment.id() + "|" + comment.rawText();
        synchronized (seen) {
            Long last = seen.get(key);
            if (last != null && now - last < 45_000L) {
                return false;
            }
            seen.put(key, now);
            if (seen.size() > 800) {
                seen.entrySet().removeIf(e -> now - e.getValue() > 180_000L);
            }
        }
        return true;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
    }

    public void reset() {
        synchronized (seen) {
            seen.clear();
        }
        if (running.get()) {
            install();
        }
    }
}
