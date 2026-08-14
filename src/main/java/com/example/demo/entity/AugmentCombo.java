package com.example.demo.entity;

import lombok.Data;

/** 英雄推荐海克斯三连组合（来自 /data/champion-details/{id}.json 的 augment_trios） */
@Data
public class AugmentCombo {

    private Long id;

    private Integer heroId;

    private String augmentIds;    // "1077:1225:1336"

    private Double winRate;       // 胜率
    private Double pickRate;      // 选取率
    private Long numGames;        // 场次
    private Long numWinGames;     // 胜场
    private Integer winRank;      // 胜率排名，1=最强组合
}
