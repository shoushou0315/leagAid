package com.example.demo.service.lcu;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Spring 启动后拉起 leagAid 采集（后台，不阻塞容器）。
 *
 * 处理边界：项目先启动、LOL 客户端后打开的情况。
 * 每隔 5s 尝试连接 LCU，连上才启动采集；后续掉线也由内部重试。
 */
@Component
public class LeagAidRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        new Thread(() -> {
            System.out.println("[采集] 等待 LOL 客户端...");
            while (true) {
                if (AutoWatcher.connectAndStart()) {
                    return;
                }
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
        }, "leagAid-bootstrap").start();
    }
}
