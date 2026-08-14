package com.example.demo.service.lcu;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局数据仓库（单例）
 *
 * 各阶段实时写入数据，对话系统/前端可随时读取。
 * 线程安全。
 *
 * 数据模型：统一 Player（账号层 + 实时层），
 * 选人阶段写我方5人账号层，局内阶段写10人实时层并补查对面账号层。
 */
public class DataHub {

    private static final DataHub INSTANCE = new DataHub();
    public static DataHub get() { return INSTANCE; }

    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String phase = "None";                       // 当前游戏阶段
    private volatile String myChampion = "";                      // 自己英雄
    private volatile String myPuuid = "";
    private volatile String currentGameId = "";                   // 对局会话锚点（同一局恒定）
    private volatile String trackedGameId = "";                   // 已处理过的新局号（防重复清空）
    private final List<Player> players = new ArrayList<>();       // 全部玩家（账号层+实时层）
    private final java.util.Set<String> myTeamNames = new java.util.HashSet<>(); // 选人阶段我方名单（去#tag）
    private final List<String> benchChampions = new ArrayList<>();// 板凳英雄
    private final List<HexOption> hexOptions = new ArrayList<>(); // 海克斯识别结果
    private final Map<String, String> lastHexByCell = new ConcurrentHashMap<>(); // 防止重复识别
    private volatile long updatedAt = 0;

    private DataHub() {}

    // ===== 阶段 =====
    public void setPhase(String phase) { this.phase = phase; }
    public String getPhase() { return phase; }

    // ===== 对局锚点 =====
    public void setCurrentGameId(String id) { this.currentGameId = id == null ? "" : id; }
    public String getCurrentGameId() { return currentGameId; }
    public void setTrackedGameId(String id) { this.trackedGameId = id == null ? "" : id; }
    public String getTrackedGameId() { return trackedGameId; }

    /** 新一局开始：清空上一局全部数据（players/板凳/海克斯/自己/锚点） */
    public synchronized void clearMatch() {
        players.clear();
        myTeamNames.clear();
        benchChampions.clear();
        hexOptions.clear();
        lastHexByCell.clear();
        myChampion = "";
        myPuuid = "";
        updatedAt = System.currentTimeMillis();
    }

    // ===== 自己 =====
    public void setMyInfo(String puuid, String champion) { this.myPuuid = puuid; this.myChampion = champion; }
    public String getMyChampion() { return myChampion; }
    public String getMyPuuid() { return myPuuid; }

    // ===== 玩家（账号层：选人阶段写入） =====
    public synchronized void setPlayers(List<Player> list) {
        players.clear();
        myTeamNames.clear();
        for (Player p : list) {
            if ("我方".equals(p.team) && p.name != null) {
                String k = stripTag(p.name);
                if (k != null && !k.isEmpty()) myTeamNames.add(k);
            }
        }
        players.addAll(list);
        updatedAt = System.currentTimeMillis();
    }
    public synchronized List<Player> getPlayers() { return new ArrayList<>(players); }

    /** 局内 gameflow 解析出的我方名单，合并进 myTeamNames（中途进入时兜底分敌我） */
    public synchronized void addMyTeamNames(java.util.Collection<String> names) {
        for (String n : names) {
            String k = stripTag(n);
            if (k != null && !k.isEmpty()) myTeamNames.add(k);
        }
        updatedAt = System.currentTimeMillis();
    }
    public synchronized java.util.Set<String> getMyTeamNames() { return new java.util.HashSet<>(myTeamNames); }

    /** 按puuid更新玩家账号层（换英雄/熟练度/风格），无则新增 */
    public synchronized void upsertPlayer(Player p) {
        for (Player existing : players) {
            if (p.puuid != null && p.puuid.equals(existing.puuid)) {
                existing.merge(p);
                updatedAt = System.currentTimeMillis();
                return;
            }
        }
        players.add(p);
        updatedAt = System.currentTimeMillis();
    }

    /** 按名字（局内 summonerName，去掉#tag）匹配已有玩家更新实时层；无则新增 */
    public synchronized void upsertBySummonerName(Player live) {
        String key = stripTag(live.name);
        for (Player existing : players) {
            if (key != null && key.equals(stripTag(existing.name))) {
                existing.merge(live);
                // 保持既有 team（我方账号层来自选人阶段，不能被 2999 的 ORDER/CHAOS 覆盖）
                updatedAt = System.currentTimeMillis();
                return;
            }
        }
        // 新玩家：保留 fetchPlayers 算好的 team（ORDER/CHAOS 分敌我，中途进入也正确）
        players.add(live);
        updatedAt = System.currentTimeMillis();
    }

    /** 判断名字（可带#tag）是否为我方（选人阶段录入的 myTeam 名单，不查 players 避免误判） */
    public synchronized boolean isKnownPlayer(String name) {
        String key = stripTag(name);
        if (key == null) return false;
        return myTeamNames.contains(key);
    }

    /** 账号层补查结果合并：只填 puuid/mastery/games/winRate/kda/style，不覆盖实时层与 team */
    public synchronized void upsertEnemyAccount(Player acct) {
        String key = stripTag(acct.name);
        for (Player existing : players) {
            if (key != null && key.equals(stripTag(existing.name))) {
                if (acct.puuid != null && !acct.puuid.isEmpty()) existing.puuid = acct.puuid;
                if (acct.mastery != null) existing.mastery = acct.mastery;
                if (acct.games > 0) existing.games = acct.games;
                if (acct.winRate > 0) existing.winRate = acct.winRate;
                if (acct.kda != null) existing.kda = acct.kda;
                if (acct.style != null) existing.style = acct.style;
                // 保留既有 team（我方/对面各自保持，避免补查覆盖）
                updatedAt = System.currentTimeMillis();
                return;
            }
        }
        // 新玩家：用 acct 自带 team（fetchPlayers 已按 ORDER/CHAOS 算好）
        players.add(acct);
        updatedAt = System.currentTimeMillis();
    }

    /** 去掉召唤师名的 #tag 后缀（TonNi#59093 → TonNi） */
    private static String stripTag(String name) {
        if (name == null) return null;
        int i = name.indexOf('#');
        return i >= 0 ? name.substring(0, i).trim() : name.trim();
    }

    // ===== 板凳 =====
    public synchronized void setBench(List<String> bench) {
        benchChampions.clear();
        benchChampions.addAll(bench);
        updatedAt = System.currentTimeMillis();
    }
    public synchronized List<String> getBench() { return new ArrayList<>(benchChampions); }

    // ===== 海克斯 =====
    public synchronized void setHexOptions(List<HexOption> list) {
        hexOptions.clear();
        hexOptions.addAll(list);
        updatedAt = System.currentTimeMillis();
    }
    public synchronized List<HexOption> getHexOptions() { return new ArrayList<>(hexOptions); }

    /** 防止同一cell重复识别海克斯 */
    public boolean isHexNew(String cell, String hex) {
        String prev = lastHexByCell.get(cell);
        if (hex != null && !hex.equals(prev)) {
            lastHexByCell.put(cell, hex);
            return true;
        }
        return false;
    }

    public long getUpdatedAt() { return updatedAt; }

    /** 全量快照 JSON（写 Redis 用） */
    public String snapshotJson() {
        try {
            return mapper.writeValueAsString(new Snapshot(
                    phase, myChampion, myPuuid, currentGameId,
                    new ArrayList<>(players),
                    new ArrayList<>(benchChampions),
                    new ArrayList<>(hexOptions),
                    updatedAt));
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public static class Snapshot {
        public String phase;
        public String myChampion;
        public String myPuuid;
        public String currentGameId;
        public List<Player> players;
        public List<String> bench;
        public List<HexOption> hexOptions;
        public long updatedAt;

        public Snapshot() {}

        public Snapshot(String phase, String myChampion, String myPuuid, String currentGameId,
                        List<Player> players, List<String> bench,
                        List<HexOption> hexOptions, long updatedAt) {
            this.phase = phase;
            this.myChampion = myChampion;
            this.myPuuid = myPuuid;
            this.currentGameId = currentGameId;
            this.players = players;
            this.bench = bench;
            this.hexOptions = hexOptions;
            this.updatedAt = updatedAt;
        }
    }

    /** 统一玩家模型：账号层（选人/局内补查）+ 实时层（局内） */
    public static class Player {
        // 身份
        public String puuid;
        public String name;        // 召唤师名（局内 summonerName）
        public String team;        // 我方/对面
        // 账号层（选人阶段队友 / 局内补查对面）
        public String champion;    // 当前英雄
        public String mastery;     // 熟练度描述
        public int games;          // 分析场次
        public double winRate;     // 胜率%
        public String kda;         // KDA摘要
        public String style;       // 风格判断
        // 实时层（局内 2999 填充）
        public int level;
        public int kills, deaths, assists;
        public String items;       // 装备摘要

        /** 用另一个 Player 的实时层/账号层合并覆盖本对象 */
        public void merge(Player other) {
            if (other.puuid != null && !other.puuid.isEmpty()) puuid = other.puuid;
            if (other.champion != null) champion = other.champion;
            if (other.mastery != null) mastery = other.mastery;
            if (other.games > 0) games = other.games;
            if (other.winRate > 0) winRate = other.winRate;
            if (other.kda != null) kda = other.kda;
            if (other.style != null) style = other.style;
            level = other.level;
            kills = other.kills;
            deaths = other.deaths;
            assists = other.assists;
            if (other.items != null) items = other.items;
        }
    }

    /** 海克斯选项 */
    public static class HexOption {
        public String name;        // 海克斯名
        public int slot;           // 槽位1-3
    }
}
