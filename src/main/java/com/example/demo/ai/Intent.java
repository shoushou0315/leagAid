package com.example.demo.ai;

/** 用户意图分类（任务类型），用于决策"走哪条取数管线 + 回复框架"。 */
public enum Intent {
    HEX_PICK,   // 选海克斯
    BUILD,      // 出装
    COUNTER,    // 打对面/克制
    SYNERGY,    // 机制联动/搭配
    FREE_QUERY, // 胜率/排行/数据
    DESCRIPTIVE,// 按效果/机制描述找
    UPDATE_DB,  // 更新知识库
    CHAT        // 闲聊/兜底
}
