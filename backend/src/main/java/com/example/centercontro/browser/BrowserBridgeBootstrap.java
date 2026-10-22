package com.example.centercontro.browser;

import com.example.centercontro.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 在控制台页面打开前启动 CDP 浏览器。
 *
 * <p>控制台直接开在复用原配置的桥接 Edge 中，避免普通 Edge 与桥接 Edge
 * 同时占用同一份 User Data。
 */
@Component
public class BrowserBridgeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(BrowserBridgeBootstrap.class);

    private final AppProperties properties;
    private final String uiUrl;

    public BrowserBridgeBootstrap(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.uiUrl = "http://127.0.0.1:" + environment.getProperty(
                "local.server.port",
                environment.getProperty("server.port", "18080")
        );
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void prepareProfile() {
        if (!properties.isOpenUi()) {
            return;
        }
        try {
            BrowserProfileResolver.ResolvedProfile profile = BrowserProfileResolver.resolve(
                    properties.getDataDir()
            );
            if (!BrowserProfileResolver.isCdpAlive(properties.getCdpUrl())) {
                BrowserProfileResolver.closeManagedProcesses(profile);
                // 只在自动化浏览器还没起来时同步：此时日常 Edge 若已关闭，
                // 就能读到它的 Cookie 数据库，把抖音登录态接力过来。
                LoginSync.SyncResult sync = LoginSync.prepareBeforeLaunch(properties.getDataDir());
                if (!sync.imported()) {
                    log.info("登录同步未执行: {}", sync.message());
                }
                BrowserProfileResolver.launch(profile, properties.getCdpUrl(), uiUrl, true);
                BrowserProfileResolver.waitForCdp(properties.getCdpUrl(), 40_000L);
            } else {
                BrowserProfileResolver.launch(profile, properties.getCdpUrl(), uiUrl, false);
            }
            log.info("浏览器桥接: {}；控制台已在自动化浏览器打开，日常 Edge 未被占用", profile.description());
        } catch (Exception e) {
            // 不阻塞后端启动；用户仍可手动访问控制台地址。
            log.warn("浏览器桥接预检查失败: {}", e.getMessage());
        }
    }
}
