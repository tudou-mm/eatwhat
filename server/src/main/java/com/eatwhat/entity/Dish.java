package com.eatwhat.entity;

import jakarta.persistence.*;

/**
 * 菜品（一条内容）。
 * 核心规则：type 一旦发布不可更改（image | video）。
 * status: normal | removed(平台下架，但不物理删除)
 */
@Entity
@Table(name = "dish")
public class Dish {

    @Id
    private String id;

    private String shopId;
    private String shopName;

    private String name;

    @Column(length = 500)
    private String descr;

    /** image | video —— 发布后不可改 */
    private String type;

    /** 图片/视频地址，JSON 数组字符串 */
    @Column(length = 2000)
    private String media;

    @Column(length = 500)
    private String cover;

    /**
     * 视频菜品的真实视频地址（type=video 时才有）。
     *
     * 为什么不直接塞进 media：客户端的图片画廊是把 media 每一项当 &lt;img&gt; 渲染的，
     * 放一个 .mp4 进去就是一堆裂图。video 类型的 media 存的是**封面图**，
     * 真视频单独放这里。客户端播放视频的能力还没做（见 docs/00 已知缺口）。
     */
    @Column(length = 500)
    private String videoUrl;

    private Double price;

    /** 系统按 price 自动归档出来的档位 id，商家不可手选 */
    private String priceTierId;

    /** real(实拍认证) | ad(广告) | pending(待核) */
    private String realTag = "pending";

    /** 口味标签，JSON 数组字符串 */
    @Column(length = 500)
    private String tasteTags;

    /** 展示用时间字符串 */
    private String publishedAt;

    /** 发布时间戳（毫秒），排序与衰减用 */
    private Long publishedTs;

    /** normal | removed */
    private String status = "normal";

    private Integer statViews = 0;
    private Integer statLikes = 0;
    private Integer statFavorites = 0;
    private Integer statComments = 0;
    private Integer statCheckins = 0;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getShopId() { return shopId; }
    public void setShopId(String shopId) { this.shopId = shopId; }
    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescr() { return descr; }
    public void setDescr(String descr) { this.descr = descr; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getMedia() { return media; }
    public void setMedia(String media) { this.media = media; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }

    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public String getPriceTierId() { return priceTierId; }
    public void setPriceTierId(String priceTierId) { this.priceTierId = priceTierId; }
    public String getRealTag() { return realTag; }
    public void setRealTag(String realTag) { this.realTag = realTag; }
    public String getTasteTags() { return tasteTags; }
    public void setTasteTags(String tasteTags) { this.tasteTags = tasteTags; }
    public String getPublishedAt() { return publishedAt; }
    public void setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }
    public Long getPublishedTs() { return publishedTs; }
    public void setPublishedTs(Long publishedTs) { this.publishedTs = publishedTs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getStatViews() { return statViews; }
    public void setStatViews(Integer statViews) { this.statViews = statViews; }
    public Integer getStatLikes() { return statLikes; }
    public void setStatLikes(Integer statLikes) { this.statLikes = statLikes; }
    public Integer getStatFavorites() { return statFavorites; }
    public void setStatFavorites(Integer statFavorites) { this.statFavorites = statFavorites; }
    public Integer getStatComments() { return statComments; }
    public void setStatComments(Integer statComments) { this.statComments = statComments; }
    public Integer getStatCheckins() { return statCheckins; }
    public void setStatCheckins(Integer statCheckins) { this.statCheckins = statCheckins; }
}
