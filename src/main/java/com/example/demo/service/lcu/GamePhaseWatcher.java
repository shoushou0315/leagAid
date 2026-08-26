package com.example.demo.service.lcu;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 游戏阶段轮询器
 *
 * 通过 /lol-gameflow/v1/gameflow-phase 检测当前所处阶段：
 *   None/Lobby/Matchmaking  → 大厅类，低频轮询（2s，省资源）
 *   ChampSelect             → 选人界面，中频轮询（1s）
 *   GameStart/InProgress    → 游戏中，高频轮询（500ms，尽快抓海克斯弹出）
 *   EndOfGame/WaitingForStats → 结算，中频（1s）
 *
 * 用法：
 *   LcuClient lcu = new LcuClient();
 *   lcu.connect();
 *   new GamePhaseWatcher(lcu, (oldPhase, newPhase) -> {
 *       System.out.println("阶段变化: " + oldPhase + " -> " + newPhase);
 *       // 在这里根据阶段触发不同逻辑
 *   }).start();
 */
public class GamePhaseWatcher {

    private final LcuClient lcu;
    private final PhaseCallback callback;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean running = true;

    /** 阶段变化回调 */
    public interface PhaseCallback {
        void onPhaseChange(String oldPhase, String newPhase);
    }

    public GamePhaseWatcher(LcuClient lcu, PhaseCallback callback) {
        this.lcu = lcu;
        this.callback = callback;
    }

    /** 根据阶段返回合适的轮询间隔(ms) */
    private int intervalFor(String phase) {
        if (phase == null) return 2000;
        return switch (phase) {
            case "GameStart", "InProgress" -> 500;   // 游戏中：高频，抓海克斯弹出
            case "ChampSelect", "EndOfGame", "WaitingForStats" -> 1000; // 选人/结算：中频
            default -> 2000;                          // 大厅等：低频省资源
        };
    }

    public void start() {
        String currentPhase = fetchPhase();
        System.out.println("[轮询] 当前阶段: " + currentPhase);

        // 中途启动：若已处于游戏阶段，立即回调一次（否则只能等下次阶段变化）
        if (currentPhase != null
                && ("ChampSelect".equals(currentPhase)
                    || "InProgress".equals(currentPhase)
                    || "GameStart".equals(currentPhase))) {
            callback.onPhaseChange(null, currentPhase);
        }

        Thread t = new Thread(() -> {
            String last = currentPhase;
            int failCount = 0;
            while (running) {
                try {
                    Thread.sleep(intervalFor(last));
                } catch (InterruptedException e) {
                    break;
                }
                fetchGameId();   // 采集对局锚点 gameId（每轮顺带）
                String next = fetchPhase();
                if (next == null) {
                    // 连接失败：连续 3 次后尝试重连 LCU（端口/token 可能已变）
                if (++failCount >= 3) {
                    System.out.println("[轮询] LCU 连接异常，尝试重连...");
                    if (lcu.reconnect()) {
                        failCount = 0;
                        System.out.println("[轮询] LCU 重连成功");
                    } else {
                        // 重连也失败 → 游戏客户端已下线 → 对局态置 None，避免残留 InProgress 一直写 Redis（前端不再看假对局）
                        DataHub.get().setPhase("None");
                        System.out.println("[轮询] LCU 不可用，对局态置 None（防残留）");
                    }
                }
                    continue;
                }
                failCount = 0;
                if (!next.equals(last)) {
                    System.out.println("[轮询] 阶段变化: " + last + " -> " + next);
                    callback.onPhaseChange(last, next);
                    last = next;
                }
            }
        }, "game-phase-watcher");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
    }

    private String fetchPhase() {
        String raw = lcu.get("/lol-gameflow/v1/gameflow-phase");
        if (raw == null || raw.startsWith("__ERROR__")) return null;
        return raw.replace("\"", "").trim();
    }

    /** 读取当前 gameData.gameId（选人阶段是占位ID，只有局内才是最终对局ID）。返回 null 表示无 */
    public String fetchGameId() {
        String json = lcu.get("/lol-gameflow/v1/session");
        if (json == null || json.startsWith("__ERROR__") || json.startsWith("__EXCEPTION__")) return null;
        try {
            String id = mapper.readTree(json).path("gameData").path("gameId").asText("");
            return id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    /** 测试入口：跑60秒观察阶段变化 */
    public static void main(String[] args) throws InterruptedException {
        LcuClient lcu = new LcuClient();
        if (!lcu.connect()) return;

        GamePhaseWatcher watcher = new GamePhaseWatcher(lcu, (oldP, newP) -> {
            System.out.println(">>> 阶段切换回调触发!");
        });
        watcher.start();

        System.out.println("[轮询] 已启动，观察60秒（此时请正常操作游戏：进选人/开局）...");
        Thread.sleep(60_000);
        watcher.stop();
        System.out.println("[轮询] 测试结束");
    }
}
