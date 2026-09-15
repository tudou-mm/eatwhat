package com.eatwhat.entity;

import jakarta.persistence.*;

/**
 * 店铺。
 * status: pending(待审核) | normal | muted(禁言) | banned(封店) | rejected(已驳回)
 * 发布规则：intervalHours + dailyLimit，平台可单店覆盖（默认 24 / 1）
 *
 * 审核字段说明：平台只审店铺、不审菜品（冻结规则第 8 条），
 * 所以 pending 是店铺维度的状态，审核通过前客户端完全看不到这家店。
 */
@Entity
@Table(name = "shop")
public class Shop {

    @Id
    private String id;

    private String name;
    private String cuisine;
    private String city;
    private String district;
    private String address;
    private String phone;
    private String hours;

    @Column(length = 500)
    private String intro;

    @Column(length = 500)
    private String cover;

    @Column(length = 500)
    private String logo;

    private Double lat;
    private Double lng;
    private Double distance;

    /** normal | muted | banned | pending | rejected */
    private String status = "normal";

    /** 商家提交审核时间，'yyyy-MM-dd HH:mm'，与前端展示格式一致 */
    private String submittedAt;

    /** 平台审核时间 */
    private String reviewedAt;

    /** 审核人 */
    private String reviewer;

    /** 驳回理由，仅在 status=rejected 时有值 */
    @Column(length = 500)
    private String rejectReason;

    /** 今日是否还能发（由发布时间动态算，这里缓存一份给列表提速） */
    private Boolean canPostToday = true;

    /** 推荐权重，越大越靠前 */
    private Integer weight = 0;

    /** 是否置顶（置顶优先级最高） */
    private Boolean pinned = false;

    /** 发布间隔小时，默认 24 */
    private Integer intervalHours = 24;

    /** 每日发布上限，默认 1 */
    private Integer dailyLimit = 1;

    /** 最后发布时间戳（毫秒），用于冷却判断 */
    private Long lastPublishAt;

    private Integer statViews = 0;
    private Integer statLikes = 0;
    private Integer statFavorites = 0;
    private Integer statComments = 0;
    private Integer statCheckins = 0;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCuisine() { return cuisine; }
    public void setCuisine(String cuisine) { this.cuisine = cuisine; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getHours() { return hours; }
    public void setHours(String hours) { this.hours = hours; }
    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public String getLogo() { return logo; }
    public void setLogo(String logo) { this.logo = logo; }
    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String submittedAt) { this.submittedAt = submittedAt; }
    public String getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(String reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewer() { return reviewer; }
    public void setReviewer(String reviewer) { this.reviewer = reviewer; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Boolean getCanPostToday() { return canPostToday; }
    public void setCanPostToday(Boolean canPostToday) { this.canPostToday = canPostToday; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public Boolean getPinned() { return pinned; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }
    public Integer getIntervalHours() { return intervalHours; }
    public void setIntervalHours(Integer intervalHours) { this.intervalHours = intervalHours; }
    public Integer getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(Integer dailyLimit) { this.dailyLimit = dailyLimit; }
    public Long getLastPublishAt() { return lastPublishAt; }
    public void setLastPublishAt(Long lastPublishAt) { this.lastPublishAt = lastPublishAt; }
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
