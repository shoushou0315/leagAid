package com.example.demo.entity;

import lombok.Data;

/** 英雄档案（来自 DDragon 单英雄 JSON：技能/玩法技巧/简介） */
@Data
public class HeroProfile {

    private Integer heroId;       // heroes.id

    private String title;         // 称号

    private String blurb;         // 简介

    private String tags;          // 定位，逗号分隔

    private String passive;       // 被动技能

    private String spells;        // Q/W/E/R 技能，换行分隔

    private String allyTips;      // 玩法技巧（队友视角）

    private String enemyTips;     // 对抗技巧

    private String version;       // 数据版本

    public String getEnemyTips() { return enemyTips; }
    public void setEnemyTips(String enemyTips) { this.enemyTips = enemyTips; }
}
