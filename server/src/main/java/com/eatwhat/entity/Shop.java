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

    /**
     * 店铺资料最后修改时间，'yyyy-MM-dd HH:mm'（商家保存资料时刷新）。
     *
     * <p><b>为什么必须单独有一个字段</b>：客户端首屏是**限量**的
     * （见 {@code ClientController.BOOTSTRAP_SHOP_LIMIT}），只按距离取前 200 家。
     * 这会让「商家改完资料，去客户端刷新就能看到」这个产品保证失效 ——
     * 一家排在 200 名之外的店改完简介，客户端看到的还是旧的。
     *
     * <p>不能复用 {@code reviewedAt}：那是**审核**动作的时间。
     * 商家每天改资料都会刷新它，会让「刚上线的新店」和「老店改了个字」
     * 混成同一类，审核队列的语义就脏了。
     */
    private String updatedAt;

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

    /**
     * 高德原始品类（采集来的，如「小吃快餐」「餐饮相关」）。
     *
     * 保留它是因为**我们的 cuisine 是映射后的结果，映射必然有损失** ——
     * 店主认领后要改品类时，得有个「原来标的是什么」作参照；
     * 运营排查「为什么这家被归成中餐」时也要看得到源头。
     */
    private String amapCuisine;

    /**
     * 品类置信度：high | low。
     *
     * 高德的品类标注有粗有细 —— 「川菜」「火锅」这种是明确的（high），
     * 但「中餐厅」「餐饮相关」这种粗桶只能兜底成「中餐」（low）。
     * 实测 low 占了 47.5%，**这不是数据质量问题，是数据源的真实边界**。
     *
     * 产品上用它的地方：
     * <ul>
     *   <li>筛选器把 low 的「中餐」折叠或放最后，别污染主体验</li>
     *   <li>店主认领后提示「请确认你的菜系」—— 让一手信息来修正</li>
     * </ul>
     */
    private String cuisineConfidence = "high";

    /** 最后发布时间戳（毫秒），用于冷却判断 */
    private Long lastPublishAt;

    /**
     * 登录态版本号。签发的 JWT 里带着它，每次请求比对一次 —— 对不上就是 401。
     *
     * 用途：**让已经发出去的 token 立即作废**。
     * 封店、改密码、主动登出时 +1，商家那边还没过期的旧 token 立刻就失效了。
     * 没有它的话，封了店人家照样能用旧 token 用到 168 小时后过期为止。
     */
    private Integer tokenVersion = 0;

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
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
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
    public String getAmapCuisine() { return amapCuisine; }
    public void setAmapCuisine(String amapCuisine) { this.amapCuisine = amapCuisine; }
    public String getCuisineConfidence() { return cuisineConfidence; }
    public void setCuisineConfidence(String cuisineConfidence) { this.cuisineConfidence = cuisineConfidence; }
    public Long getLastPublishAt() { return lastPublishAt; }
    public void setLastPublishAt(Long lastPublishAt) { this.lastPublishAt = lastPublishAt; }
    public Integer getTokenVersion() { return tokenVersion == null ? 0 : tokenVersion; }
    public void setTokenVersion(Integer tokenVersion) { this.tokenVersion = tokenVersion; }
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
