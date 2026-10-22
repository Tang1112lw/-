package com.example.centercontro.web;

import com.example.centercontro.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
public class UiLauncher {

    private static final Logger log = LoggerFactory.getLogger(UiLauncher.class);

    private final AppProperties properties;
    private final String port;

    public UiLauncher(AppProperties properties, org.springframework.core.env.Environment environment) {
        this.properties = properties;
        this.port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "18080"));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void openUi() {
        if (!properties.isOpenUi()) {
            return;
        }
        String url = "http://127.0.0.1:" + port;
        log.info("控制台地址: {}（由浏览器桥接启动器打开）", url);
    }
}
