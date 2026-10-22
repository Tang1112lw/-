package com.example.centercontro.assistant;

import com.example.centercontro.browser.BrowserSession;
import com.example.centercontro.browser.Comment;
import com.example.centercontro.browser.CommentWatcher;
import com.example.centercontro.browser.LiveActions;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class LiveSession {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String id;
    private final String name;
    private volatile String roomUrl;
    private final String cdpUrl;
    private final String profileDir;
    private final ScheduledExecutorService worker;
    /**
     * start / stop 的互斥锁。
     *
     * <p>原来两个操作都是 {@code CompletableFuture.runAsync} 各跑各的：先点"停止"再立刻点
     * "启动"，stop 里的 {@code browser.close()} 会把 start 正在连接的浏览器一起关掉，
     * 于是报 {@code TargetClosedError: Target page, context or browser has been closed}。
     * 这把锁只保护"状态切换"这一小段，不保护耗时的浏览器连接，否则点停止会卡住。
     */
    private final Object lifecycleLock = new Object();
    private volatile boolean cancelRequested = false;
    private final Deque<Comment> recentComments = new ArrayDeque<>();
    private final Deque<String> logs = new ArrayDeque<>();
    private final AtomicInteger commentCount = new AtomicInteger(0);
    private final AtomicInteger sentComments = new AtomicInteger(0);

    private volatile BrowserSession browser;
    private volatile CommentWatcher watcher;
    private volatile LiveActions actions;
    private volatile ActionPolicy policy;
    private volatile String state = "CREATED";
    private volatile String phase = "IDLE";
    private volatile boolean loggedIn = false;
    private volatile String browserTitle = "";
    private volatile String currentUrl = "";
    private volatile CommentPlan lastDecision;
    private volatile CommentPlan pendingDecision;
    private volatile Instant pendingCommentAt;
    private volatile Integer pendingIntervalSeconds;
    private volatile String lastAction = "";
    private volatile String lastError = "";
    private volatile boolean roomEnded = false;
    private volatile Instant startedAt;
    private volatile Instant nextRunAt;
    private volatile ScheduledFuture<?> loopFuture;

    public LiveSession(String id, String name, String roomUrl, String cdpUrl, String profileDir) {
        this.id = id;
        this.name = name;
        this.roomUrl = roomUrl;
        this.cdpUrl = cdpUrl;
        this.profileDir = profileDir;
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "assistant-" + id);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void onComment(Comment comment) {
        synchronized (recentComments) {
            recentComments.addLast(comment);
            while (recentComments.size() > 200) {
                recentComments.removeFirst();
            }
        }
        if (!comment.system()) {
            commentCount.incrementAndGet();
        }
    }

    public Comment lastComment() {
        synchronized (recentComments) {
            for (var iterator = recentComments.descendingIterator(); iterator.hasNext(); ) {
                Comment comment = iterator.next();
                if (!comment.system()) {
                    return comment;
                }
            }
        }
        return null;
    }

    public List<Comment> recentComments() {
        synchronized (recentComments) {
            return new ArrayList<>(recentComments);
        }
    }

    public List<Comment> recentChatComments(int limit) {
        synchronized (recentComments) {
            List<Comment> chat = recentComments.stream()
                    .filter(comment -> !comment.system())
                    .toList();
            int from = Math.max(0, chat.size() - Math.max(1, limit));
            return new ArrayList<>(chat.subList(from, chat.size()));
        }
    }

    public List<Comment> commentsAfter(long seq, int limit) {
        synchronized (recentComments) {
            return recentComments.stream()
                    .filter(comment -> comment.seq() > seq)
                    .limit(Math.max(1, limit))
                    .toList();
        }
    }

    public int commentCount() {
        return commentCount.get();
    }

    public void recordSentComment() {
        sentComments.incrementAndGet();
    }

    public java.util.Map<String, Integer> sentActions() {
        return java.util.Map.of("COMMENT", sentComments.get());
    }

    public void addLog(String message) {
        String line = LocalTime.now().format(TIME_FORMAT) + "  " + message;
        synchronized (logs) {
            logs.addLast(line);
            while (logs.size() > 120) {
                logs.removeFirst();
            }
        }
    }

    public List<String> logs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    public SessionStatus snapshot() {
        return new SessionStatus(
                id,
                name,
                state,
                phase,
                roomUrl,
                cdpUrl,
                loggedIn,
                policy != null && policy.isAutoEnabled(),
                browserTitle,
                currentUrl,
                commentCount.get(),
                sentActions(),
                lastComment(),
                recentChatComments(20),
                lastDecision,
                pendingDecision,
                pendingCommentAt,
                pendingIntervalSeconds,
                lastAction,
                lastError,
                startedAt,
                nextRunAt,
                logs(),
                policy == null ? java.util.Map.of() : policy.snapshot()
        );
    }

    public void close() {
        state = "STOPPING";
        phase = "STOPPED";
        nextRunAt = null;
        clearPending();
        ScheduledFuture<?> loop = loopFuture;
        if (loop != null) {
            loop.cancel(false);
        }
        if (watcher != null) {
            watcher.stop();
        }
        if (browser != null) {
            // 停会话只断开 Playwright，不关自动化 Edge；登录态和 cookie 留在浏览器里。
            browser.release();
        }
        watcher = null;
        browser = null;
        actions = null;
        state = "STOPPED";
    }

    public synchronized void resetForNewRoom(String newRoomUrl) {
        this.roomUrl = newRoomUrl;
        synchronized (recentComments) {
            recentComments.clear();
        }
        commentCount.set(0);
        sentComments.set(0);
        clearPending();
        lastDecision = null;
        lastAction = "";
        lastError = "";
        nextRunAt = null;
        roomEnded = false;
        phase = "WAITING";
        addLog("切换直播间: " + newRoomUrl);
    }

    public void shutdown() {
        close();
        worker.shutdownNow();
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String roomUrl() {
        return roomUrl;
    }

    public String cdpUrl() {
        return cdpUrl;
    }

    public String profileDir() {
        return profileDir;
    }

    public ScheduledExecutorService worker() {
        return worker;
    }

    public Object lifecycleLock() {
        return lifecycleLock;
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public ScheduledFuture<?> loopFuture() {
        return loopFuture;
    }

    public void setLoopFuture(ScheduledFuture<?> loopFuture) {
        this.loopFuture = loopFuture;
    }

    public BrowserSession browser() {
        return browser;
    }

    public void setBrowser(BrowserSession browser) {
        this.browser = browser;
    }

    public CommentWatcher watcher() {
        return watcher;
    }

    public void setWatcher(CommentWatcher watcher) {
        this.watcher = watcher;
    }

    public LiveActions actions() {
        return actions;
    }

    public void setActions(LiveActions actions) {
        this.actions = actions;
    }

    public ActionPolicy policy() {
        return policy;
    }

    public void setPolicy(ActionPolicy policy) {
        this.policy = policy;
    }

    public String state() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String phase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public boolean loggedIn() {
        return loggedIn;
    }

    public void setLoggedIn(boolean loggedIn) {
        this.loggedIn = loggedIn;
    }

    public String browserTitle() {
        return browserTitle;
    }

    public void setBrowserTitle(String browserTitle) {
        this.browserTitle = browserTitle;
    }

    public String currentUrl() {
        return currentUrl;
    }

    public void setCurrentUrl(String currentUrl) {
        this.currentUrl = currentUrl;
    }

    public CommentPlan lastDecision() {
        return lastDecision;
    }

    public void setLastDecision(CommentPlan lastDecision) {
        this.lastDecision = lastDecision;
    }

    public CommentPlan pendingDecision() {
        return pendingDecision;
    }

    public void setPendingDecision(CommentPlan pendingDecision) {
        this.pendingDecision = pendingDecision;
    }

    public Instant pendingCommentAt() {
        return pendingCommentAt;
    }

    public void setPendingCommentAt(Instant pendingCommentAt) {
        this.pendingCommentAt = pendingCommentAt;
    }

    public Integer pendingIntervalSeconds() {
        return pendingIntervalSeconds;
    }

    public void setPendingIntervalSeconds(Integer pendingIntervalSeconds) {
        this.pendingIntervalSeconds = pendingIntervalSeconds;
    }

    public void clearPending() {
        pendingDecision = null;
        pendingCommentAt = null;
        pendingIntervalSeconds = null;
    }

    public String lastAction() {
        return lastAction;
    }

    public boolean roomEnded() {
        return roomEnded;
    }

    public void setRoomEnded(boolean roomEnded) {
        this.roomEnded = roomEnded;
    }

    public void setLastAction(String lastAction) {
        this.lastAction = lastAction;
    }

    public String lastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant nextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }
}
