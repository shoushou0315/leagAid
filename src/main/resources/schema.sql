-- ============================================
-- 海克斯大乱斗 AI 助手 - 建表脚本（7 张表）
-- MySQL 8，数据库: aramgg
-- ============================================

CREATE TABLE IF NOT EXISTS heroes (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    official_name VARCHAR(255),
    en_name VARCHAR(255),
    tier VARCHAR(255),
    win_rate DOUBLE,
    pick_rate DOUBLE,
    version VARCHAR(255),
    date VARCHAR(255),
    win_rank INT,
    image_url VARCHAR(500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS augments (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    en_name VARCHAR(255),
    rarity INT,
    tier_name VARCHAR(255),
    description VARCHAR(4000),
    tooltip VARCHAR(4000),
    enabled BIT,
    image_url VARCHAR(500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hero_augment_rank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hero_id INT,
    augment_id INT,
    tier VARCHAR(255),
    win_rank INT,
    total INT,
    win_rate DOUBLE,
    pick_rate DOUBLE,
    num_games BIGINT,
    num_win_games BIGINT,
    UNIQUE KEY uk_hero_augment (hero_id, augment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS items (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    en_name VARCHAR(255),
    description VARCHAR(4000),
    plaintext VARCHAR(2000),
    total_price INT,
    base_price INT,
    tags VARCHAR(1000),
    from_ids VARCHAR(500),
    into_ids VARCHAR(500),
    version VARCHAR(255),
    image_url VARCHAR(500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hero_item_build (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hero_id INT,
    build_index INT,
    group_index INT,
    slot INT,
    item_id INT,
    win_rate DOUBLE,
    pick_rate DOUBLE,
    UNIQUE KEY uk_build (hero_id, build_index, group_index, slot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hero_item_ext (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hero_id INT,
    item_id INT,
    ext_type VARCHAR(20),          -- situational=情境装备 / recommended=推荐装备
    slot INT,
    UNIQUE KEY uk_ext (hero_id, ext_type, slot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hero_profiles (
    hero_id INT PRIMARY KEY,
    title VARCHAR(100),
    tags VARCHAR(300),
    blurb TEXT,
    passive TEXT,
    spells TEXT,
    ally_tips TEXT,
    enemy_tips TEXT,
    version VARCHAR(255)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
