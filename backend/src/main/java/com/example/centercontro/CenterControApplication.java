package com.example.centercontro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.centercontro.config.AppProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class CenterControApplication {

    public static void main(String[] args) {
        // 原来这里把端口写死成 18080：改了 server.port 之后，新实例仍然去探测 18080，
        // 一旦那儿有老实例在跑就会误判"已在运行"然后直接退出，看起来像"程序起不来"。
        String uiUrl = "http://127.0.0.1:" + resolvePort(args);
        if (isInstanceRunning(uiUrl)) {
            // 已有实例时桥接 Edge 里已经开着控制台，不能再用系统默认浏览器
            // 打开第二个配置。
            return;
        }
        SpringApplication.run(CenterControApplication.class, args);
    }

    private static String resolvePort(String[] args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null) {
                    continue;
                }
                if (arg.startsWith("--server.port=")) {
                    String value = arg.substring("--server.port=".length()).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
                if ("--server.port".equals(arg) && i + 1 < args.length) {
                    return args[i + 1].trim();
                }
            }
        }
        String system = System.getProperty("server.port");
        if (system != null && !system.isBlank()) {
            return system.trim();
        }
        String env = System.getenv("SERVER_PORT");
        return env != null && !env.isBlank() ? env.trim() : "18080";
    }

    public static String baseUrl(String[] args) {
        return "http://127.0.0.1:" + resolvePort(args);
    }

    private static boolean isInstanceRunning(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/status"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("corpusSize");
        } catch (Exception e) {
            return false;
        }
    }
}
