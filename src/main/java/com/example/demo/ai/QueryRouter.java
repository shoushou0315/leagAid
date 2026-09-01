package com.example.demo.ai;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 追问放行器：仅识别"那/如果/再"开头的追问放行走 LLM，不做正则固定查询短路。
 * 高频固定查询（排行榜/出装/数据包等）已交由意图路由 + LLM 工具链（模型自调 tryFixedQuery/queryKnowledge）。
 */
@Component
public class QueryRouter {

    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger hit = new AtomicInteger();
    private final AtomicLong hitMillis = new AtomicLong();

    public QueryRouter() {
    }

    /** 仅判断追问放行：命中放行返回 null（走 LLM），其余也返回 null（全部走意图路由/LLM）。固定查询不再正则短路。 */
    public String route(String question) {
        total.incrementAndGet();
        if (isFollowUp(question)) {
            System.out.println("[路由] 追问放行 → LLM: " + question);
            return null;
        }
        return null;
    }

    /** 判断是否为追问（多轮对话中承接上文的问题） */
    public boolean isFollowUp(String q) {
        String t = q.trim();
        if (t.isEmpty()) return false;
        return t.matches("^(那|然后|再|还|另外|如果|要是|假如|比如|例如|那如果|那要是|那假如|换个|换种|再来|继续|接着|之后|后面|然后如果).*");
    }

    /** 路由统计（兼容 /route-stats 接口） */
    public String stats() {
        int t = total.get();
        int h = hit.get();
        double rate = t == 0 ? 0 : 100.0 * h / t;
        double avg = h == 0 ? 0 : (double) hitMillis.get() / h;
        return String.format("总请求 %d，命中 %d，命中率 %.1f%%，命中平均耗时 %.0fms",
                t, h, rate, avg);
    }
}
