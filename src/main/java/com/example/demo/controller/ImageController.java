package com.example.demo.controller;

import com.example.demo.mapper.HeroMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 名称→图片映射接口：前端渲染 AI 回答时，把出现的英雄/装备/海克斯名替换成图标。
 * 返回 {名称: 图片URL}，英雄覆盖 称号/官方中文名/英文名 三种叫法。
 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final HeroMapper heroMapper;

    public ImageController(HeroMapper heroMapper) {
        this.heroMapper = heroMapper;
    }

    /** 全量名称→图片URL 映射（前端启动时加载一次） */
    @GetMapping("/mapping")
    public Map<String, String> mapping() {
        Map<String, String> map = new LinkedHashMap<>();

        // 英雄：称号/官方中文名 → 头像（英文名不入映射，避免误匹配 HTML 标签和 URL）
        for (Map<String, Object> h : heroMapper.findAllHeroesWithImage()) {
            String url = String.valueOf(h.get("image_url"));
            put(map, h.get("name"), url);
            put(map, h.get("official_name"), url);
        }
        // 海克斯：中文名 → 图标
        for (Map<String, Object> a : heroMapper.findAllAugmentsWithImage()) {
            put(map, a.get("name"), String.valueOf(a.get("image_url")));
        }
        // 装备：中文名 → 图标（同名装备（如多个版本的"兰德里的折磨"）保留第一个，避免被后到的覆盖成错图）
        for (Map<String, Object> it : heroMapper.findAllItemsWithImage()) {
            String name = String.valueOf(it.get("name")).trim();
            String url = String.valueOf(it.get("image_url"));
            if (name.isBlank() || url.isBlank() || url.equals("null")) continue;
            // 已存在同名（英雄/海克斯优先），或装备重名保留先到的
            if (map.containsKey(name)) continue;
            map.put(name, url);
        }
        return map;
    }

    private void put(Map<String, String> map, Object name, String url) {
        if (name == null || url == null || url.isBlank() || String.valueOf(name).isBlank()) return;
        map.put(String.valueOf(name).trim(), url);
    }
}
