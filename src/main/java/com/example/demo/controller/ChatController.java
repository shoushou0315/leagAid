package com.example.demo.controller;

import com.example.demo.ai.DynamicContentRetriever;
import com.example.demo.ai.QueryRouter;
import com.example.demo.service.ConsultantService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
public class ChatController {

    private final ConsultantService consultantService;
    private final DynamicContentRetriever ragRetriever;
    private final QueryRouter queryRouter;
    private final Path memoryDir;

    public ChatController(ConsultantService consultantService,
                          DynamicContentRetriever ragRetriever,
                          QueryRouter queryRouter,
                          @Value("${app.memory-dir:data/chat-memories}") String memoryDir) {
        this.consultantService = consultantService;
        this.ragRetriever = ragRetriever;
        this.queryRouter = queryRouter;
        this.memoryDir = Path.of(memoryDir);
        this.memoryDir.toFile().mkdirs();
    }

    /** 对话（带 sessionId 才有跨轮记忆）。硬路由命中直接返回数据，miss 走 LLM（告知固定查询已试过，避免重复调 tryFixedQuery） */
    @GetMapping("/chat")
    public Flux<String> chat(@RequestParam(defaultValue = "anonymous") String sessionId,
                             @RequestParam String message) {
        if (ragRetriever != null && !ragRetriever.isReady()) {
            return Flux.just("【知识库构建中】向量索引尚未就绪，暂时无法回答问题。请稍后重试，或刷新 /refresh 触发更新。");
        }
        String routed = queryRouter.route(message);
        if (routed != null) {
            return Flux.just(routed);
        }
        // 直接传原始用户消息（不拼前缀，避免污染记忆/前端显示）；
        // "固定查询已试过、别重复调"由系统提示词统一约束
        return consultantService.chat(sessionId, message);
    }

    /** 开新会话：返回时间戳 sessionId */
    @GetMapping("/session/new")
    public String newSession() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    /** 列出历史会话（时间戳 + 首条提问摘要） */
    @GetMapping("/sessions")
    public List<Map<String, String>> listSessions() {
        List<Map<String, String>> sessions = new ArrayList<>();
        File[] files = memoryDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return sessions;
        List<File> sorted = new ArrayList<>(List.of(files));
        sorted.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (File f : sorted) {
            String sessionId = f.getName().replace(".json", "");
            String summary = firstUserMessage(f);
            sessions.add(Map.of(
                    "sessionId", sessionId,
                    "createdAt", LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(f.lastModified()),
                            java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    "summary", summary));
        }
        return sessions;
    }

    /** 查看某局完整对话历史 */
    @GetMapping("/session/{sessionId}")
    public List<String> getHistory(@PathVariable String sessionId) {
        File f = memoryDir.resolve(sessionId + ".json").toFile();
        if (!f.exists()) return List.of("会话不存在: " + sessionId);
        try {
            String json = Files.readString(f.toPath());
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
            List<String> result = new ArrayList<>();
            for (ChatMessage m : messages) {
                result.add(m.type() + ": " + extractText(m));
            }
            return result;
        } catch (Exception e) {
            return List.of("读取失败: " + e.getMessage());
        }
    }

    private String extractText(ChatMessage m) {
        if (m instanceof dev.langchain4j.data.message.SystemMessage s) return s.text();
        if (m instanceof dev.langchain4j.data.message.UserMessage u) return u.singleText();
        if (m instanceof dev.langchain4j.data.message.AiMessage a) return a.text() == null ? "[工具调用]" : a.text();
        if (m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage t) return t.text();
        return m.toString();
    }

    /** 删除会话 */
    @GetMapping("/session/{sessionId}/delete")
    public String deleteSession(@PathVariable String sessionId) {
        File f = memoryDir.resolve(sessionId + ".json").toFile();
        if (f.delete()) return "已删除: " + sessionId;
        return "删除失败或不存在: " + sessionId;
    }

    private String firstUserMessage(File f) {
        try {
            String json = Files.readString(f.toPath());
            if (json.isBlank()) return "空会话";
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
            for (ChatMessage m : messages) {
                if (m.type() == dev.langchain4j.data.message.ChatMessageType.USER) {
                    String t = extractText(m);
                    return t.length() > 30 ? t.substring(0, 30) + "..." : t;
                }
            }
            return "无用户消息";
        } catch (Exception e) {
            return "解析失败";
        }
    }

    @GetMapping("/refresh")
    public String refresh() {
        if (ragRetriever != null) {
            ragRetriever.refresh();
            return "开始更新...";
        }
        return "不支持的操作";
    }

    /** 补采缺失的装备build数据 */
    @GetMapping("/refresh-builds")
    public String refreshBuilds(com.example.demo.service.AramggDataService dataService) {
        var missing = dataService.getMissingBuildHeroes();
        if (missing.isEmpty()) return "build数据完整，无需补采";
        new Thread(() -> dataService.syncMissingBuilds(), "build-refresh").start();
        return "开始补采 " + missing.size() + " 个英雄的build数据（" + missing + "）";
    }

    @GetMapping("/status")
    public String status() {
        if (ragRetriever != null) {
            return ragRetriever.isReady() ? "就绪" : "更新中";
        }
        return "未知";
    }

    /** 硬路由命中统计 */
    @GetMapping("/route-stats")
    public String routeStats() {
        return queryRouter.stats();
    }
}
