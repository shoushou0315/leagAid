package com.example.demo.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 英雄固定查询（MyBatis）
 *
 * 选人界面/高频接口用的固定查询，预定义 SQL、参数化、安全。
 */
@Mapper
public interface HeroMapper {

    /** 按名称查英雄（称号/官方名/英文名模糊匹配） */
    Map<String, Object> findHero(@Param("name") String name);

    /** 英雄胜率/Tier/排名 */
    Map<String, Object> getHeroStats(@Param("id") Integer id);

    /** 该英雄海克斯排名 TOP N */
    List<Map<String, Object>> getHeroAugments(@Param("heroId") Integer heroId, @Param("limit") int limit);

    /** 该英雄指定海克斯（按名称模糊匹配）的胜率/排名 */
    List<Map<String, Object>> getHeroAugmentByName(@Param("heroId") Integer heroId, @Param("keyword") String keyword);

    /** 该英雄出装方案 */
    List<Map<String, Object>> getBuilds(@Param("heroId") Integer heroId);

    /** 该英雄玩法档案 */
    Map<String, Object> getHeroProfile(@Param("heroId") Integer heroId);

    /** 英雄排行榜 TOP N */
    List<Map<String, Object>> getTopHeroes(@Param("limit") int limit);

    /** 三连组合 TOP N */
    List<Map<String, Object>> getCombos(@Param("heroId") Integer heroId, @Param("limit") int limit);

    /** 全量海克斯（向量索引构建用） */
    List<Map<String, Object>> findAllAugments();

    /** 全量装备（向量索引构建用） */
    List<Map<String, Object>> findAllItems();

    /** 全量英雄档案（向量索引构建用） */
    List<Map<String, Object>> findAllHeroProfiles();

    /** 海克斯数量 */
    long countAugments();

    /** 装备数量 */
    long countItems();

    /** 英雄档案数量 */
    long countHeroProfiles();

    /**
     * 动态查询（参数化，LLM 不写 SQL 只填参数）
     * table: 白名单表名；heroId: 英雄id；keyword: 模糊搜索词；tier: 稀有度
     * order: asc/desc；limit: 条数
     */
    List<Map<String, Object>> dynamicQuery(@Param("table") String table,
                                           @Param("heroId") Integer heroId,
                                           @Param("keyword") String keyword,
                                           @Param("tier") String tier,
                                           @Param("order") String order,
                                           @Param("limit") int limit);

    /** 按 id 批量查海克斯名（三连组合 id→名字 映射用） */
    List<Map<String, Object>> findAugmentNamesByIds(@Param("ids") List<Integer> ids);

    /** 全量英雄名（语音热词用：称号+官方名+英文名） */
    List<String> findAllHeroNames();

    /** 全量海克斯名（语音热词用） */
    List<String> findAllAugmentNames();

    /** 全量装备名（语音热词用） */
    List<String> findAllItemNames();
}
