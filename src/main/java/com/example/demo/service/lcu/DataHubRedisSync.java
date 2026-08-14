package com.example.demo.service.lcu;

/**
 * DataHub 快照 → Redis（供 AI 助手实时读取）
 *
 * 每 1s 把 DataHub 全量快照覆盖写 Redis key "leagaid:state"。
 * 游戏状态是"最新快照型"，覆盖写即可，不累积。
 * 使用零依赖 MiniRedisClient（仅 SET），无需 jedis。
 */
public class DataHubRedisSync {

    private static final String KEY = "leagaid:state";
    private static volatile boolean running = false;

    private DataHubRedisSync() {}

    public static void start(String redisHost, int redisPort) {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            System.out.println("[RedisSync] 启动: " + redisHost + ":" + redisPort + " key=" + KEY);
            while (running) {
                try (MiniRedisClient client = new MiniRedisClient(redisHost, redisPort)) {
                    client.set(KEY, DataHub.get().snapshotJson());
                } catch (Exception e) {
                    System.out.println("[RedisSync] 写入失败: " + e.getMessage());
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "datahub-redis-sync");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        running = false;
    }
}
