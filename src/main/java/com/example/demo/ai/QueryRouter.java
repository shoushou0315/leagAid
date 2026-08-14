package com.example.demo.ai;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 硬路由：代码层意图分类，命中高频固定查询直接短路返回（0 次 LLM 调用）。
 *
 * 路由优先级：
 *   1. 排行榜/强查询 → getTopHeroes
 *   2. "英雄有了/拿到 X" 组合 → tryHeroAugmentCombo
 *   3. 英雄数据包（胜率/海克斯/出装/组合/玩法）→ 完整包
 *   4. miss → 返回 null，走 LLM 软路由兜底
 *
 * 与 FixedQueryTools 共用 query() 逻辑，保证 Tool 与硬路由行为一致。
 */
@Component
public class QueryRouter {

    /** 未命中标志：FixedQueryTools.query() 返回以它开头即表示固定查询未命中 */
    static final String MISS_PREFIX = "【未命中固定查询";

    private final FixedQueryTools fixedQueryTools;

    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger hit = new AtomicInteger();
    private final AtomicLong hitMillis = new AtomicLong();

    public QueryRouter(FixedQueryTools fixedQueryTools) {
        this.fixedQueryTools = fixedQueryTools;
    }

    /**
     * 硬路由尝试：命中返回可直出的数据（非 null），未命中返回 null（走 LLM）。
     * 追问型问题（"那/然后/再/如果/还有"开头）直接放行，不进固定查询。
     */
    public String route(String question) {
        total.incrementAndGet();
        // 追问型问题：多轮对话中带上下文的追问，固定查询无法独立命中，直接放行
        if (isFollowUp(question)) {
            System.out.println(">>> [Router] 追问型问题放行: " + question);
            return null;
        }
        long t0 = System.currentTimeMillis();
        String result = fixedQueryTools.query(question);
        if (result == null || result.startsWith(MISS_PREFIX)) {
            return null;
        }
        hit.incrementAndGet();
        hitMillis.addAndGet(System.currentTimeMillis() - t0);
        System.out.println(">>> [Router] 硬路由命中: " + question + "（耗时 " + (System.currentTimeMillis() - t0) + "ms）");
        return result;
    }

    /** 判断是否为追问（多轮对话中承接上文的问题） */
    public boolean isFollowUp(String q) {
        String t = q.trim();
        if (t.isEmpty()) return false;
        return t.matches("^(那|然后|再|还|另外|如果|要是|假如|比如|例如|那如果|那要是|那假如|换个|换种|再来|继续|接着|之后|后面|然后如果).*");
    }

    /** 路由统计（简历/监控用） */
    public String stats() {
        int t = total.get();
        int h = hit.get();
        double rate = t == 0 ? 0 : 100.0 * h / t;
        double avg = h == 0 ? 0 : (double) hitMillis.get() / h;
        return String.format("总请求 %d，命中 %d，命中率 %.1f%%，命中平均耗时 %.0fms",
                t, h, rate, avg);
    }
}
