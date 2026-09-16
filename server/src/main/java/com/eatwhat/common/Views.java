package com.eatwhat.common;

import com.eatwhat.entity.AppUser;
import com.eatwhat.entity.Comment;
import com.eatwhat.entity.Dish;
import com.eatwhat.entity.Report;
import com.eatwhat.entity.Shop;
import com.eatwhat.service.DishService;
import com.eatwhat.service.RankService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体 → 前端视图对象的统一转换层。
 *
 * 为什么必须要有这一层：
 * 前端读的是**嵌套结构**（shop.stats.views、comment.reply.content、
 * user.comments），而实体是**扁平字段**（statViews、replyContent、commentCount）。
 * 直接返回实体不会报错 —— 页面只会静默显示空白或 undefined，
 * 这种 bug 极难排查。
 *
 * 更麻烦的是：三端（客户端 / 商家端 / 平台端）各写一份转换，
 * 一旦某端漏了字段，就会出现"客户端正常、平台端空白"这种诡异现象。
 * 所以统一收在这里，三端共用同一份结构定义。
 *
 * 字段顺序刻意对齐 assets/data/mock.js，这样后端模式与本地假数据模式
 * 出来的对象长得一模一样，页面代码不需要区分两种模式。
 */
@Component
public class Views {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DishService dishService;
    private final RankService rankService;

    public Views(DishService dishService, RankService rankService) {
        this.dishService = dishService;
        this.rankService = rankService;
    }

    // ==================== 店铺 ====================

    public Map<String, Object> shop(Shop s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("logo", s.getLogo());
        m.put("cover", s.getCover());
        m.put("address", s.getAddress());
        m.put("city", s.getCity());
        m.put("district", s.getDistrict());
        m.put("lat", s.getLat());
        m.put("lng", s.getLng());
        m.put("distance", s.getDistance());
        m.put("phone", s.getPhone());
        m.put("hours", s.getHours());
        m.put("cuisine", s.getCuisine());
        m.put("intro", s.getIntro());
        m.put("status", s.getStatus());
        m.put("weight", nz(s.getWeight()));
        m.put("pinned", Boolean.TRUE.equals(s.getPinned()));
        m.put("canPostToday", Boolean.TRUE.equals(s.getCanPostToday()));
        m.put("intervalHours", s.getIntervalHours() == null ? 24 : s.getIntervalHours());
        m.put("dailyLimit", s.getDailyLimit() == null ? 1 : s.getDailyLimit());
        // 前端读的是格式化好的时间字符串（mock 里就是 'yyyy-MM-dd HH:mm'）
        m.put("lastPostAt", fmtTs(s.getLastPublishAt()));

        // 审核痕迹 —— 平台端「商家审核」页要用
        m.put("submittedAt", s.getSubmittedAt());
        m.put("reviewedAt", s.getReviewedAt());
        m.put("reviewer", s.getReviewer());
        m.put("rejectReason", s.getRejectReason());

        Map<String, Object> stats = new LinkedHashMap<>();
        // 待审核的店还没内容，跳过这次查询
        stats.put("dishes", isPendingLike(s.getStatus()) ? 0 : dishService.listByShop(s.getId()).size());
        stats.put("views", nz(s.getStatViews()));
        stats.put("likes", nz(s.getStatLikes()));
        stats.put("favorites", nz(s.getStatFavorites()));
        stats.put("comments", nz(s.getStatComments()));
        stats.put("checkins", nz(s.getStatCheckins()));
        m.put("stats", stats);
        return m;
    }

    public List<Map<String, Object>> shops(List<Shop> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Shop s : list) out.add(shop(s));
        return out;
    }

    // ==================== 菜品 ====================

    public Map<String, Object> dish(Dish d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("shopId", d.getShopId());
        m.put("shopName", d.getShopName());
        m.put("name", d.getName());
        m.put("desc", d.getDescr());
        m.put("type", d.getType());
        m.put("media", strList(d.getMedia()));
        m.put("cover", d.getCover());
        m.put("videoUrl", d.getVideoUrl());
        m.put("price", d.getPrice());
        m.put("priceTierId", d.getPriceTierId());
        m.put("realTag", d.getRealTag());
        m.put("tasteTags", strList(d.getTasteTags()));
        m.put("publishedAt", d.getPublishedAt());
        m.put("status", d.getStatus());
        m.put("decay", rankService.timeDecay(d.getPublishedTs()));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("views", nz(d.getStatViews()));
        stats.put("likes", nz(d.getStatLikes()));
        stats.put("favorites", nz(d.getStatFavorites()));
        stats.put("comments", nz(d.getStatComments()));
        stats.put("checkins", nz(d.getStatCheckins()));
        m.put("stats", stats);
        return m;
    }

    public List<Map<String, Object>> dishes(List<Dish> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dish d : list) out.add(dish(d));
        return out;
    }

    // ==================== 评论 ====================

    public Map<String, Object> comment(Comment c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("dishId", c.getDishId());
        m.put("userId", c.getUserId());
        m.put("userName", c.getUserName());
        m.put("avatar", c.getAvatar());
        m.put("content", c.getContent());
        m.put("at", c.getAt());
        if (c.getReplyContent() != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("content", c.getReplyContent());
            r.put("at", c.getReplyAt());
            m.put("reply", r);
        } else {
            m.put("reply", null);
        }
        return m;
    }

    public List<Map<String, Object>> comments(List<Comment> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Comment c : list) out.add(comment(c));
        return out;
    }

    // ==================== 用户 ====================

    public Map<String, Object> user(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("name", u.getName());
        m.put("avatar", u.getAvatar());
        m.put("phone", u.getPhone());
        m.put("city", u.getCity());
        m.put("district", u.getDistrict());
        m.put("status", u.getStatus());
        // 前端字段名是 comments / reports，实体是 commentCount / reportCount
        m.put("comments", nz(u.getCommentCount()));
        m.put("reports", nz(u.getReportCount()));
        m.put("at", u.getAt());
        return m;
    }

    public List<Map<String, Object>> users(List<AppUser> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppUser u : list) out.add(user(u));
        return out;
    }

    // ==================== 举报 ====================

    public Map<String, Object> report(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("type", r.getType());
        m.put("targetId", r.getTargetId());
        m.put("targetName", r.getTargetName());
        m.put("reason", r.getReason());
        m.put("reporter", r.getReporter());
        m.put("at", r.getAt());
        m.put("status", r.getStatus());
        return m;
    }

    public List<Map<String, Object>> reports(List<Report> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Report r : list) out.add(report(r));
        return out;
    }

    // ==================== 工具 ====================

    /** 时间戳 → 'yyyy-MM-dd HH:mm'，与前端 mock 的格式保持一致 */
    public static String fmtTs(Long ts) {
        if (ts == null) return "";
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), CN).format(FMT);
    }

    /** JSON 字符串 → List&lt;String&gt;，脏数据一律当空列表，不让它把整页搞崩 */
    public static List<String> strList(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        try {
            return JSON.readValue(s, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static int nz(Integer i) { return i == null ? 0 : i; }

    /** 还没上线（待审核 / 已驳回）的店，没有内容也没有统计 */
    private static boolean isPendingLike(String status) {
        return "pending".equals(status) || "rejected".equals(status);
    }
}
