package com.example.centercontro.assistant;

import com.example.centercontro.browser.BrowserSession;
import com.example.centercontro.browser.CommentWatcher;
import com.example.centercontro.browser.LiveActions;
import com.example.centercontro.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 单会话循环：每轮从本地语料库随机取一条评论，到点自动发送。
 *
 * <p>不调用任何模型，也不读取直播间评论来决定说什么；评论区只用于前端展示。
 */
@Service
public class LiveAssistantService {

    private static final Logger log = LoggerFactory.getLogger(LiveAssistantService.class);

    private final CommentCorpus corpus;
    private final AppProperties properties;
    private final Random random = new Random();

    public LiveAssistantService(CommentCorpus corpus, AppProperties properties) {
        this.corpus = corpus;
        this.properties = properties;
    }

    public void start(LiveSession session) {
        boolean reconnect = false;
        synchronized (session.lifecycleLock()) {
            if ("STARTING".equals(session.state())) {
                session.addLog("会话正在启动中");
                return;
            }
            if ("RUNNING".equals(session.state())) {
                BrowserSession existing = session.browser();
                if (existing != null && existing.isAlive()) {
                    session.addLog("会话已在运行");
                    return;
                }
                // 状态还是 RUNNING，但浏览器/page 已经没了（用户关掉了自动化窗口、
                // Edge 崩溃、或上次停止没清干净）。这时必须允许重新启动，不能直接返回。
                reconnect = true;
            }
            if ("STOPPING".equals(session.state())) {
                // 停止还没收尾就把浏览器交出去，stop 里的 close() 会把新连接一起关掉
                // （TargetClosedError）。宁可让用户等一下。
                session.addLog("上一次停止还没完成，稍等一两秒再点启动");
                return;
            }
            // stop() 会把 cancelRequested 设为 true。停止已经完成后再次启动是
            // 一次全新的启动请求，必须清掉上一次留下的取消标记，否则会立刻自我取消。
            session.setCancelRequested(false);
        }
        if (reconnect) {
            session.addLog("检测到浏览器已断开，先清理旧连接再重新启动");
            session.close();
        }
        synchronized (session.lifecycleLock()) {
            if (session.cancelRequested()) {
                session.setState("STOPPED");
                session.setPhase("STOPPED");
                session.addLog("启动已取消（期间收到停止请求）");
                return;
            }
            session.setCancelRequested(false);
            session.setState("STARTING");
        }
        session.setPhase("CONNECTING");
        session.setStartedAt(Instant.now());
        session.setLastError("");
        session.addLog("正在连接浏览器 " + session.cdpUrl());

        BrowserSession browser = null;
        try {
            browser = new BrowserSession(session.id(), session.cdpUrl(), session.profileDir(), properties);
            session.setBrowser(browser);
            browser.start(session.roomUrl());
            if (!browser.profileDescription().isBlank()) {
                session.addLog("浏览器桥接配置: " + browser.profileDescription()
                        + "；登录态继续使用此配置");
            }

            ActionPolicy policy = new ActionPolicy(properties, new Random());
            session.setPolicy(policy);
            session.setActions(new LiveActions(browser, new Random()));

            CommentWatcher watcher = new CommentWatcher(browser, properties, session::onComment);
            session.setWatcher(watcher);
            watcher.start();

            session.setLoggedIn(browser.isLoggedInStable());
            session.setBrowserTitle(browser.title());
            session.setCurrentUrl(browser.currentUrl());
            watcher.setAnchorName(anchorName(session.browserTitle()));
            if (!session.loggedIn()) {
                session.addLog(browser.openLogin());
            }

            synchronized (session.lifecycleLock()) {
                if (session.cancelRequested()) {
                    // 连接期间用户点了停止：这里不能再把状态改成 RUNNING 覆盖掉。
                    session.addLog("启动过程中收到停止请求，已取消本次启动");
                    session.setPhase("STOPPED");
                    session.setState("STOPPED");
                    closeQuietly(browser);
                    session.setBrowser(null);
                    return;
                }
                session.setState("RUNNING");
            }
            session.setPhase("WAITING");
            session.addLog("浏览器已连接；登录状态=" + (session.loggedIn() ? "已登录" : "未登录")
                    + "；评论语料库=" + corpus.totalSize() + " 条");

            int min = Math.max(1_000, properties.getLoopMinIntervalMs());
            int max = Math.max(min, properties.getLoopMaxIntervalMs());
            session.addLog("自动评论已启用：每轮随机场景取一条语料，轮间隔 "
                    + (min / 1000) + "-" + (max / 1000) + " 秒");
            scheduleNextLoop(session, 3_000);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (session.cancelRequested()) {
                // 是"启动途中被停止"造成的异常，不是真的配置错误，别把它记成 ERROR。
                session.setState("STOPPED");
                session.setPhase("STOPPED");
                session.addLog("启动已取消（期间收到停止请求）");
            } else {
                session.setState("ERROR");
                session.setPhase("ERROR");
                session.setLastError(message);
                session.addLog("启动失败: " + message);
                log.warn("session {} start failed", session.id(), e);
            }
            closeQuietly(browser);
        }
    }

    /**
     * 从页面标题里取主播昵称。
     *
     * <p>抖音直播间标题形如「涌浪AI成长社的抖音直播间 - 抖音直播」，取"的抖音直播间"前面的部分。
     * 取不到就返回空串（这时不做主播过滤，行为与以前一致）。
     */
    private String anchorName(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        int idx = title.indexOf("的抖音直播间");
        if (idx <= 0) {
            idx = title.indexOf("的直播间");
        }
        if (idx <= 0) {
            return "";
        }
        return title.substring(0, idx).trim();
    }

    private void closeQuietly(BrowserSession browser) {
        if (browser == null) {
            return;
        }
        try {
            browser.close();
        } catch (Exception ignored) {
        }
    }

    private void scheduleNextLoop(LiveSession session, int delayMs) {
        if (!"RUNNING".equals(session.state())) {
            return;
        }
        session.setPhase("WAITING");
        session.setNextRunAt(Instant.now().plusMillis(Math.max(0, delayMs)));
        var future = session.worker().schedule(() -> {
            session.setNextRunAt(null);
            try {
                processOnce(session);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                session.setLastError(message);
                session.addLog("本轮异常: " + message);
            }
            int next;
            if (session.pendingDecision() != null && session.pendingCommentAt() != null) {
                long millis = Duration.between(Instant.now(), session.pendingCommentAt()).toMillis();
                next = (int) Math.max(0, Math.min(Integer.MAX_VALUE, millis));
            } else {
                // 没排定评论时的重试间隔（正常情况不会走到）。
                next = Math.max(10_000, Math.min(20_000, properties.getLoopMinIntervalMs()));
            }
            session.setPhase("WAITING");
            session.setNextRunAt(Instant.now().plusMillis(next));
            if (session.pendingDecision() == null) {
                session.addLog("本轮没有排定评论，" + Math.max(1, next / 1000) + " 秒后再试");
            } else {
                session.addLog("下次评论已排定，" + Math.max(0, next / 1000) + " 秒后执行");
            }
            scheduleNextLoop(session, next);
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        session.setLoopFuture(future);
    }

    private void processOnce(LiveSession session) {
        if (!"RUNNING".equals(session.state())) {
            return;
        }
        try {
            BrowserSession browser = session.browser();
            if (browser == null) {
                return;
            }
            if (!browser.isAlive()) {
                String message = "浏览器连接已断开，自动评论已暂停；点击启动会话重新接入持久浏览器";
                session.setState("ERROR");
                session.setPhase("DISCONNECTED");
                session.setLastError(message);
                session.addLog(message);
                return;
            }
            boolean wasLoggedIn = session.loggedIn();
            boolean loggedIn = browser.isLoggedIn();
            session.setLoggedIn(loggedIn);
            session.setBrowserTitle(browser.title());
            session.setCurrentUrl(browser.currentUrl());
            // 主播昵称会随房间变化，每轮顺手刷新，供 CommentWatcher 过滤主播自己的发言。
            CommentWatcher watcher = session.watcher();
            if (watcher != null) {
                watcher.setAnchorName(anchorName(session.browserTitle()));
            }
            if (wasLoggedIn != loggedIn) {
                session.addLog(loggedIn ? "检测到登录成功" : "检测到未登录（评论会失败，请扫码）");
            }

            if (browser.isRoomEnded()) {
                session.setPhase("ENDED");
                if (!session.roomEnded()) {
                    session.setRoomEnded(true);
                    session.addLog("直播间已结束（下播）：暂停所有动作。换一个直播中的地址后自动恢复");
                }
                return;
            }
            if (session.roomEnded()) {
                session.setRoomEnded(false);
                session.addLog("检测到直播间恢复中/已切换房间，继续");
            }

            ActionPolicy policy = session.policy();
            if (policy == null) {
                return;
            }

            CommentPlan pending = session.pendingDecision();
            Instant due = session.pendingCommentAt();
            if (pending != null && due != null && !Instant.now().isBefore(due)) {
                if (policy.isAutoEnabled() && policy.allow(pending)) {
                    session.setPhase("ACTING");
                    try {
                        execute(session, pending);
                    } finally {
                        // 执行过程中抛任何异常都不能把 phase 留在 ACTING，
                        // 否则前端会一直显示"执行中…"，看不出真实状态。
                        session.setPhase("WAITING");
                    }
                } else {
                    session.addLog("未执行：" + policy.rejectReason(pending));
                }
                session.clearPending();
            }
            if (session.pendingDecision() != null) {
                return;
            }

            session.setPhase("GENERATING");
            CommentPlan plan = corpus.next();
            session.setLastDecision(plan);
            int interval = randomIntervalSeconds();
            session.setPendingDecision(plan);
            session.setPendingCommentAt(Instant.now().plusSeconds(interval));
            session.setPendingIntervalSeconds(interval);
            session.setPhase("WAITING");
            session.addLog("已规划下次评论[" + plan.scene() + "] \""
                    + plan.text() + "\" " + interval + " 秒后执行");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            session.setLastError(message);
            session.addLog("处理异常: " + message);
            log.debug("session {} process failed", session.id(), e);
        }
    }

    private int randomIntervalSeconds() {
        int min = Math.max(1, properties.getLoopMinIntervalMs() / 1000);
        int max = Math.max(min, properties.getLoopMaxIntervalMs() / 1000);
        return min + random.nextInt(Math.max(1, max - min + 1));
    }

    public LiveActions.ActionResult manual(LiveSession session, CommentPlan plan) {
        if (session.actions() == null || session.policy() == null) {
            return new LiveActions.ActionResult(false, "COMMENT", "会话未启动", Instant.now());
        }
        try {
            return session.worker().submit(() -> {
                if (!session.policy().allowManual(plan)) {
                    session.addLog("人工动作被硬限制拦截（内容为空、重复或命中敏感词）");
                    return new LiveActions.ActionResult(false, "COMMENT",
                            "硬限制拦截：内容为空、重复或命中敏感词", Instant.now());
                }
                session.setPhase("ACTING");
                LiveActions.ActionResult result;
                try {
                    result = execute(session, plan);
                } finally {
                    session.setPhase("WAITING");
                }
                if (result.success()) {
                    session.clearPending();
                }
                return result;
            }).get(90, TimeUnit.SECONDS);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new LiveActions.ActionResult(false, "COMMENT",
                    "执行异常: " + message, Instant.now());
        }
    }

    public String switchRoom(LiveSession session, String roomUrl) {
        boolean running = "RUNNING".equals(session.state()) && session.browser() != null;
        if (!running) {
            session.resetForNewRoom(roomUrl);
            return "直播间地址已更新，下次启动生效";
        }
        boolean navigated = false;
        try {
            session.browser().navigateToRoom(roomUrl);
            navigated = true;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            session.addLog("切换地址已保存，但页面导航失败: " + shorten(message));
        }
        if (session.watcher() != null) {
            session.watcher().reset();
        }
        // 换房间必须连策略一起重置：上一场说过的话、频控状态都属于"本场"。
        if (session.policy() != null) {
            session.policy().reset();
        }
        session.resetForNewRoom(roomUrl);
        if (navigated) {
            session.worker().submit(() -> processOnce(session));
            return "已切换直播间";
        }
        return "地址已保存；当前页面导航失败，请点停止后重新启动";
    }

    private String shorten(String message) {
        String value = message.replaceAll("\\s+", " ").trim();
        return value.length() > 180 ? value.substring(0, 180) + "..." : value;
    }

    private LiveActions.ActionResult execute(LiveSession session, CommentPlan plan) {
        LiveActions.ActionResult result = session.actions().execute(plan);
        if (result.success()) {
            session.policy().record(plan);
            session.recordSentComment();
            session.setLastAction(result.message());
            session.addLog("执行成功: " + result.message());
        } else {
            session.setLastAction("失败: " + result.message());
            session.addLog("执行失败: " + result.message());
        }
        return result;
    }

    public void stop(LiveSession session) {
        synchronized (session.lifecycleLock()) {
            session.setCancelRequested(true);
            session.setPhase("STOPPING");
        }
        session.addLog("正在停止会话");
        session.setNextRunAt(null);
        session.close();
        session.addLog("会话已停止");
    }

    public void tick(LiveSession session) {
        if (!"RUNNING".equals(session.state())) {
            session.addLog("会话未运行，无法立即生成");
            return;
        }
        session.worker().submit(() -> {
            session.addLog("手动触发一次生成");
            session.clearPending();
            session.setPhase("GENERATING");
            try {
                processOnce(session);
            } finally {
                session.setPhase("WAITING");
            }
        });
    }
}
