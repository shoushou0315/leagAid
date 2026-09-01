package com.example.demo.entity;

import lombok.Data;

/** 海克斯强化（来自 /data/aram-mayhem-augments.zh_cn.json） */
@Data
public class Augment {

    private Integer id;

    private String name;          // 中文名 displayName

    private String enName;        // 内部名 ARAM_XXX

    private Integer rarity;       // 0=白银 1=黄金 2=棱彩
    private String tierName;      // 白银/黄金/棱彩

    private String description;   // 描述

    private String tooltip;       // tooltip 详细效果

    private Boolean enabled;

    private String imageUrl;      // 图标 URL（cdn.dtodo.cn，iconSmall 转小写）

    private Double globalWinRate; // 海克斯全英雄平均胜率（来自 aramgg augments 页面，0~100）
}
