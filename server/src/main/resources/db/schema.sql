-- ============================================================
-- 「吃什么」本地生活推荐平台 · 建表脚本（MySQL 8.0+）
-- 说明：H2 模式下 JPA 会自动建表，此脚本仅给 MySQL 用户使用。
-- ============================================================

CREATE DATABASE IF NOT EXISTS eatwhat DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE eatwhat;

-- ---------- 店铺 ----------
DROP TABLE IF EXISTS shop;
CREATE TABLE shop (
  id                  VARCHAR(64)  NOT NULL COMMENT '店铺ID',
  name                VARCHAR(128) NOT NULL COMMENT '店铺名称',
  cuisine             VARCHAR(64)           COMMENT '菜系',
  city                VARCHAR(64)           COMMENT '城市',
  district            VARCHAR(64)           COMMENT '区县',
  address             VARCHAR(255)          COMMENT '详细地址',
  phone               VARCHAR(32)           COMMENT '联系电话',
  hours               VARCHAR(64)           COMMENT '营业时间',
  intro               VARCHAR(500)          COMMENT '店铺简介',
  cover               VARCHAR(500)          COMMENT '门头图',
  logo                VARCHAR(500)          COMMENT '店铺logo',
  lat                 DOUBLE                COMMENT '纬度',
  lng                 DOUBLE                COMMENT '经度',
  distance            DOUBLE                COMMENT '距离(km，演示值)',
  status              VARCHAR(16)  DEFAULT 'normal' COMMENT 'pending|normal|muted|banned|rejected',
  submitted_at        VARCHAR(32)           COMMENT '商家提交审核时间',
  reviewed_at         VARCHAR(32)           COMMENT '平台审核时间',
  reviewer            VARCHAR(64)           COMMENT '审核人',
  reject_reason       VARCHAR(500)          COMMENT '驳回理由',
  can_post_today      BIT(1)       DEFAULT b'1'     COMMENT '今日是否可发',
  weight              INT          DEFAULT 0        COMMENT '推荐权重',
  pinned              BIT(1)       DEFAULT b'0'     COMMENT '是否置顶',
  interval_hours      INT          DEFAULT 24       COMMENT '发布间隔(小时)',
  daily_limit         INT          DEFAULT 1        COMMENT '每日发布上限',
  last_publish_at     BIGINT                COMMENT '最后发布时间戳(ms)',
  stat_views          INT          DEFAULT 0,
  stat_likes          INT          DEFAULT 0,
  stat_favorites      INT          DEFAULT 0,
  stat_comments       INT          DEFAULT 0,
  stat_checkins       INT          DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_city (city),
  KEY idx_status (status),
  KEY idx_weight (weight DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺';

-- ---------- 菜品 ----------
DROP TABLE IF EXISTS dish;
CREATE TABLE dish (
  id              VARCHAR(64)  NOT NULL COMMENT '菜品ID',
  shop_id         VARCHAR(64)  NOT NULL COMMENT '所属店铺',
  shop_name       VARCHAR(128)          COMMENT '店铺名（冗余，便于列表展示）',
  name            VARCHAR(128) NOT NULL COMMENT '菜品名称',
  descr           VARCHAR(500)          COMMENT '简介(≤100字)',
  type            VARCHAR(16)  NOT NULL COMMENT 'image|video，发布后不可改',
  media           VARCHAR(2000)         COMMENT '图片/视频地址 JSON 数组',
  cover           VARCHAR(500)          COMMENT '封面',
  price           DOUBLE                COMMENT '价格',
  price_tier_id   VARCHAR(64)           COMMENT '系统自动归档的档位ID',
  real_tag        VARCHAR(16)  DEFAULT 'pending' COMMENT 'real|ad|pending',
  taste_tags      VARCHAR(500)          COMMENT '口味标签 JSON 数组',
  published_at    VARCHAR(32)           COMMENT '展示时间',
  published_ts    BIGINT                COMMENT '发布时间戳(ms)',
  status          VARCHAR(16)  DEFAULT 'normal' COMMENT 'normal|removed',
  stat_views      INT          DEFAULT 0,
  stat_likes      INT          DEFAULT 0,
  stat_favorites  INT          DEFAULT 0,
  stat_comments   INT          DEFAULT 0,
  stat_checkins   INT          DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_shop (shop_id),
  KEY idx_published (published_ts DESC),
  KEY idx_tier (price_tier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品（内容）';

-- ---------- 评论 ----------
DROP TABLE IF EXISTS comment;
CREATE TABLE comment (
  id              VARCHAR(64)   NOT NULL,
  dish_id         VARCHAR(64)   NOT NULL,
  user_id         VARCHAR(64)   NOT NULL,
  user_name       VARCHAR(64),
  avatar          VARCHAR(500),
  content         VARCHAR(1000) NOT NULL,
  at              VARCHAR(32),
  at_ts           BIGINT,
  reply_content   VARCHAR(1000) COMMENT '商家回评',
  reply_at        VARCHAR(32),
  status          VARCHAR(16) DEFAULT 'normal' COMMENT 'normal|removed',
  PRIMARY KEY (id),
  KEY idx_dish (dish_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论';

-- ---------- 用户 ----------
DROP TABLE IF EXISTS app_user;
CREATE TABLE app_user (
  id              VARCHAR(64)  NOT NULL,
  name            VARCHAR(64),
  avatar          VARCHAR(500),
  phone           VARCHAR(32),
  city            VARCHAR(64),
  district        VARCHAR(64),
  status          VARCHAR(16) DEFAULT 'normal' COMMENT 'normal|warned|banned',
  comment_count   INT DEFAULT 0,
  report_count    INT DEFAULT 0 COMMENT '被举报次数，≥3 自动警告',
  at              VARCHAR(32) COMMENT '注册日期',
  PRIMARY KEY (id),
  UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ---------- 举报 ----------
DROP TABLE IF EXISTS report;
CREATE TABLE report (
  id            VARCHAR(64)  NOT NULL,
  type          VARCHAR(16)  NOT NULL COMMENT 'dish|comment',
  target_id     VARCHAR(64)  NOT NULL,
  target_name   VARCHAR(255),
  reason        VARCHAR(255),
  reporter      VARCHAR(128),
  at            VARCHAR(32),
  status        VARCHAR(16) DEFAULT 'pending' COMMENT 'pending|confirmed|rejected',
  PRIMARY KEY (id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报';

-- ---------- 平台配置 ----------
DROP TABLE IF EXISTS platform_config;
CREATE TABLE platform_config (
  id                      BIGINT NOT NULL DEFAULT 1,
  price_tiers             VARCHAR(2000) COMMENT '价格档 JSON',
  taste_tags              VARCHAR(2000) COMMENT '口味标签 JSON',
  cuisines                VARCHAR(2000) COMMENT '菜系 JSON',
  default_interval_hours  INT DEFAULT 24,
  default_daily_limit     INT DEFAULT 1,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台配置（单行表）';

-- 初始配置
INSERT INTO platform_config (id, price_tiers, taste_tags, cuisines, default_interval_hours, default_daily_limit)
VALUES (1,
  '[{"id":"tier_mid","name":"中等","min":0,"max":50},{"id":"tier_high","name":"高等","min":50,"max":150},{"id":"tier_ultra","name":"超高级","min":150,"max":-1}]',
  '["麻辣","清淡","烧烤","日料","面食","小吃","甜点"]',
  '["川菜","火锅","烧烤","日料","面食","小吃","西餐","甜品","粤菜","湘菜"]',
  24, 1);
