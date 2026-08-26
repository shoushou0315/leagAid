package com.example.demo;

import com.example.demo.ai.DatabaseTools;
import com.example.demo.ai.DynamicContentRetriever;
import com.example.demo.ai.FixedQueryTools;
import com.example.demo.model.QwenEmbeddingModel;
import com.example.demo.service.ConsultantService;
import com.example.demo.service.JsonFileChatMemoryStore;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.service.AiServices;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Value("${chat.api-key}")
    private String chatApiKey;
    @Value("${chat.base-url}")
    private String chatBaseUrl;
    @Value("${chat.model}")
    private String chatModel;
    @Value("${qwen.api-key}")
    private String qwenApiKey;
    @Value("${qwen.base-url}")
    private String qwenBaseUrl;
    @Value("${qwen.embedding-model}")
    private String qwenEmbeddingModel;
    @Value("${app.memory-dir}")
    private String memoryDir;

    @Bean
    ChatMemoryStore chatMemoryStore() {
        return new JsonFileChatMemoryStore(java.nio.file.Path.of(memoryDir));
    }

    @Bean
    ChatMemoryProvider chatMemoryProvider(ChatMemoryStore store) {
        return new PersistentChatMemoryProvider(store);
    }

    @Bean
    StreamingChatModel streamingChatModel() {
        StreamingChatModel raw = OpenAiStreamingChatModel.builder()
                .apiKey(chatApiKey)
                .baseUrl(chatBaseUrl)
                .modelName(chatModel)
                .customParameters(java.util.Map.of("enable_thinking", false))  // qwen3 关闭 thinking
                .accumulateToolCallId(true)  // qwen 流式 tool_calls 的 id 从完整值变空串，需累积避免拼接错乱
                .logRequests(false)  // 关闭 HTTP 请求日志，避免刷屏
                .logResponses(false)  // 关闭 HTTP 响应日志
                .build();
        return new com.example.demo.model.QwenStreamingChatModel(raw);  // 清洗 tool arguments 尾逗号
    }
    @Bean
    ChatModel chatModel() {
        // 意图分类专用：只求快。关 thinking（对齐主对话，否则 qwen3 思考会拖到超时）；短超时 + 不重试，失败由 ChatController 降级 CHAT
        return OpenAiChatModel.builder()
                .apiKey(chatApiKey)
                .baseUrl(chatBaseUrl)
                .modelName(chatModel)
                .customParameters(java.util.Map.of("enable_thinking", false))
                .timeout(Duration.ofSeconds(8))
                .maxRetries(0)
                .build();
    }
    @Bean
    com.example.demo.ai.IntentClassifier intentClassifier(ChatModel chatModel) {
        return AiServices.builder(com.example.demo.ai.IntentClassifier.class)
                .chatModel(chatModel)
                .build();
    }
    @Bean
    EmbeddingModel embeddingModel() {
        return new QwenEmbeddingModel(qwenApiKey, qwenEmbeddingModel, qwenBaseUrl + "/compatible-mode/v1");
    }

    @Bean
    DynamicContentRetriever ragRetriever(EmbeddingModel embeddingModel,
                                         com.example.demo.service.AramggDataService dataService,
                                         com.example.demo.mapper.HeroMapper heroMapper,
                                         @Value("${app.redis.host:127.0.0.1}") String redisHost,
                                         @Value("${app.redis.port:6379}") int redisPort,
                                         @Value("${aramgg.vector-dimension:1536}") int vectorDimension) {
        return new DynamicContentRetriever(embeddingModel, dataService, heroMapper,
                redisHost, redisPort, "vec:rag", vectorDimension);
    }

    /** 显式绑定 AiService：注入工具（固定查询 + 自由查询 + 语义检索 + 游戏状态），流式，工具调用正常 */
    @Bean
    ConsultantService consultantService(StreamingChatModel streamingChatModel,
                                        org.springframework.jdbc.core.JdbcTemplate jdbc,
                                        com.example.demo.mapper.HeroMapper heroMapper,
                                        DynamicContentRetriever ragRetriever,
                                        com.example.demo.ai.GameStateTool gameStateTool,
                                        com.example.demo.ai.HexRecognizeTool hexRecognizeTool,
                                        ChatMemoryProvider chatMemoryProvider) {
        // 直接用 new 实例（避免 Spring 代理导致 @Tool 注解丢失）
        DatabaseTools databaseTools = new DatabaseTools(jdbc, heroMapper);
        FixedQueryTools fixedQueryTools = new FixedQueryTools(heroMapper);
        return AiServices.builder(ConsultantService.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(databaseTools, fixedQueryTools, ragRetriever, gameStateTool, hexRecognizeTool)
                .build();
    }

    static class PersistentChatMemoryProvider implements ChatMemoryProvider {

        private final ChatMemoryStore store;

        PersistentChatMemoryProvider(ChatMemoryStore store) {
            this.store = store;
        }

        @Override
        public ChatMemory get(Object memoryId) {
            return MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(100)
                    .chatMemoryStore(store)
                    .build();
        }
    }
}