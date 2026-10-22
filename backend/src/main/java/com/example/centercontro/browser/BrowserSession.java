package com.example.centercontro.browser;

import com.example.centercontro.config.AppProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

public class BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    private final String id;
    private final String cdpUrl;
    private final String profileDir;
    private final AppProperties properties;
    private final ExecutorService executor;

    private volatile Playwright playwright;
    private volatile Browser browser;
    private volatile BrowserContext context;
    private volatile Page page;
    private volatile Process launchedProcess;
    private volatile boolean ownsBrowser;
    private volatile String state = "NEW";
    private volatile String lastError = "";
    private volatile String profileDescription = "";

    public BrowserSession(String id, String cdpUrl, String profileDir, AppProperties properties) {
        this.id = id;
        this.cdpUrl = cdpUrl;
        this.profileDir = profileDir;
        this.properties = properties;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "browser-" + id);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start(String roomUrl) {
        try {
            submit(() -> {
                state = "STARTING";
                Map<String, String> driverEnv = new HashMap<>(System.getenv());
                driverEnv.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
                playwright = Playwright.create(new Playwright.CreateOptions().setEnv(driverEnv));
                if (!"cdp".equalsIgnoreCase(properties.getBrowserMode())) {
                    throw new IllegalStateException("仅支持 CDP 浏览器桥接模式（centercontrol.browser-mode=cdp）");
                }
                connectCdp(roomUrl);
                state = "RUNNING";
                return null;
            }).get(150, TimeUnit.SECONDS);
        } catch (Exception e) {
            lastError = rootMessage(e);
            state = "ERROR";
            log.warn("browser session {} start failed: {}", id, lastError);
            closeQuietlyOnExecutor();
            throw new IllegalStateException("浏览器启动失败: " + lastError, e);
        }
    }

    private void connectCdp(String roomUrl) {
        try {
            browser = playwright.chromium().connectOverCDP(cdpUrl);
        } catch (PlaywrightException first) {
            if (!properties.isLaunchIfMissing()) {
                throw first;
            }
            launchEdgeWithDebugging();
            browser = playwright.chromium().connectOverCDP(cdpUrl);
        }
        context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
        // 刚做过登录同步时，自动化配置里会带上一份日常 Edge 的完整 Cookie。
        // 只保留抖音相关域名，其余站点（银行、邮箱等）的 Cookie 不进自动化浏览器。
        LoginSync.pruneNonDouyinCookies(context, properties.getDataDir());
        // 复用已经开着的抖音页面，避免每次启动都堆一个新标签页；
        // 页面里的登录态和登录后刷新出来的 cookie 都留在同一个浏览器里。
        page = context.pages().stream()
                .filter(candidate -> !candidate.isClosed())
                .filter(candidate -> candidate.url().contains("douyin.com"))
                .findFirst()
                .orElseGet(context::newPage);
        configurePage();
        navigatePage(page, roomUrl);
        ownsBrowser = false;
    }

    private void launchEdgeWithDebugging() {
        if (isCdpAlive()) {
            return;
        }
        BrowserProfileResolver.ResolvedProfile profile =
                BrowserProfileResolver.resolve(properties.getDataDir());
        profileDescription = profile.description();
        // 只处理本程序自己的持久配置，不能结束用户日常 Edge。
        BrowserProfileResolver.closeManagedProcesses(profile);
        // 日常 Edge 已关闭时，把它的抖音登录态接力到自动化配置；正在运行时只记录、不碰它的文件。
        LoginSync.prepareBeforeLaunch(properties.getDataDir());
        launchedProcess = BrowserProfileResolver.launch(profile, cdpUrl, "about:blank", true);
        BrowserProfileResolver.waitForCdp(cdpUrl, 40_000L);
        log.info("自动化浏览器已就绪，复用持久配置: {}", profile.userDataDir());
    }

    private void configurePage() {
        page.setDefaultTimeout(15_000);
        page.setDefaultNavigationTimeout(60_000);
        page.onDialog(dialog -> {
            log.info("browser {} dialog: {}", id, dialog.message());
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        });
    }

    private void navigatePage(Page target, String roomUrl) {
        try {
            target.navigate(roomUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60_000));
        } catch (PlaywrightException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (!message.contains("ERR_ABORTED")) {
                throw e;
            }
            // 抖音搜索页会重定向到 live.douyin.com，Chromium 可能报 ERR_ABORTED；
            // 页面通常已经在跳转，等待稳定后继续。
            log.info("browser {} 导航被重定向，等待页面稳定", id);
            try {
                target.waitForTimeout(1_500);
            } catch (Exception ignored) {
            }
        }
        try {
            target.waitForSelector("#chatroom", new Page.WaitForSelectorOptions().setTimeout(30_000));
        } catch (PlaywrightException e) {
            String currentUrl = target.url();
            log.warn("browser {} 未找到 #chatroom，当前 URL={}", id, currentUrl);
            throw new IllegalStateException(
                    "直播间未加载（当前页面: " + currentUrl + "）。请确认直播中的直链，"
                            + "不要停留在抖音精选/关注页",
                    e
            );
        }
        target.bringToFront();
    }

    public void navigateToRoom(String roomUrl) {
        runVoid(p -> navigatePage(p, roomUrl));
    }

    public boolean isLoggedIn() {
        try {
            return run(this::isLoggedInInternal);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLoggedInInternal(Page p) {
        if (p == null || p.isClosed()) {
            return false;
        }
        try {
            Object result = p.evaluate("""
                    () => {
                      const editor = document.querySelector(
                        "#chatroom div[contenteditable='true'],"
                        + " div.webcast-chatroom___input-container div[contenteditable='true']"
                      );
                      const text = document.body ? (document.body.innerText || '') : '';
                      const loginVisible = [...document.querySelectorAll('button,a,div,span')]
                        .some(el => (el.innerText || '').trim() === '登录' && el.offsetParent !== null);
                      return {
                        editor: !!editor,
                        explicitLoggedOut: text.includes('未登录') || text.includes('需先登录'),
                        loginVisible
                      };
                    }
                    """);
            if (result instanceof Map<?, ?> state) {
                if (Boolean.TRUE.equals(state.get("editor"))) {
                    return true;
                }
                if (Boolean.TRUE.equals(state.get("explicitLoggedOut"))
                        || Boolean.TRUE.equals(state.get("loginVisible"))) {
                    return false;
                }
            }
            // 直播间下播后评论框会消失，但账号仍可能处于登录状态。
            // 此时用 HttpOnly 的 sessionid 等 Cookie 判断，避免误报“未登录”并反复弹登录。
            if (context != null) {
                double now = System.currentTimeMillis() / 1000.0;
                return context.cookies("https://www.douyin.com").stream()
                        .filter(cookie -> cookie.expires == null
                                || cookie.expires <= 0
                                || cookie.expires > now)
                        .map(cookie -> cookie.name)
                        .anyMatch(name -> name.equals("sessionid")
                                || name.equals("sessionid_ss")
                                || name.equals("sid_guard"));
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 启动阶段页面还在渲染，登录状态会抖；重试几次再下结论。 */
    public boolean isLoggedInStable() {
        for (int i = 0; i < 5; i++) {
            if (isLoggedIn()) {
                return true;
            }
            sleep(1_200);
        }
        return isLoggedIn();
    }

    /** 直播间是否已结束（下播）。已结束的房间里评论区是空的，任何动作都必然失败。 */
    public boolean isRoomEnded() {
        try {
            return run(p -> {
                Object ended = p.evaluate("""
                        () => {
                          const text = document.body.innerText || '';
                          if (text.indexOf('直播已结束') < 0) return false;
                          // 页面上"猜你喜欢"里可能也有已结束的推荐位，所以再确认评论区是空的
                          const items = document.querySelectorAll('.webcast-chatroom___item').length;
                          const editor = document.querySelector("#chatroom div[contenteditable='true']");
                          return items === 0 && !editor;
                        }
                        """);
                return Boolean.TRUE.equals(ended);
            });
        } catch (Exception e) {
            return false;
        }
    }

    public String openLogin() {
        try {
            return run(p -> {
                p.bringToFront();
                if (isLoggedInInternal(p)) {
                    return "当前浏览器已登录";
                }
                Locator candidates = p.locator("text=登录");
                int count = candidates.count();
                for (int i = 0; i < count; i++) {
                    Locator candidate = candidates.nth(i);
                    try {
                        if (!candidate.isVisible()) {
                            continue;
                        }
                        String text = candidate.innerText();
                        if (text == null || !text.trim().equals("登录")) {
                            continue;
                        }
                        candidate.click(new Locator.ClickOptions().setTimeout(5_000));
                        p.waitForTimeout(1_500);
                        return "已打开登录窗口，请在弹出的自动化浏览器窗口扫码（不是当前控制台窗口）";
                    } catch (Exception ignored) {
                    }
                }
                try {
                    Object clicked = p.evaluate("""
                            () => {
                              const nodes = [...document.querySelectorAll('button,div,span,a')];
                              const target = nodes.find(el =>
                                (el.innerText || '').trim() === '登录' && el.offsetParent !== null);
                              if (target) { target.click(); return true; }
                              return false;
                            }
                            """);
                    if (Boolean.TRUE.equals(clicked)) {
                        p.waitForTimeout(1_500);
                        return "已打开登录窗口，请在弹出的自动化浏览器窗口扫码（不是当前控制台窗口）";
                    }
                } catch (Exception ignored) {
                }
                return "当前页面没有可点击的登录入口，请在浏览器中手动点击登录";
            });
        } catch (Exception e) {
            return "打开登录窗口失败: " + rootMessage(e);
        }
    }

    public String title() {
        try {
            return run(Page::title);
        } catch (Exception e) {
            return "";
        }
    }

    public String currentUrl() {
        try {
            return run(Page::url);
        } catch (Exception e) {
            return "";
        }
    }

    public <T> T run(Function<Page, T> action) {
        if (page == null) {
            throw new IllegalStateException("浏览器尚未启动");
        }
        try {
            return submit(() -> action.apply(page)).get(70, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e), e);
        }
    }

    public void runVoid(Consumer<Page> action) {
        run(page -> {
            action.accept(page);
            return null;
        });
    }

    private <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    public boolean isAlive() {
        try {
            Page currentPage = page;
            Browser currentBrowser = browser;
            return currentPage != null && !currentPage.isClosed()
                    && currentBrowser != null && currentBrowser.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 只断开 Playwright，不关闭自动化 Edge。
     *
     * <p>停会话时保留浏览器进程和登录态，下次启动直接通过 9222 接回去，
     * 避免每次都新开一个配置目录、看起来像“又要重新登录”。
     */
    public void release() {
        try {
            submit(() -> {
                disconnectBrowser();
                return null;
            }).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            lastError = rootMessage(e);
            log.warn("browser session {} release failed: {}", id, lastError);
        } finally {
            executor.shutdownNow();
        }
    }

    private void disconnectBrowser() {
        state = "RELEASING";
        if (ownsBrowser) {
            if (context != null) {
                try {
                    context.close();
                } catch (Exception ignored) {
                }
            }
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception ignored) {
            }
        }
        playwright = null;
        browser = null;
        context = null;
        page = null;
        ownsBrowser = false;
        state = "RELEASED";
    }

    public void close() {
        try {
            submit(() -> {
                state = "STOPPING";
                disconnectBrowser();
                if (launchedProcess != null) {
                    // 先让 Edge 正常退出，把 Cookie 落盘；直接强制结束会丢掉最近的登录态。
                    EdgeEnvironment.closeGracefully(List.of(launchedProcess.toHandle()));
                    launchedProcess = null;
                }
                state = "STOPPED";
                return null;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            lastError = rootMessage(e);
            log.warn("browser session {} close failed: {}", id, lastError);
        } finally {
            executor.shutdownNow();
        }
    }

    private void closeQuietlyOnExecutor() {
        try {
            disconnectBrowser();
        } catch (Exception ignored) {
        }
    }

    private int cdpPort() {
        try {
            URI uri = URI.create(cdpUrl);
            return uri.getPort() > 0 ? uri.getPort() : 9222;
        } catch (Exception e) {
            return 9222;
        }
    }

    private boolean isCdpAlive() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(cdpUrl + "/json/version"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage().replaceAll("\\s+", " ").trim();
        return message.length() > 260 ? message.substring(0, 260) + "..." : message;
    }

    public String id() {
        return id;
    }

    public String state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    public String cdpUrl() {
        return cdpUrl;
    }

    public String profileDir() {
        return profileDescription.isBlank() ? profileDir : profileDescription;
    }

    public String profileDescription() {
        return profileDescription;
    }
}
