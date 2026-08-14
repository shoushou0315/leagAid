package com.example.demo.service.lcu;

import javax.net.ssl.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;

/**
 * LCU 客户端（完整方案）
 *
 * 认证获取：通过 NtQueryInformationProcess 底层API读取 LeagueClientUx 进程命令行
 *           （复刻阿卡丽助手方案，绕过ACE反作弊对标准API的拦截）
 * 数据获取：账号信息 / 选人队友 / 战绩 / 可选英雄
 *
 * 注意：需以管理员权限运行（读取进程命令行需要）。
 */
public class LcuClient {

    private int port;
    private String token;
    private HttpClient client;

    /** 连接LCU：动态获取端口/token */
    public boolean connect() {
        LcuNtAuth.setVerbose(false); // 静默获取，不打印进程命令行
        List<LcuNtAuth.UxAuthInfo> infos = LcuNtAuth.findUxAuth();
        if (infos.isEmpty()) {
            return false;  // 静默失败，由上层 LeagAidRunner 周期重试
        }
        LcuNtAuth.UxAuthInfo info = infos.get(0);
        this.port = info.port;
        this.token = info.authToken;
        System.out.println("[OK] 连接LCU: port=" + port);
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            client = HttpClient.newBuilder()
                    .sslContext(ctx)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** 重连 LCU（客户端重启后端口/token 变化）：重新获取并重建连接 */
    public boolean reconnect() {
        int oldPort = this.port;
        boolean ok = connect();
        if (ok && this.port != oldPort) {
            System.out.println("[重连] LCU 端口变化 " + oldPort + " -> " + this.port);
        }
        return ok;
    }

    /** GET 请求，返回原始JSON字符串 */
    public String get(String api) {
        try {
            String auth = "Basic " + Base64.getEncoder()
                    .encodeToString(("riot:" + token).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://127.0.0.1:" + port + (api.startsWith("/") ? api : "/" + api)))
                    .header("Authorization", auth)
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : "__ERROR__:" + resp.statusCode() + ":" + resp.body();
        } catch (Exception e) {
            return "__EXCEPTION__:" + e.getMessage();
        }
    }

    /** POST 请求（JSON body），返回原始JSON字符串 */
    public String post(String api, String jsonBody) {
        try {
            String auth = "Basic " + Base64.getEncoder()
                    .encodeToString(("riot:" + token).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://127.0.0.1:" + port + (api.startsWith("/") ? api : "/" + api)))
                    .header("Authorization", auth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : "__ERROR__:" + resp.statusCode() + ":" + resp.body();
        } catch (Exception e) {
            return "__EXCEPTION__:" + e.getMessage();
        }
    }

    /** 简单JSON字段提取（测试用；生产用Jackson） */
    public static String field(String json, String name) {
        if (json == null) return null;
        String key = "\"" + name + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        String rest = json.substring(idx + key.length()).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf("\"", 1);
            return end < 0 ? null : rest.substring(1, end);
        }
        int end = Math.min(
                rest.indexOf(",") < 0 ? rest.length() : rest.indexOf(","),
                rest.indexOf("}") < 0 ? rest.length() : rest.indexOf("}"));
        return rest.substring(0, end).trim();
    }

    // ==================== 业务接口 ====================

    /** 当前账号信息 */
    public String currentSummoner() { return get("/lol-summoner/v1/current-summoner"); }

    /** 英雄熟练度 */
    public String championMastery(long summonerId) {
        return get("/lol-collections/v1/inventories/" + summonerId + "/champion-mastery");
    }

    /** 段位信息 */
    public String rankedStats() { return get("/lol-ranked/v1/current-ranked-stats"); }

    /** 选人会话（含队友ID） */
    public String champSelectSession() { return get("/lol-champ-select/v1/session"); }

    /** 可选英雄 */
    public String pickableChampions() { return get("/lol-champ-select/v1/pickable-champions"); }

    /** 战绩（用accountId） */
    public String matchHistory(String accountId) {
        return get("/lol-match-history/v1/products/lol/" + accountId + "/matches?begIndex=0&endIndex=10");
    }

    /** 测试入口：完整演示账号/队友/战绩 */
    public static void main(String[] args) {
        System.out.println("========== LCU 客户端完整测试 ==========");
        LcuClient lcu = new LcuClient();
        if (!lcu.connect()) return;

        // 1. 账号信息
        System.out.println("\n===== 账号信息 =====");
        String me = lcu.currentSummoner();
        System.out.println(me);
        String accountId = field(me, "accountId");
        String summonerId = field(me, "summonerId");
        System.out.println("  → accountId=" + accountId + ", summonerId=" + summonerId);

        // 2. 段位
        System.out.println("\n===== 段位 =====");
        String rank = lcu.rankedStats();
        System.out.println("  段位字段(tier): " + field(rank, "tier") + "  单排最高: " + field(rank, "highestCurrentSeasonReachedTierSR"));

        // 3. 熟练度（路径可能因版本不同，失败则打印原始）
        System.out.println("\n===== 英雄熟练度 =====");
        String mastery = lcu.championMastery(Long.parseLong(summonerId));
        System.out.println(mastery.substring(0, Math.min(400, mastery.length())));

        // 4. 选人会话（需在选人界面）
        System.out.println("\n===== 选人会话 =====");
        String session = lcu.champSelectSession();
        System.out.println(session.substring(0, Math.min(800, session.length())));

        // 5. 战绩
        System.out.println("\n===== 近期战绩 =====");
        String hist = lcu.matchHistory(accountId);
        System.out.println(hist.substring(0, Math.min(500, hist.length())));

        System.out.println("\n========== 测试完成 ==========");
    }
}
