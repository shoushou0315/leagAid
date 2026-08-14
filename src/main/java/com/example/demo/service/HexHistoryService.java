package com.example.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.List;

/**
 * 已选海克斯历史（Redis 持久化，按 sessionId 隔离）。
 *
 * key: hex:history:{sessionId} → JSON 数组 ["超凡邪恶","魔法飞弹"]
 * 一局一个会话，天然隔离；对局结束清理。
 */
@Service
public class HexHistoryService {

    private final String redisHost;
    private final int redisPort;
    private final ObjectMapper mapper = new ObjectMapper();

    public HexHistoryService(@Value("${app.redis.host:127.0.0.1}") String redisHost,
                             @Value("${app.redis.port:6379}") int redisPort) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
    }

    private String key(String sessionId) {
        return "hex:history:" + sessionId;
    }

    /** 记录已选海克斯（去重追加），返回当前列表 */
    public synchronized List<String> add(String sessionId, String name) {
        List<String> history = get(sessionId);
        if (name == null || name.isBlank()) return history;
        String clean = name.trim();
        if (!history.contains(clean)) history.add(clean);
        try (Jedis jedis = new Jedis(redisHost, redisPort)) {
            jedis.set(key(sessionId), mapper.writeValueAsString(history));
        } catch (Exception e) {
            System.out.println(">>> [HexHistory] 保存失败: " + e.getMessage());
        }
        return history;
    }

    /** 读取当前会话已选海克斯列表 */
    public List<String> get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return new ArrayList<>();
        try (Jedis jedis = new Jedis(redisHost, redisPort)) {
            String json = jedis.get(key(sessionId));
            if (json == null || json.isBlank()) return new ArrayList<>();
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 清理某会话历史（对局结束调用） */
    public void clear(String sessionId) {
        try (Jedis jedis = new Jedis(redisHost, redisPort)) {
            jedis.del(key(sessionId));
        } catch (Exception e) {
            System.out.println(">>> [HexHistory] 清理失败: " + e.getMessage());
        }
    }
}
