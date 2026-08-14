package com.example.demo.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.util.SafeEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Redis 8 原生 Vector Set 向量存储（替代 JVector）。
 *
 * 用 Jedis CommandArguments 执行 Redis 8 Vector Set API：
 *   - VADD key VALUES <dim> <v1> <v2>... <element>   写入向量
 *   - VSIM key VALUES <dim> <v1> <v2>... COUNT n WITHSCORES   相似度查询
 *   - VSETATTR key element json                       关联元数据/文本
 *   - VGETATTR key element                            取元数据/文本
 *   - VCARD / VDIM                                    数量/维度
 *
 * 注意：536 维向量必须用 CommandArguments + executeCommand 构建，
 *       sendCommand(ProtocolCommand, String...) 的 varargs 对大参数有 bug（报 invalid vector specification）。
 */
public class RedisVectorStore {

    private static final ProtocolCommand VADD = () -> SafeEncoder.encode("VADD");
    private static final ProtocolCommand VSIM = () -> SafeEncoder.encode("VSIM");
    private static final ProtocolCommand VCARD = () -> SafeEncoder.encode("VCARD");
    private static final ProtocolCommand VSETATTR = () -> SafeEncoder.encode("VSETATTR");
    private static final ProtocolCommand VGETATTR = () -> SafeEncoder.encode("VGETATTR");
    private static final ProtocolCommand DEL = () -> SafeEncoder.encode("DEL");

    private final String host;
    private final int port;
    private final String key;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisVectorStore(String host, int port, String key) {
        this.host = host;
        this.port = port;
        this.key = key;
    }

    private Jedis conn() {
        return new Jedis(host, port);
    }

    /** 写入一条向量 + 元数据（元素名用唯一 id） */
    public void add(String id, float[] vector, String pageType, String pageId, String text) {
        try (Jedis jedis = conn()) {
            CommandArguments ca = new CommandArguments(VADD);
            ca.add(key).add("VALUES").add(String.valueOf(vector.length));
            for (float v : vector) ca.add(String.valueOf(Float.isNaN(v) ? 0f : v));
            ca.add(id);
            jedis.getConnection().executeCommand(ca);
            // 元数据关联
            String attrs = String.format("{\"pageType\":\"%s\",\"pageId\":\"%s\",\"text\":%s}",
                    escapeJson(pageType), escapeJson(pageId), toJsonString(text));
            CommandArguments sa = new CommandArguments(VSETATTR);
            sa.add(key).add(id).add(attrs);
            jedis.getConnection().executeCommand(sa);
        }
    }

    /** KNN 查询：返回 [{id, score, text, pageType}] */
    public List<Map<String, Object>> search(float[] query, int topN) {
        try (Jedis jedis = conn()) {
            CommandArguments qa = new CommandArguments(VSIM);
            qa.add(key).add("VALUES").add(String.valueOf(query.length));
            for (float v : query) qa.add(String.valueOf(Float.isNaN(v) ? 0f : v));
            qa.add("COUNT").add(String.valueOf(topN)).add("WITHSCORES");
            Object raw = jedis.getConnection().executeCommand(qa);

            List<Map<String, Object>> out = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (int i = 0; i + 1 < list.size(); i += 2) {
                    String elem = str(list.get(i));
                    double score = Double.parseDouble(str(list.get(i + 1)));
                    String text = elem;
                    String pageType = "";
                    Object attr = jedis.getConnection().executeCommand(
                            new CommandArguments(VGETATTR).add(key).add(elem));
                    if (attr != null) {
                        String s = str(attr);
                        if (!s.isBlank() && !"nil".equalsIgnoreCase(s)) {
                            try {
                                var node = mapper.readTree(s);
                                text = node.path("text").asText(elem);
                                pageType = node.path("pageType").asText("");
                            } catch (Exception ignored) { }
                        }
                    }
                    out.add(Map.of("id", elem, "score", score, "text", text, "pageType", pageType));
                }
            }
            return out;
        }
    }

    /** 是否已有数据 */
    public boolean hasData() {
        try (Jedis jedis = conn()) {
            Object r = jedis.getConnection().executeCommand(new CommandArguments(VCARD).add(key));
            if (r == null) return false;
            try { return Long.parseLong(str(r)) > 0; }
            catch (Exception e) { return false; }
        }
    }

    /** 清空（重建前调用） */
    public void clear() {
        try (Jedis jedis = conn()) {
            jedis.getConnection().executeCommand(new CommandArguments(DEL).add(key));
        }
    }

    public void close() {
        // Jedis 无池化，无需显式关闭连接池
    }

    private static String str(Object o) {
        if (o == null) return "";
        if (o instanceof byte[] b) return SafeEncoder.encode(b);
        return String.valueOf(o);
    }

    private static String toJsonString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
