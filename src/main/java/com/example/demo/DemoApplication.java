package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DemoApplication {
    public static void main(String[] args) {
        // 禁用 headless：需要 Robot 截图（海克斯识别）访问屏幕
        System.setProperty("java.awt.headless", "false");
        // 外部配置文件优先：程序同目录下放 config.yml（含 API key/数据库密码）则自动加载并覆盖默认值
        System.setProperty("spring.config.additional-location", "optional:file:./config.yml");
        SpringApplication app = new SpringApplication(DemoApplication.class);
        app.setDefaultProperties(java.util.Map.of(
                "server.port", "8080"
        ));
        // 默认激活 local profile（加载 application-local.yml 真实密钥/密码，该文件不入库）
        app.setAdditionalProfiles("local");
        app.run(args);
    }
}