package com.example.demo.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Qwen 流式 function calling 兜底：清洗工具调用 arguments 的非法 JSON。
 *
 * 背景：qwen3 系列流式返回 tool_calls 时，arguments 偶尔带尾逗号（{"question":"...",}），
 *       Jackson 严格解析直接抛 JsonParseException。这里在交给 AiService 前修正。
 *
 * 处理：1) 去掉任意层级的尾逗号 ,} / ,]
 *       2) 仍非法则用括号配平截断到最后一个完整对象
 */
public class QwenStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final ObjectMapper mapper = new ObjectMapper();

    public QwenStreamingChatModel(StreamingChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        delegate.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                handler.onCompleteResponse(fixArguments(completeResponse));
            }

            @Override
            public void onError(Throwable error) {
                handler.onError(error);
            }
        });
    }

    private ChatResponse fixArguments(ChatResponse response) {
        AiMessage ai = response.aiMessage();
        if (ai == null || ai.toolExecutionRequests() == null || ai.toolExecutionRequests().isEmpty()) {
            return response;
        }
        List<ToolExecutionRequest> fixed = new ArrayList<>();
        boolean changed = false;
        for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
            String clean = sanitize(req.arguments());
            if (!clean.equals(req.arguments())) changed = true;
            fixed.add(ToolExecutionRequest.builder()
                    .id(req.id())
                    .name(req.name())
                    .arguments(clean)
                    .build());
        }
        if (!changed) return response;
        return ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                        .text(ai.text())
                        .toolExecutionRequests(fixed)
                        .build())
                .tokenUsage(response.tokenUsage())
                .finishReason(response.finishReason())
                .build();
    }

    private String sanitize(String args) {
        if (args == null || args.isBlank()) return "{}";
        String cleaned = args;
        // 多轮去尾逗号（嵌套时可能需多次）：去掉 ",}" / ",]"（含逗号前空白）
        for (int i = 0; i < 5; i++) {
            String next = cleaned;
            // 用逗号 + 空白 + 右括号 替换为先去掉逗号
            next = replaceTrailingComma(next);
            if (next.equals(cleaned)) break;
            cleaned = next;
        }
        if (isValidJson(cleaned)) return cleaned;
        String truncated = truncateBalanced(cleaned);
        if (isValidJson(truncated)) return truncated;
        // 兜底：仍非法则返回空对象，避免下游 JsonParseException
        System.out.println(">>> [ToolFix] 无法修复，降级为{}: [" + args + "]");
        return "{}";
    }

    /** 把 " ,}" / " ,]" （逗号紧邻右括号，可含空白）替换为 "}" / "]"；等价于正则 ,\s*([}\]]) -> $1 */
    private String replaceTrailingComma(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',') {
                int j = i + 1;
                while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t' || s.charAt(j) == '\n')) j++;
                if (j < s.length() && (s.charAt(j) == '}' || s.charAt(j) == ']')) {
                    i = j - 1;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private boolean isValidJson(String s) {
        try {
            mapper.readTree(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String truncateBalanced(String s) {
        int depth = 0;
        int lastZero = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '{': case '[': depth++; break;
                case '}': case ']': depth--; if (depth == 0) lastZero = i; break;
            }
        }
        return lastZero >= 0 ? s.substring(0, lastZero + 1) : s;
    }
}
