package com.example.demo.entity;

import lombok.Data;

/** 英雄×装备推荐方案（来自 aramgg 英雄页 build 面板） */
@Data
public class HeroItemBuild {

    private Long id;

    private Integer heroId;

    private Integer buildIndex;   // 第几套方案 0/1/2

    private Integer groupIndex;   // 方案内第几组 1/2/3

    private Integer slot;         // 装备槽位 1-3

    private Integer itemId;

    private Double winRate;       // 该方案胜率
    private Double pickRate;      // 该方案选取率
}
