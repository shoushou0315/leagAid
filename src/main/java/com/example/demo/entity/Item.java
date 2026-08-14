package com.example.demo.entity;

import lombok.Data;

/** 装备（来自 DDragon item.json） */
@Data
public class Item {

    private Integer id;

    private String name;          // 中文名

    private String enName;        // 英文名

    private String description;   // 详细描述

    private String plaintext;     // 简短描述

    private Integer totalPrice;   // 总价
    private Integer basePrice;    // 基础价

    private String tags;          // 分类，逗号分隔

    private String fromIds;       // 合成来源，逗号分隔

    private String intoIds;       // 可升级为，逗号分隔

    private String version;       // 数据版本
}
