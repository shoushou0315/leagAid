package com.example.demo.entity;

import lombok.Data;

/** 英雄（来自 /data/champions-stats.json） */
@Data
public class Hero {

    private Integer id;

    private String name;          // 称号，如：刀锋之影

    private String officialName;  // 官方中文名，如：泰隆

    private String enName;        // 英文名

    private String tier;          // T1/T2/T3...
    private Double winRate;       // 胜率 0.5756
    private Double pickRate;      // 选取率
    private String version;       // 版本 16.15
    private String date;          // 数据日期
    private Integer winRank;      // 总排名

    private String imageUrl;      // 头像 URL（cdn.dtodo.cn，champion-icons/{id}.png）
}
