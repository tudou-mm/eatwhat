package com.eatwhat.controller;

import com.eatwhat.common.R;
import com.eatwhat.entity.Dish;
import com.eatwhat.entity.Shop;
import com.eatwhat.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 客户端接口（食客侧）。
 * 免登录可浏览；评论、打卡需带 userId。
 */
@RestController
@RequestMapping("/api/client")
public class ClientController {

    private final ShopService shopService;
    private final DishService dishService;
    private final CommentService commentService;
    private final RankService rankService;
    private final ConfigService configService;

    public ClientController(ShopService shopService, DishService dishService,
                            CommentService commentService, RankService rankService,
                            ConfigService configService) {
        this.shopService = shopService;
        this.dishService = dishService;
        this.commentService = commentService;
        this.rankService = rankService;
        this.configService = configService;
    }

    /**
     * 启动聚合接口：一次返回客户端各页面所需的基础数据。
     * ------------------------------------------------------------------
     * 为什么需要它：
     * 适配层做的是「同步预加载」，请求数直接换算成页面白屏时长。
     * 分散拉取要走 1(店铺)+1(配置)+N(各店菜品)+M(各菜评论) 次请求，
     * 店铺一多就会明显卡首屏。聚合成 1 次请求，首屏稳定。
     * 顺带修正语义 —— 适配层原先调的是平台端的 /admin/shops。
     *
     * 注意：这里必须用 shopView / dishView 转换，
     * 不能用原始实体 —— 前端读的是 stats.xxx 嵌套结构，
     * 实体是扁平的 statXxx 字段，直接返回页面会取不到值。
     */
    @GetMapping("/bootstrap")
    public R<Map<String, Object>> bootstrap() {
        Map<String, Object> m = new LinkedHashMap<>();

        List<Map<String, Object>> shops = new ArrayList<>();
        for (Shop s : shopService.listVisible()) shops.add(shopView(s));
        m.put("shops", shops);

        List<Map<String, Object>> dishes = new ArrayList<>();
        for (Dish d : dishService.listByStatus("normal")) dishes.add(dishView(d));
        m.put("dishes", dishes);

        List<Map<String, Object>> comments = new ArrayList<>();
        for (com.eatwhat.entity.Comment c : commentService.listAll()) comments.add(commentView(c));
        m.put("comments", comments);

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("priceTiers", configService.priceTiers());
        cfg.put("tasteTags", configService.tasteTags());
        cfg.put("cuisines", configService.cuisines());
        m.put("config", cfg);

        return R.ok(m);
    }

    /**
     * 信息流。tab = nearby | random
     * tiers / tastes 可选，传了就按筛选条件出流。
     *
     * 筛选态下的两条关键差异：
     * 1. 每店取「符合筛选条件的最新一条」——而不是笼统的最新一条，
     *    否则筛"麻辣"时，某店最新菜不辣就会被整店剔除，
     *    即使店里更早有辣的菜。
     * 2. 不做内容补位 —— 补位会把不符合条件的菜塞回来，筛选失效。
     */
    @GetMapping("/feed")
    public R<List<Map<String, Object>>> feed(@RequestParam(defaultValue = "nearby") String tab,
                                              @RequestParam(required = false) String city,
                                              @RequestParam(required = false) String browsed,
                                              @RequestParam(required = false) List<String> tiers,
                                              @RequestParam(required = false) List<String> tastes) {
        boolean filtered = (tiers != null && !tiers.isEmpty())
                        || (tastes != null && !tastes.isEmpty());

        // 取每家店铺的最新一条菜品（首页每店只露一条，这是冻结规则）
        Map<String, Dish> latestByShop = new HashMap<>();
        for (Dish d : dishService.listNormal()) {
            if (filtered && !matchFilter(d, tiers, tastes)) continue;
            Dish cur = latestByShop.get(d.getShopId());
            boolean newer = cur == null
                    || (d.getPublishedTs() != null && cur.getPublishedTs() != null
                        && d.getPublishedTs() > cur.getPublishedTs());
            if (newer) latestByShop.put(d.getShopId(), d);
        }

        List<Dish> feed;
        if ("random".equals(tab)) {
            Set<String> browsedSet = new HashSet<>();
            if (browsed != null && !browsed.isBlank()) browsedSet.addAll(Arrays.asList(browsed.split(",")));
            feed = rankService.buildRandomFeed(new ArrayList<>(latestByShop.values()), browsedSet);
        } else {
            feed = rankService.buildNearbyFeed(city, latestByShop);
        }

        // 内容供给不足时补位（冻结规则 D2）。
        // 注意：只补「尚未出现在 feed 里的店铺」的历史内容，
        // 否则同一家店会有两条内容同时占据首页，破坏"每店一条"。
        if (!filtered && feed.size() < 3) {
            feed = new ArrayList<>(feed);
            Set<String> usedShops = new HashSet<>();
            for (Dish d : feed) usedShops.add(d.getShopId());

            List<Dish> all = new ArrayList<>(dishService.listNormal());
            all.sort((a, b) -> Long.compare(
                    b.getPublishedTs() == null ? 0 : b.getPublishedTs(),
                    a.getPublishedTs() == null ? 0 : a.getPublishedTs()));

            for (Dish d : all) {
                if (feed.size() >= 3) break;
                if (usedShops.contains(d.getShopId())) continue;
                feed.add(d);
                usedShops.add(d.getShopId());
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Dish d : feed) out.add(dishView(d));
        return R.ok(out);
    }

    /** 店铺详情 */
    @GetMapping("/shop/{id}")
    public R<Map<String, Object>> shop(@PathVariable String id) {
        Shop s = shopService.get(id);
        Map<String, Object> m = shopView(s);
        m.put("dishes", dishService.listByShop(id).stream().map(this::dishView).toList());
        return R.ok(m);
    }

    /** 某店铺的全部菜品（适配层预加载用） */
    @GetMapping("/shop/{id}/dishes")
    public R<List<Map<String, Object>>> shopDishes(@PathVariable String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dish d : dishService.listByShop(id)) out.add(dishView(d));
        return R.ok(out);
    }

    /** 菜品详情 */
    @GetMapping("/dish/{id}")
    public R<Map<String, Object>> dish(@PathVariable String id) {
        return R.ok(dishView(dishService.get(id)));
    }

    /** 菜品评论列表 */
    @GetMapping("/dish/{id}/comments")
    public R<List<Map<String, Object>>> comments(@PathVariable String id) {
        return R.ok(commentService.listByDish(id).stream().map(this::commentView).toList());
    }

    /** 发表评论 */
    @PostMapping("/dish/{id}/comment")
    public R<Map<String, Object>> addComment(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.get("userId"));
        String content = body.get("content") == null ? "" : body.get("content").toString();
        return R.ok(commentView(commentService.add(id, userId, content)));
    }

    /** 点赞 / 收藏 / 打卡 */
    @PostMapping("/dish/{id}/action")
    public R<Void> action(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.get("type"));
        switch (type) {
            case "like" -> dishService.incStat(id, "likes");
            case "favorite" -> dishService.incStat(id, "favorites");
            case "view" -> dishService.incStat(id, "views");
            case "checkin" -> dishService.incCheckin(id);
            default -> { }
        }
        return R.ok();
    }

    /**
     * 筛选结果明细：返回**全部**符合条件的菜品，不做"每店一条"去重。
     * 维度内部 OR（多选口味命中任一即可），维度之间 AND。
     * 若要"每店一条"的筛选信息流，用 GET /feed?tiers=..&tastes=..
     */
    @PostMapping("/filter")
    public R<List<Map<String, Object>>> filter(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> tiers = (List<String>) body.getOrDefault("tiers", new ArrayList<>());
        @SuppressWarnings("unchecked")
        List<String> tastes = (List<String>) body.getOrDefault("tastes", new ArrayList<>());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Dish d : dishService.listNormal()) {
            if (matchFilter(d, tiers, tastes)) out.add(dishView(d));
        }
        return R.ok(out);
    }

    /** 客户端基础配置：价格档 + 口味标签（前端筛选页要用） */
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        Map<String, Object> m = new HashMap<>();
        m.put("priceTiers", configService.priceTiers());
        m.put("tasteTags", configService.tasteTags());
        return R.ok(m);
    }

    // ==================== 视图转换 ====================

    private Map<String, Object> dishView(Dish d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("shopId", d.getShopId());
        m.put("shopName", d.getShopName());
        m.put("name", d.getName());
        m.put("desc", d.getDescr());
        m.put("type", d.getType());
        m.put("media", jsonList(d.getMedia()));
        m.put("cover", d.getCover());
        m.put("price", d.getPrice());
        m.put("priceTierId", d.getPriceTierId());
        m.put("realTag", d.getRealTag());
        m.put("tasteTags", jsonList(d.getTasteTags()));
        m.put("publishedAt", d.getPublishedAt());
        m.put("status", d.getStatus());
        m.put("decay", rankService.timeDecay(d.getPublishedTs()));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("views", d.getStatViews());
        stats.put("likes", d.getStatLikes());
        stats.put("favorites", d.getStatFavorites());
        stats.put("comments", d.getStatComments());
        stats.put("checkins", d.getStatCheckins());
        m.put("stats", stats);
        return m;
    }

    private Map<String, Object> shopView(Shop s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("cuisine", s.getCuisine());
        m.put("city", s.getCity());
        m.put("district", s.getDistrict());
        m.put("address", s.getAddress());
        m.put("phone", s.getPhone());
        m.put("hours", s.getHours());
        m.put("intro", s.getIntro());
        m.put("cover", s.getCover());
        m.put("logo", s.getLogo());
        m.put("lat", s.getLat());
        m.put("lng", s.getLng());
        m.put("distance", s.getDistance());
        m.put("status", s.getStatus());
        m.put("pinned", s.getPinned());
        m.put("weight", s.getWeight());
        m.put("intervalHours", s.getIntervalHours());
        m.put("dailyLimit", s.getDailyLimit());
        m.put("canPostToday", s.getCanPostToday());
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("dishes", dishService.listByShop(s.getId()).size());
        stats.put("views", s.getStatViews());
        stats.put("likes", s.getStatLikes());
        stats.put("favorites", s.getStatFavorites());
        stats.put("comments", s.getStatComments());
        stats.put("checkins", s.getStatCheckins());
        m.put("stats", stats);
        // 前端读的是格式化好的时间字符串（mock 里就是 'yyyy-MM-dd HH:mm'）
        m.put("lastPostAt", fmtTs(s.getLastPublishAt()));
        return m;
    }

    /** 时间戳 → 'yyyy-MM-dd HH:mm'，与前端 mock 的格式保持一致 */
    private String fmtTs(Long ts) {
        if (ts == null) return "";
        return java.time.LocalDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(ts), java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private Map<String, Object> commentView(com.eatwhat.entity.Comment c) {
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

    private List<String> jsonList(String s) {
        try {
            if (s == null || s.isBlank()) return new ArrayList<>();
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(s, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private List<String> tasteList(Dish d) { return jsonList(d.getTasteTags()); }

    /**
     * 单条菜品是否命中筛选条件。
     * 维度内部 OR（多选口味命中任一即可），维度之间 AND
     * （价格档与口味都要满足）。null / 空集合表示该维度不限。
     * —— 与 docs/05-后端接口契约.md 的口径一致。
     */
    private boolean matchFilter(Dish d, List<String> tiers, List<String> tastes) {
        boolean tierOk = tiers == null || tiers.isEmpty() || tiers.contains(d.getPriceTierId());
        boolean tasteOk = tastes == null || tastes.isEmpty() || hasAny(tasteList(d), tastes);
        return tierOk && tasteOk;
    }

    private boolean hasAny(List<String> a, List<String> b) {
        for (String s : a) if (b.contains(s)) return true;
        return false;
    }
}
