package com.example.demo.entity;

import lombok.Data;

/** 英雄×海克斯排名（来自 /data/champion-details/{id}.json）核心决策表 */
@Data
public class HeroAugmentRank {

    private Long id;

    private Integer heroId;

    private Integer augmentId;

    private String tier;          // 该英雄下此海克斯的等级
    private Integer winRank;      // 排名
    private Integer total;        // 总数 145
    private Double winRate;       // 胜率
    private Double pickRate;      // 选取率
    private Long numGames;        // 场次
    private Long numWinGames;     // 胜场
}
