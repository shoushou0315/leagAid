package com.example.demo.entity;

import lombok.Data;

/** 英雄扩展装备（情境装备 situational / 推荐装备 recommended，无序列表，无胜率） */
@Data
public class HeroItemExt {

    private Long id;

    private Integer heroId;

    private Integer itemId;

    private String extType;   // situational / recommended

    private Integer slot;
}
