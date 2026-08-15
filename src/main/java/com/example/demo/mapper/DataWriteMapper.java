package com.example.demo.mapper;

import com.example.demo.entity.Augment;
import com.example.demo.entity.Hero;
import com.example.demo.entity.HeroAugmentRank;
import com.example.demo.entity.HeroItemBuild;
import com.example.demo.entity.HeroProfile;
import com.example.demo.entity.Item;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据采集写库 Mapper（替代 JPA Repository）
 *
 * 采集全量数据时清空重建 + 批量插入。
 */
@Mapper
public interface DataWriteMapper {

    // ===== 英雄 =====
    void deleteAllHeroes();

    int batchInsertHeroes(@Param("list") List<Hero> list);

    List<Hero> findAllHeroes();

    // ===== 海克斯 =====
    void deleteAllAugments();

    int batchInsertAugments(@Param("list") List<Augment> list);

    // ===== 英雄×海克斯排名 =====
    void deleteHeroAugmentRanks(@Param("heroId") Integer heroId);

    int batchInsertHeroAugmentRanks(@Param("list") List<HeroAugmentRank> list);

    // ===== 装备 =====
    void deleteAllItems();

    /** 删除 id 以指定前缀开头的装备（大乱斗清洗：删 22/77 前缀残留） */
    int deleteItemsByPrefix(@Param("p1") String p1, @Param("p2") String p2);

    /** 查指定 id 的 from_ids（合成来源） */
    List<String> findFromIdsByIds(@Param("ids") List<Integer> ids);

    int batchInsertItems(@Param("list") List<Item> list);

    // ===== 出装方案 =====
    void deleteHeroBuilds(@Param("heroId") Integer heroId);

    int batchInsertHeroBuilds(@Param("list") List<HeroItemBuild> list);

    List<HeroItemBuild> findHeroBuilds(@Param("heroId") Integer heroId);

    // ===== 扩展装备（情境/推荐） =====
    void deleteHeroExt(@Param("heroId") Integer heroId);

    int batchInsertHeroExt(@Param("list") List<com.example.demo.entity.HeroItemExt> list);

    // ===== 英雄档案 =====
    void deleteAllHeroProfiles();

    int batchInsertHeroProfiles(@Param("list") List<HeroProfile> list);
}
