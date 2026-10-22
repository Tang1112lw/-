package com.example.centercontro.browser;

import com.example.centercontro.assistant.CommentPlan;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Random;

/**
 * 直播间动作层。只保留「发评论」一个动作，点赞逻辑已移除。
 */
public class LiveActions {

    private static final Logger log = LoggerFactory.getLogger(LiveActions.class);

    private static final String COMMENT_EDITOR =
            "#chatroom div[contenteditable='true']";

    private final BrowserSession browser;
    private final Random random;

    public LiveActions(BrowserSession browser, Random random) {
        this.browser = browser;
        this.random = random;
    }

    public ActionResult execute(CommentPlan plan) {
        if (plan == null || plan.text() == null || plan.text().isBlank()) {
            return new ActionResult(false, "COMMENT", "没有可发送的评论", Instant.now());
        }
        humanPause(500, 1400);
        try {
            return sendComment(plan.text());
        } catch (Exception e) {
            String message = shortMessage(e);
            log.warn("评论发送失败: {}", message);
            return new ActionResult(false, "COMMENT", "执行失败: " + message, Instant.now());
        }
    }

    private ActionResult sendComment(String rawText) {
        String text = rawText == null ? "" : rawText.replaceAll("\\s+", " ").trim();
        if (text.isBlank()) {
            return new ActionResult(false, "COMMENT", "评论内容为空", Instant.now());
        }
        if (text.length() > 60) {
            text = text.substring(0, 60);
        }
        final String message = text;
        return browser.run(page -> {
            Locator editor = findCommentEditor(page);
            if (editor == null) {
                return new ActionResult(false, "COMMENT",
                        "未找到评论输入框，页面可能未登录或结构已变化", Instant.now());
            }
            editor.click();
            humanPause(160, 420);
            editor.fill(message);
            humanPause(380, 950);
            editor.press("Enter");
            page.waitForTimeout(1800);

            String remaining = "";
            try {
                remaining = editor.innerText();
            } catch (Exception ignored) {
            }
            boolean cleared = remaining == null || remaining.replace("\u200b", "").isBlank();
            boolean appeared = page.locator(".webcast-chatroom___item",
                    new Page.LocatorOptions().setHasText(message)).count() > 0;
            if (cleared || appeared) {
                return new ActionResult(true, "COMMENT", "评论已发送: " + message, Instant.now());
            }
            return new ActionResult(false, "COMMENT",
                    "已按回车，但未确认发送成功；请在浏览器中检查", Instant.now());
        });
    }

    private Locator findCommentEditor(Page page) {
        List<String> selectors = List.of(
                COMMENT_EDITOR,
                "div.webcast-chatroom___input-container div[contenteditable='true']",
                "div[contenteditable='true']",
                "textarea[placeholder*='互动']"
        );
        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector);
                int count = locator.count();
                for (int i = 0; i < count; i++) {
                    Locator candidate = locator.nth(i);
                    if (candidate.isVisible()) {
                        return candidate;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void humanPause(int minMillis, int maxMillis) {
        int min = Math.max(50, minMillis);
        int max = Math.max(min, maxMillis);
        try {
            Thread.sleep(min + random.nextInt(max - min));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String shortMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage().replaceAll("\\s+", " ").trim();
        return message.length() > 220 ? message.substring(0, 220) + "..." : message;
    }

    public record ActionResult(boolean success, String action, String message, Instant at) {
    }
}