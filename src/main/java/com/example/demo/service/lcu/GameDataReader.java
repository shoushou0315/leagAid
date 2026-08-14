package com.example.demo.service.lcu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.*;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

/**
 * 局内数据读取（2999 Live Client Data API，免token）
 *
 * 读取对局中全部10名玩家的：英雄、装备、符文、KDA、等级、生死状态
 * 仅在游戏进行中（InProgress）可用，进入对局后调用。
 */
public class GameDataReader {

    private static final String BASE = "https://127.0.0.1:2999";
    private static volatile String myRiotId = "";  // gameName#tagLine，定位自己在 playerlist 里的 team
    private static volatile String myPuuid = "";   // 自己的 puuid，中途进入时补 myChampion
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> champByAlias = new HashMap<>(); // alias小写 → 中文名

    /** 注入自己的 riotId + puuid（AutoWatcher 启动时调用），用于局内按 ORDER/CHAOS 分敌我 + 补自己信息 */
    public static void setMyIdentity(String riotId, String puuid) {
        myRiotId = riotId == null ? "" : riotId;
        myPuuid = puuid == null ? "" : puuid;
    }

    public GameDataReader() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            client = HttpClient.newBuilder().sslContext(ctx).build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 加载英雄alias→中文名（从ddragon，无需LCU） */
    public void loadChampNames() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://ddragon.leagueoflegends.com/cdn/14.24.1/data/zh_CN/champion.json"))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode data = mapper.readTree(resp.body()).get("data");
            data.fieldNames().forEachRemaining(alias ->
                    champByAlias.put(alias.toLowerCase(), data.get(alias).path("name").asText(alias)));
        } catch (Exception ignored) {}
    }

    /** 从rawChampionName提取中文名（形如 game_character_displayname_Rumble） */
    public String champName(JsonNode p) {
        String raw = p.path("rawChampionName").asText("");
        int i = raw.lastIndexOf('_');
        String alias = (i > 0 ? raw.substring(i + 1) : raw).toLowerCase();
        return champByAlias.getOrDefault(alias, p.path("championName").asText(alias));
    }

    /** 获取全部玩家列表JSON，局内不可用时返回null */
    public JsonNode getPlayerList() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/liveclientdata/playerlist"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? mapper.readTree(resp.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取全部10名玩家的局内数据，写入DataHub并返回 */
    public java.util.List<DataHub.Player> fetchPlayers() {
        java.util.List<DataHub.Player> players = new java.util.ArrayList<>();
        JsonNode list = getPlayerList();
        if (list == null) {
            System.out.println("  [局内] 2999不可用（未在对局中）");
            return players;
        }
        for (JsonNode p : list) {
            DataHub.Player pl = new DataHub.Player();
            pl.name = p.path("summonerName").asText("");
            pl.champion = champName(p);
            pl.level = p.path("level").asInt();
            JsonNode scores = p.path("scores");
            pl.kills = scores.path("kills").asInt();
            pl.deaths = scores.path("deaths").asInt();
            pl.assists = scores.path("assists").asInt();
            pl.items = itemsBrief(p);
            players.add(pl);
        }
        // team 判定：在 playerlist 里找到自己（riotId），自己所在的 ORDER/CHAOS 即我方，另一队为对面
        String myTeamSide = null;
        if (myRiotId != null && !myRiotId.isEmpty()) {
            for (JsonNode p : list) {
                String sn = p.path("summonerName").asText("");
                if (sn.equals(myRiotId) || sn.endsWith("#" + myRiotId) || myRiotId.endsWith(sn)) {
                    myTeamSide = p.path("team").asText("");
                    // 中途进入时补自己的英雄/账号信息
                    if (myPuuid != null && !myPuuid.isEmpty()) {
                        DataHub.get().setMyInfo(myPuuid, champName(p));
                    }
                    break;
                }
            }
        }
        if (myTeamSide == null) {
            System.out.println("  [局内] 未定位到自己 team（myRiotId=" + myRiotId + "）");
        }
        for (int i = 0; i < players.size(); i++) {
            String side = list.get(i).path("team").asText("");
            if (myTeamSide != null && myTeamSide.equals(side)) {
                players.get(i).team = "我方";
            } else if (myTeamSide == null) {
                // 兜底：选人阶段我方名单
                players.get(i).team = DataHub.get().isKnownPlayer(players.get(i).name) ? "我方" : "对面";
            } else {
                players.get(i).team = "对面";
            }
        }
        // 按召唤师名 upsert：覆盖实时层，保留已有账号层（我方来自选人阶段）
        for (DataHub.Player pl : players) {
            DataHub.get().upsertBySummonerName(pl);
        }
        return players;
    }

    /** 局内数据：写入 DataHub（静默，不打印，避免刷屏） */
    public void printAllPlayers() {
        fetchPlayers();
    }

    /** 装备简要（前4件非药水装备） */
    private String itemsBrief(JsonNode p) {
        JsonNode items = p.path("items");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        if (items != null && items.isArray()) {
            for (JsonNode it : items) {
                String name = it.path("displayName").asText("");
                if (name.isEmpty() || name.contains("药水") || name.contains("饰品")) continue;
                if (count >= 4) break;
                sb.append(name).append(" ");
                count++;
            }
        }
        return sb.toString().trim();
    }

    /** 测试入口 */
    public static void main(String[] args) {
        GameDataReader reader = new GameDataReader();
        reader.loadChampNames();
        reader.printAllPlayers();
    }
}
