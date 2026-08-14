package com.example.demo.service.lcu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 队友分析：输出作战风格（近20把大乱斗）
 *
 * 输出：场次、胜率、场均KDA、角色偏好、风格判断
 */
public class TeammateAnalyzer {

    private final LcuClient lcu;
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<Integer, String> championNames = new HashMap<>();
    private Map<Integer, String[]> championRoles = new HashMap<>();

    public TeammateAnalyzer(LcuClient lcu) {
        this.lcu = lcu;
    }

    /** 加载英雄名 + 角色映射 */
    public void loadMaps() {
        String champs = lcu.get("/lol-game-data/assets/v1/champion-summary.json");
        if (champs != null && !champs.startsWith("__ERROR__")) {
            try {
                mapper.readTree(champs).forEach(c -> {
                    int id = c.get("id").asInt();
                    championNames.put(id, c.get("name").asText());
                    JsonNode roles = c.get("roles");
                    if (roles != null && roles.isArray()) {
                        String[] arr = new String[roles.size()];
                        for (int i = 0; i < roles.size(); i++) arr[i] = roles.get(i).asText();
                        championRoles.put(id, arr);
                    }
                });
            } catch (Exception ignored) {}
        }
    }

    public String champName(int id) { return championNames.getOrDefault(id, "ID" + id); }

    /** 英雄中文名反查 id（用于对面补查熟练度） */
    public int championIdByName(String name) {
        if (name == null) return -1;
        for (Map.Entry<Integer, String> e : championNames.entrySet()) {
            if (name.equals(e.getValue())) return e.getKey();
        }
        return -1;
    }

    /** 英雄主角色（取第一个roles） */
    public String champRole(int id) {
        String[] roles = championRoles.get(id);
        return (roles != null && roles.length > 0) ? roles[0] : "unknown";
    }

    /** 查指定英雄的熟练度，返回描述字符串或null */
    public String championMasteryFor(String puuid, int championId) {
        String json = lcu.get("/lol-champion-mastery/v1/" + puuid + "/champion-mastery");
        if (json == null || json.startsWith("__ERROR__")) return null;
        try {
            JsonNode arr = mapper.readTree(json);
            for (JsonNode m : arr) {
                if (m.path("championId").asInt() == championId) {
                    return String.format("%s 等级%d %d点 最高%s",
                            champName(championId),
                            m.path("championLevel").asInt(),
                            m.path("championPoints").asLong(),
                            m.path("highestGrade").asText(""));
                }
            }
            return "未玩过 " + champName(championId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 分析一个玩家的作战风格（近50把大乱斗），返回风格数据（带日志，选人阶段用） */
    public DataHub.Player analyze(String puuid, String name, String champion) {
        return analyze(puuid, name, champion, true);
    }

    /** 分析一个玩家的作战风格（静默，补查线程用，避免每轮刷屏） */
    public DataHub.Player analyzeSilent(String puuid, String name, String champion) {
        return analyze(puuid, name, champion, false);
    }

    private DataHub.Player analyze(String puuid, String name, String champion, boolean verbose) {
        DataHub.Player tm = new DataHub.Player();
        tm.puuid = puuid;
        tm.name = name;
        tm.champion = champion;

        // 分页拉取战绩，凑够 50 局大乱斗（LCU match-history 偶发返回不全，一次拉不满就翻页）
        java.util.List<JsonNode> kiwiGames = new java.util.ArrayList<>();
        int page = 0;
        int emptyPages = 0;
        while (kiwiGames.size() < 50 && emptyPages < 2) {
            int beg = page * 50;
            int end = beg + 49;
            String hist = getWithRetry("/lol-match-history/v1/products/lol/" + puuid + "/matches?begIndex=" + beg + "&endIndex=" + end);
            if (hist == null) {
                if (verbose) System.out.println("  战绩查询失败");
                break;
            }
            int before = kiwiGames.size();
            try {
                JsonNode games = mapper.readTree(hist).path("games").path("games");
                for (JsonNode g : games) {
                    if ("KIWI".equals(g.path("gameMode").asText())) kiwiGames.add(g);
                }
            } catch (Exception e) {
                if (verbose) System.out.println("  战绩解析失败: " + e.getMessage());
                break;
            }
            if (kiwiGames.size() == before) emptyPages++;  // 这一页没有新的大乱斗局
            else emptyPages = 0;
            page++;
            // 安全阀：最多翻 6 页（300 局）
            if (page >= 6) break;
        }
            if (kiwiGames.isEmpty()) { if (verbose) System.out.println("  无大乱斗战绩"); return tm; }

        try {
            int total = 0, wins = 0;
            double kills = 0, deaths = 0, assists = 0;
            for (JsonNode g : kiwiGames) {
                int targetParticipantId = -1;
                for (JsonNode id : g.path("participantIdentities")) {
                    if (puuid.equals(id.path("player").path("puuid").asText())) {
                        targetParticipantId = id.path("participantId").asInt();
                        break;
                    }
                }
                if (targetParticipantId < 0) continue;

                total++;
                for (JsonNode p : g.path("participants")) {
                    if (p.path("participantId").asInt() != targetParticipantId) continue;
                    JsonNode stats = p.path("stats");
                    if (stats.path("win").asBoolean()) wins++;
                    kills += stats.path("kills").asDouble();
                    deaths += stats.path("deaths").asDouble();
                    assists += stats.path("assists").asDouble();
                }
            }
            if (total == 0) { if (verbose) System.out.println("  无有效对局"); return tm; }

            double k = kills / total, d = deaths / total, a = assists / total;
            double wr = 100.0 * wins / total;
            tm.games = total;
            tm.winRate = Math.round(wr * 10) / 10.0;
            tm.kda = String.format("%.1f/%.1f/%.1f", k, d, a);
            tm.style = judgeStyle(k, d, a, wr);
            if (verbose) {
                System.out.printf("  近%d把海克斯大乱斗 胜率%.0f%%  KDA %.1f/%.1f/%.1f  %s%n",
                        total, wr, k, d, a, tm.style);
            }
        } catch (Exception e) {
            System.out.println("  战绩解析失败: " + e.getMessage());
        }
        return tm;
    }

    /** 带重试的 LCU GET（偶发返回不全/失败，间隔1s重试一次） */
    private String getWithRetry(String url) {
        String result = lcu.get(url);
        if (isBad(result)) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            result = lcu.get(url);
        }
        // 重试仍失败返回 null，让调用方走"查询失败"分支，不抛 JSON 解析异常
        return isBad(result) ? null : result;
    }

    /** 判断 LCU 返回是否异常（HTTP 错误或连接异常） */
    private static boolean isBad(String s) {
        return s == null || s.startsWith("__ERROR__") || s.startsWith("__EXCEPTION__");
    }

    /** 兼容：只打印（旧调用） */
    public void analyze(String puuid) {
        analyze(puuid, "", "");
    }

    /** 简单风格判断 */
    private String judgeStyle(double k, double d, double a, double winRate) {
        StringBuilder sb = new StringBuilder();
        if (winRate >= 60) sb.append("强势大腿 ");
        else if (winRate >= 50) sb.append("中规中矩 ");
        else if (winRate < 45) sb.append("可能偏弱 ");
        if (d >= 8) sb.append("容易上头(死亡多) ");
        if (k >= 10) sb.append("激进输出型 ");
        else if (a >= 20) sb.append("辅助/团队型 ");
        if (sb.length() == 0) sb.append("均衡型");
        return sb.toString().trim();
    }

    /** 测试入口 */
    public static void main(String[] args) {
        LcuClient lcu = new LcuClient();
        if (!lcu.connect()) return;
        TeammateAnalyzer ta = new TeammateAnalyzer(lcu);
        ta.loadMaps();
        String me = lcu.currentSummoner();
        String puuid = LcuClient.field(me, "puuid");
        if (puuid != null) ta.analyze(puuid);
    }
}
