package com.eatwhat.entity;

import jakarta.persistence.*;
import java.util.List;

/**
 * 平台配置（键值对）。
 * 存价格档 JSON、口味标签 JSON、菜系 JSON —— 单行表，永远只有 id=1 一条。
 */
@Entity
@Table(name = "platform_config")
public class PlatformConfig {

    @Id
    private Long id = 1L;

    /** 价格档 JSON：[{id,name,min,max}] */
    @Column(length = 2000)
    private String priceTiers;

    /** 口味标签 JSON：["麻辣","清淡",...] */
    @Column(length = 2000)
    private String tasteTags;

    /** 菜系 JSON：["川菜",...] */
    @Column(length = 2000)
    private String cuisines;

    /** 全局默认发布间隔小时 */
    private Integer defaultIntervalHours = 24;

    /** 全局默认日上限 */
    private Integer defaultDailyLimit = 1;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPriceTiers() { return priceTiers; }
    public void setPriceTiers(String priceTiers) { this.priceTiers = priceTiers; }
    public String getTasteTags() { return tasteTags; }
    public void setTasteTags(String tasteTags) { this.tasteTags = tasteTags; }
    public String getCuisines() { return cuisines; }
    public void setCuisines(String cuisines) { this.cuisines = cuisines; }
    public Integer getDefaultIntervalHours() { return defaultIntervalHours; }
    public void setDefaultIntervalHours(Integer defaultIntervalHours) { this.defaultIntervalHours = defaultIntervalHours; }
    public Integer getDefaultDailyLimit() { return defaultDailyLimit; }
    public void setDefaultDailyLimit(Integer defaultDailyLimit) { this.defaultDailyLimit = defaultDailyLimit; }
}
