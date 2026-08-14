package com.example.demo.service.lcu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 海克斯大乱斗助手
 *
 * 选人阶段：队友风格 / 熟练度 / 板凳英雄
 * 局内：2999读取10人数据
 * 局内快捷键：F6截取海克斯区域（保存图片，供OCR用）
 *
 * 需管理员权限运行。
 */
public class AutoWatcher {

    private static LcuClient lcu;
    private static TeammateAnalyzer ta;
    private static ObjectMapper mapper = new ObjectMapper();
    private static volatile boolean watching = false;
    private static final Map<String, Integer> lastChampionByPuuid = new HashMap<>();
    private static volatile boolean gameActive = false;
    private static volatile String myPuuid = "";
    private static volatile String mySummonerId = "";
    private static volatile String myRiotId = "";   // gameName#tagLine，用于在2999里定位自己
    private static volatile boolean champSelectHandled = false;
    private static volatile boolean gameHandled = false;

    public static void main(String[] args) throws InterruptedException {
        connectAndStart();
        // main 模式保留阻塞（独立调试用）
        for (;;) {
            try { Thread.sleep(60_000); } catch (InterruptedException e) { break; }
        }
    }

    /** 启动采集（Spring 调用，不阻塞） */
    public static boolean connectAndStart() {
        System.out.println("========== 海克斯大乱斗助手（采集） ==========");
        lcu = new LcuClient();
        if (!lcu.connect()) {
            return false;
        }
        ta = new TeammateAnalyzer(lcu);
        ta.loadMaps();

        // 记录自己的 puuid + summonerId + riotId（比 isLocalPlayer 字段可靠）。
        // LCU 刚连上接口可能未就绪，返回空/异常都算失败，一直重试直到拿到有效 puuid（后台线程）
        String me = null;
        while (me == null) {
            me = lcu.currentSummoner();
            if (me == null || me.startsWith("__ERROR__")) {
                me = null;
                try { Thread.sleep(1000); } catch (InterruptedException e) { return false; }
                continue;
            }
            try {
                JsonNode node = mapper.readTree(me);
                if (node.isArray() && node.size() > 0) node = node.get(0);
                // 未加载完：puuid 为空视为失败，继续重试
                if (node.path("puuid").asText("").isEmpty()) {
                    me = null;
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return false; }
                    continue;
                }
            } catch (Exception e) {
                me = null;
                try { Thread.sleep(1000); } catch (InterruptedException e2) { return false; }
            }
        }
        try {
            JsonNode meNode = mapper.readTree(me);
            if (meNode.isArray() && meNode.size() > 0) meNode = meNode.get(0);
            myPuuid = meNode.path("puuid").asText("");
            mySummonerId = String.valueOf(meNode.path("summonerId").asLong(0));
            String gn = meNode.path("gameName").asText("");
            String tl = meNode.path("tagLine").asText("");
            myRiotId = gn + (tl.isEmpty() ? "" : "#" + tl);
        } catch (Exception ignored) {}
        System.out.println("[OK] 自己 riotId=" + myRiotId + " puuid=" + myPuuid);

        // 启动 DataHub → Redis 快照同步（AI 助手实时读取）
        DataHubRedisSync.start("127.0.0.1", 6379);

        // 启动语音全局热键（全屏游戏也能按 F6 说话）
        VoiceHotkeyService.startStatic();

        new GamePhaseWatcher(lcu, AutoWatcher::onPhaseChange).start();
        System.out.println("[OK] 采集已启动");
        return true;
    }

    private static void onPhaseChange(String oldPhase, String newPhase) {
        // 新一局判定：进入局内（InProgress/GameStart）时，以最终 gameId 变化为准
        // （选人阶段 gameId 是占位不可靠，不用于判断）
        boolean inGame = "InProgress".equals(newPhase) || "GameStart".equals(newPhase);
        if (inGame) {
            DataHub hub = DataHub.get();
            String newGameId = hub.getCurrentGameId();
            String prevGameId = hub.getTrackedGameId();
            if (!newGameId.isEmpty() && !newGameId.equals(prevGameId)) {
                hub.clearMatch();
                hub.setTrackedGameId(newGameId);
                System.out.println("[对局] 新一局开始 gameId=" + newGameId + "，旧数据已清空");
            }
        }
        DataHub.get().setPhase(newPhase);
        switch (newPhase) {
            case "ChampSelect" -> {
                if (!champSelectHandled) {
                    champSelectHandled = true;
                    new Thread(AutoWatcher::handleChampSelect, "handle-champselect").start();
                }
            }
            case "InProgress", "GameStart" -> {
                watching = false;
                gameActive = true;
                if (!gameHandled) {
                    gameHandled = true;
                    Thread gameThread = new Thread(AutoWatcher::handleGame, "handle-game");
                    gameThread.setDaemon(true);
                    gameThread.start();
                }
            }
            default -> {
                watching = false;
                gameActive = false;
                // 回到大厅/结算，重置本局标志，下一局可重新触发
                champSelectHandled = false;
                gameHandled = false;
            }
        }
    }

    /** 局内：2999读取10人数据 + 对面账号层补查（补查独立线程，不阻塞实时刷新） */
    private static void handleGame() {
        System.out.println("\n========== 对局开始 ==========");
        GameDataReader.setMyIdentity(myRiotId, myPuuid);  // 供局内按 ORDER/CHAOS 分敌我 + 补自己信息
        GameDataReader reader = new GameDataReader();
        reader.loadChampNames();
        // 账号层补查放独立线程（LCU 战绩查询可能几秒，不能卡实时刷新）：
        // 中途进入局内时，我方/对面都没有账号层（熟练度/胜率/风格），统一补查
        Thread enemyThread = new Thread(() -> {
            // 等 players 非空（主循环首次刷新后）再开始补查
            boolean hasPlayer = false;
            while (gameActive && !hasPlayer) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                hasPlayer = !DataHub.get().getPlayers().isEmpty();
            }
            // 持续补查直到对局结束（单个玩家失败超5次后静默跳过，不阻塞整体）
            while (gameActive) {
                analyzeAccounts();
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
        }, "enemy-analyze");
        enemyThread.setDaemon(true);
        enemyThread.start();
        // 局内主循环：只管实时层刷新
        while (gameActive) {
            try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            try {
                reader.printAllPlayers();
            } catch (Exception e) {
                // 单次刷新失败不影响循环，防止异常杀死线程导致 JVM 退出
                System.out.println("[局内] 刷新失败: " + e.getMessage());
            }
        }
    }

    /** 每个对面玩家已补查的尝试次数（补成功或超5次即不再尝试） */
    private static final Map<String, Integer> enemyRetryCount = new HashMap<>();
    private static final int MAX_ENEMY_RETRY = 5;

    /** 账号层补查：单轮并行尝试所有账号层缺失的玩家（我方/对面都补），失败的下轮再试 */
    private static void analyzeAccounts() {
        try {
            List<DataHub.Player> all = DataHub.get().getPlayers();
            List<DataHub.Player> pending = new ArrayList<>();
            for (DataHub.Player pl : all) {
                if (pl.name == null || pl.name.isEmpty()) continue;
                if (pl.games > 0) continue; // 已补查过账号层
                int retry = enemyRetryCount.merge(pl.name, 1, Integer::sum);
                if (retry > MAX_ENEMY_RETRY) continue; // 放弃这个玩家
                pending.add(pl);
            }
            if (pending.isEmpty()) return;
            // 并行补查（最多5个同时，与队友分析一致）
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(pending.size());
            for (DataHub.Player pl : pending) {
                new Thread(() -> {
                    try {
                        analyzeOne(pl);
                    } finally {
                        latch.countDown();
                    }
                }, "acct-" + pl.name).start();
            }
            latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("[账号补查] 失败: " + e.getMessage());
        }
    }

    /** 单个玩家账号层补查 */
    private static void analyzeOne(DataHub.Player pl) {
        try {
            String puuid = findPuuidBySummonerName(pl.name);
            if (puuid == null) return; // 下次重试
            // 战绩统计（胜率/KDA/风格，静默模式避免补查轮询刷屏）
            DataHub.Player acct = ta.analyzeSilent(puuid, pl.name, pl.champion);
            if (acct == null || acct.games == 0) return;
            acct.team = pl.team;  // 保持原 team（我方/对面）
            acct.champion = pl.champion;
            acct.name = pl.name;
            acct.puuid = puuid;
            // 熟练度补查：用中文英雄名反查 championId
            int cid = ta.championIdByName(pl.champion);
            if (cid > 0) {
                String m = ta.championMasteryFor(puuid, cid);
                if (m != null) acct.mastery = m;
            }
            // 只补账号层，实时层（level/items）留给 2999 刷新，避免旧值覆盖
            DataHub.get().upsertEnemyAccount(acct);
            System.out.println("[账号补查] " + pl.team + " " + pl.name + " 补查完成: " + pl.champion);
        } catch (Exception e) {
            System.out.println("[账号补查] " + pl.name + " 异常: " + e.getMessage());
        }
    }

    /** 用 summonerName（可带#tag）反查 puuid（LCU summoners 接口） */
    private static String findPuuidBySummonerName(String name) {
        // 拆 gameName + tagLine（TonNi#59093 → gameName=TonNi, tagLine=59093）
        String gameName = name, tagLine = "";
        int idx = name.indexOf('#');
        if (idx >= 0) {
            gameName = name.substring(0, idx).trim();
            tagLine = name.substring(idx + 1).trim();
        }
        try {
            // 新版接口：按 gameName+tagLine 批量查（POST）
            String body = "[\"" + gameName + "#" + tagLine + "\"]";
            String json = lcu.post("/lol-summoner/v2/summoners/names", body);
            if (json != null && !json.startsWith("__ERROR__")) {
                JsonNode node = mapper.readTree(json);
                if (node.isArray() && node.size() > 0) {
                    return node.get(0).path("puuid").asText("");
                }
                if (!node.isMissingNode()) return node.path("puuid").asText("");
            }
            // 备选：按 gameName 单独查
            String json2 = lcu.get("/lol-summoner/v1/summoners?name=" + gameName);
            if (json2 != null && !json2.startsWith("__ERROR__")) {
                JsonNode node = mapper.readTree(json2);
                if (node.isArray() && node.size() > 0) return node.get(0).path("puuid").asText("");
                return node.path("puuid").asText("");
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static void handleChampSelect() {
        String session = waitSession();
        if (session == null) return;
        if (!session.contains("\"benchEnabled\":true")) {
            System.out.println("  (非大乱斗模式，跳过)");
            return;
        }
        System.out.println("\n========== 大乱斗选人 ==========");

        // 1. 队友作战风格
        analyzeTeammates(session);

        // 2. 轮询：等所有人选完→显示板凳；换英雄→更新板凳
        watching = true;
        new Thread(AutoWatcher::watchChampChanges).start();
    }

    /** 输出每个队友的作战风格（近50把），并写入DataHub。并行分析5个队友，加速选人数据就绪 */
    private static void analyzeTeammates(String session) {
        try {
            JsonNode myTeam = mapper.readTree(session).get("myTeam");
            if (myTeam == null) return;
            DataHub.Player[] results = new DataHub.Player[myTeam.size()];
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(myTeam.size());
            // 限流：最多3个队友同时查LCU战绩，避免并发打爆match-history接口（之前遇到过返回不全）
            java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(3);
            int idx = 0;
            for (JsonNode p : myTeam) {
                String puuid = p.path("puuid").asText("");
                String name = p.path("gameName").asText("?");
                int championId = p.path("championId").asInt();
                String champ = championId > 0 ? ta.champName(championId) : "";
                int i = idx;
                new Thread(() -> {
                    try {
                        if (!puuid.isEmpty()) {
                            semaphore.acquire();
                            try {
                                System.out.println("\n===== 队友" + (i + 1) + " (" + name + ") =====");
                                DataHub.Player tm = ta.analyze(puuid, name, champ);
                                tm.team = "我方";
                                results[i] = tm;
                            } finally {
                                semaphore.release();
                            }
                        }
                        // 初始记为0：首次从0→选定英雄时触发"选英雄"
                        if (!puuid.isEmpty()) lastChampionByPuuid.put(puuid, 0);
                        // 自己信息（用 puuid 对比，isLocalPlayer 字段不可靠）
                        if (puuid.equals(myPuuid)) {
                            DataHub.get().setMyInfo(puuid, champ);
                        }
                    } catch (InterruptedException e) {
                        // 线程中断，忽略
                    } finally {
                        latch.countDown();
                    }
                }, "teammate-" + idx).start();
                idx++;
            }
            // 等全部队友分析完成（最多15秒兜底）
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
            List<DataHub.Player> players = new ArrayList<>();
            for (DataHub.Player r : results) {
                if (r != null) players.add(r);
            }
            DataHub.get().setPlayers(players);
        } catch (Exception e) {
            System.out.println("解析失败: " + e.getMessage());
        }
    }

    /** 轮询：所有队友选完后显示板凳；队友换英雄时显示新英雄熟练度+更新板凳 */
    private static void watchChampChanges() {
        boolean benchShown = false;
        String lastBenchKey = "";
        while (watching) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            String session = lcu.get("/lol-champ-select/v1/session");
            if (session == null || session.startsWith("__ERROR__")) continue;
            try {
                JsonNode root = mapper.readTree(session);
                JsonNode myTeam = root.path("myTeam");
                if (!myTeam.isArray()) continue;

                // 1. 检测队友选/换英雄（championId变化）→ 输出新英雄熟练度
                for (JsonNode p : myTeam) {
                    String puuid = p.path("puuid").asText("");
                    int newChamp = p.path("championId").asInt();
                    if (puuid.isEmpty() || newChamp <= 0) continue;
                    Integer oldChamp = lastChampionByPuuid.get(puuid);
                    if (oldChamp != null && newChamp != oldChamp) {
                        String action = (oldChamp > 0) ? "换英雄" : "选英雄";
                        System.out.println("\n[队友" + action + "] " + ta.champName(newChamp));
                        String m = ta.championMasteryFor(puuid, newChamp);
                        if (m != null) System.out.println("  熟练度: " + m);
                        DataHub.Player p2 = new DataHub.Player();
                        p2.puuid = puuid;
                        p2.name = p.path("gameName").asText("?");
                        p2.team = "我方";
                        p2.champion = ta.champName(newChamp);
                        p2.mastery = m;
                        DataHub.get().upsertPlayer(p2);
                        if (puuid.equals(myPuuid)) {
                            DataHub.get().setMyInfo(puuid, ta.champName(newChamp));
                        }
                    }
                    lastChampionByPuuid.put(puuid, newChamp);
                }

                // 2. 所有人选完 → 显示板凳；换英雄导致板凳变化 → 更新板凳
                boolean allPicked = true;
                for (JsonNode p : myTeam) {
                    if (p.path("championId").asInt() <= 0) { allPicked = false; break; }
                }
                if (allPicked) {
                    String benchKey = root.path("benchChampions").toString();
                    if (!benchShown || !benchKey.equals(lastBenchKey)) {
                        benchShown = true;
                        lastBenchKey = benchKey;
                        printBench(root);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /** 显示板凳英雄，并写入DataHub */
    private static void printBench(JsonNode root) {
        JsonNode bench = root.get("benchChampions");
        System.out.println("\n【板凳英雄(可换)】(" + (bench != null ? bench.size() : 0) + "个)");
        List<String> names = new ArrayList<>();
        if (bench != null && bench.isArray()) {
            bench.forEach(b -> names.add(ta.champName(b.path("championId").asInt())));
            System.out.println("  " + String.join(" ", names));
        }
        DataHub.get().setBench(names);
    }

    /** 等待选人会话就绪 */
    private static String waitSession() {
        for (int i = 0; i < 10; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { return null; }
            String s = lcu.get("/lol-champ-select/v1/session");
            if (s != null && !s.startsWith("__ERROR__")) return s;
        }
        return null;
    }
}
