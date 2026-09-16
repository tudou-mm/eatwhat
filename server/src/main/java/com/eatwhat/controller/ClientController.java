package com.eatwhat.controller;

import com.eatwhat.auth.AuthContext;
import com.eatwhat.common.BizException;
import com.eatwhat.common.R;
import com.eatwhat.common.Views;
import com.eatwhat.entity.Dish;
import com.eatwhat.entity.Shop;
import com.eatwhat.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 客户端接口（食客侧）。
 * 免登录可浏览；评论、打卡需带 userId。
 *
 * 出参一律过 {@link Views}，不直接返回实体 ——
 * 前端读的是嵌套结构（stats.views），实体是扁平字段（statViews），
 * 直接返回实体页面只会静默显示空白。
 */
@RestController
@RequestMapping("/api/client")
public class ClientController {

    private final ShopService shopService;
    private final DishService dishService;
    private final CommentService commentService;
    private final RankService rankService;
    private final ConfigService configService;
    private final Views views;

    public ClientController(ShopService shopService, DishService dishService,
                            CommentService commentService, RankService rankService,
                            ConfigService configService, Views views) {
        this.shopService = shopService;
        this.dishService = dishService;
        this.commentService = commentService;
        this.rankService = rankService;
        this.configService = configService;
        this.views = views;
    }

    /**
     * 启动聚合接口：一次返回客户端各页面所需的基础数据。
     * ------------------------------------------------------------------
     * 为什么需要它：
     * 适配层做的是「同步预加载」，请求数直接换算成页面白屏时长。
     * 分散拉取要走 1(店铺)+1(配置)+N(各店菜品)+M(各菜评论) 次请求，
     * 店铺一多就会明显卡首屏。聚合成 1 次请求，首屏稳定。
     * 顺带修正语义 —— 适配层原先调的是平台端的 /admin/shops。
     */
    @GetMapping("/bootstrap")
    public R<Map<String, Object>> bootstrap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shops", views.shops(shopService.listVisible()));
        m.put("dishes", views.dishes(dishService.listByStatus("normal")));
        m.put("comments", views.comments(commentService.listAll()));

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

        return R.ok(views.dishes(feed));
    }

    /** 店铺详情 */
    @GetMapping("/shop/{id}")
    public R<Map<String, Object>> shop(@PathVariable String id) {
        Shop s = shopService.get(id);
        Map<String, Object> m = views.shop(s);
        m.put("dishes", dishService.listByShop(id).stream().map(views::dish).toList());
        return R.ok(m);
    }

    /** 某店铺的全部菜品（适配层预加载用） */
    @GetMapping("/shop/{id}/dishes")
    public R<List<Map<String, Object>>> shopDishes(@PathVariable String id) {
        return R.ok(views.dishes(dishService.listByShop(id)));
    }

    /** 菜品详情 */
    @GetMapping("/dish/{id}")
    public R<Map<String, Object>> dish(@PathVariable String id) {
        return R.ok(views.dish(dishService.get(id)));
    }

    /** 菜品评论列表 */
    @GetMapping("/dish/{id}/comments")
    public R<List<Map<String, Object>>> comments(@PathVariable String id) {
        return R.ok(commentService.listByDish(id).stream().map(views::comment).toList());
    }

    /**
     * 发表评论。
     *
     * ⚠️ **身份一律取自 token，不看请求体里的 userId**。
     * 之前是直接 `body.get("userId")` 拿去用 —— 那意味着任何人把 userId 改成别人
     * 就能以别人名义评论；被封号的用户换个 id 也照发不误，封号等于没封。
     * 现在这个接口由 AuthInterceptor 要求 client 身份（见 {@code requiredRole}），
     * 这里再从上下文取 subject 当下单用户。
     *
     * 请求体里若仍带着 userId，**直接忽略**（不报错）：老的调用方不至于因此挂掉。
     */
    @PostMapping("/dish/{id}/comment")
    public R<Map<String, Object>> addComment(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        AuthContext.Principal me = AuthContext.get();
        if (me == null || !"client".equals(me.role()) || me.subject() == null) {
            throw new BizException(401, "请先登录后再发表评论");
        }
        String content = body.get("content") == null ? "" : body.get("content").toString();
        // CommentService.add 里还有一道封号校验，两处都不能省
        return R.ok(views.comment(commentService.add(id, me.subject(), content)));
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

        List<Dish> hit = new ArrayList<>();
        for (Dish d : dishService.listNormal()) {
            if (matchFilter(d, tiers, tastes)) hit.add(d);
        }
        return R.ok(views.dishes(hit));
    }

    /** 客户端基础配置：价格档 + 口味标签（前端筛选页要用） */
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        Map<String, Object> m = new HashMap<>();
        m.put("priceTiers", configService.priceTiers());
        m.put("tasteTags", configService.tasteTags());
        return R.ok(m);
    }

    // ==================== 筛选条件 ====================

    /**
     * 单条菜品是否命中筛选条件。
     * 维度内部 OR（多选口味命中任一即可），维度之间 AND
     * （价格档与口味都要满足）。null / 空集合表示该维度不限。
     * —— 与 docs/05-后端接口契约.md 的口径一致。
     */
    private boolean matchFilter(Dish d, List<String> tiers, List<String> tastes) {
        boolean tierOk = tiers == null || tiers.isEmpty() || tiers.contains(d.getPriceTierId());
        boolean tasteOk = tastes == null || tastes.isEmpty() || hasAny(Views.strList(d.getTasteTags()), tastes);
        return tierOk && tasteOk;
    }

    private boolean hasAny(List<String> a, List<String> b) {
        for (String s : a) if (b.contains(s)) return true;
        return false;
    }
}
