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
     *
     * <p><b>⚠️ 为什么必须限量（血泪）</b>：这个接口原先返回**全量**，
     * 在 22 家演示数据下是 100KB 左右，看不出问题。
     * 数据扩到 1620 家真实店铺后，响应体涨到 **5.16 MB**（其中菜品占 80%）——
     * 手机上要下 5MB JSON 才能出首屏，而且服务端频繁抛
     * {@code ClientAbortException}（客户端读不完就断开，curl 表现为
     * {@code HTTP=200 但 SIZE=0}，看着像「接口正常」）。
     *
     * <p>现在按「先按距离排，再取前 {@link #BOOTSTRAP_SHOP_LIMIT} 家」截断。
     * 城市里同时开着的店本来就看不完 200 家，这个量足够撑起首屏和滑动。
     * 明确要看更远/更多的场景走 {@code /feed}（已支持分页）。
     *
     * <p>菜品**只取这些店的**（不是全量菜品再过滤）—— 后者是 6944 条，
     * 即使店铺截断了也还是 4MB，等于没优化。
     *
     * <p><b>⚠️⚠️ 铁律：dishes 的 shopId 必须全部能在 shops 里找到（血泪）</b>
     * <p>曾经在这里写成「店铺取前 200 家 <b>或</b> 菜品在 24h 内发布」——
     * 两个集合各取各的，结果 2320 条菜里有 **1415 条的店不在返回的 200 家里**。
     * 后果是**前端排序被打乱**（不是报错，是静默乱序）：
     * 前端 {@code buildFeed} 排序时对找不到店的菜 {@code return 0}（保持原序），
     * 这些「孤儿菜」混在数组里，把正常店的相邻关系打散，
     * 于是同一权重档内出现「1.0km 排在 0.4km 前面」。
     * 浏览器测试 {@code drive_user_loc} 的「同权重组内距离递增」就是这么挂的，
     * 排查时先怀疑了距离口径、又怀疑了 weight 类型，最后才定位到集合不闭合。
     *
     * <p>正确做法：**由店铺范围决定菜品范围**。要让 24h 新菜可见，
     * 就得把它的**店**也拉进店铺集合，而不是只把菜塞进来。
     */
    @GetMapping("/bootstrap")
    public R<Map<String, Object>> bootstrap(@RequestParam(required = false) String city) {
        List<Shop> visible = shopService.listVisible();
        // 按「距离 → 权重 → 置顶」排，保证取到的是用户真正最可能看的店
        List<Shop> picked = rankService.topShopsByDistance(visible, BOOTSTRAP_SHOP_LIMIT, city);

        Set<String> ids = new HashSet<>();
        for (Shop s : picked) ids.add(s.getId());

        // 菜品范围 = **这 200 家店的菜**（严格的子集关系，绝不越界）。
        //
        // 「商家刚发的菜，客户端立刻能看到」这个产品保证不在这里实现 ——
        // 它由 RankService.topShopsByDistance 的**店铺保底**保证：
        // 刚上线 / 刚改过资料的店会被强制拉进 picked（见 ShopService.isRecentlyChanged），
        // 店进来了，它的菜自然就在下面这轮过滤里。
        // 两边各管各的反而会造出「有菜没店」的孤儿数据。
        List<Dish> dishes = new ArrayList<>();
        for (Dish d : dishService.listByStatus("normal")) {
            if (ids.contains(d.getShopId())) dishes.add(d);
        }
        // 评论只保留「菜品还在这次返回范围内」的，否则前端拿不到对应菜
        Set<String> dishIds = new HashSet<>();
        for (Dish d : dishes) dishIds.add(d.getId());
        List<com.eatwhat.entity.Comment> comments = new ArrayList<>();
        for (com.eatwhat.entity.Comment c : commentService.listAll()) {
            if (dishIds.contains(c.getDishId())) comments.add(c);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shops", views.shops(picked));
        m.put("dishes", views.dishes(dishes));
        m.put("comments", views.comments(comments));
        // 告诉前端「这只是前 N 家，还有更多」—— 前端据此决定要不要提示/分页
        m.put("totalShops", visible.size());
        m.put("truncated", visible.size() > picked.size());

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("priceTiers", configService.priceTiers());
        cfg.put("tasteTags", configService.tasteTags());
        cfg.put("cuisines", configService.cuisines());
        m.put("config", cfg);

        return R.ok(m);
    }

    /**
     * 首屏最多给多少家店。
     *
     * <p>取 200 的依据：实测 200 家 + 其菜品 ≈ 600KB，
     * 在 4G 下约 1.5 秒能下完，是可接受的首屏代价。
     * 再往上加，收益（用户根本划不到）远小于代价（首屏变慢）。
     */
    private static final int BOOTSTRAP_SHOP_LIMIT = 200;

    /**
     * 「刚上线的店」多久之内必须保底进首屏（小时）。
     *
     * <p><b>为什么需要保底</b>：限量是按距离取的，这会让一个产品保证悄悄失效 ——
     * 「平台刚审核通过的店，客户端立刻能看到」。
     * 一家新店如果在城郊（或距离排不进前 200），限量后它要等别的店被删/被封
     * 才可能露脸，商家会以为「审核通过了怎么没上线」。
     *
     * <p>而且平台审核的验收动作就是「通过 → 去客户端刷新看看」，
     * 保底缺失会让这个动作时灵时不灵（测试里就抓到了这条：
     * {@code 通过后客户端立刻能看到这家店}）。
     *
     * <p>48 小时覆盖「审核完当天到第二天再看一眼」这个真实节奏。
     */
    private static final long NEW_SHOP_BOOST_HOURS = 48;

    /**
     * 信息流。tab = nearby | random
     * tiers / tastes 可选，传了就按筛选条件出流。
     * limit / offset 用于分页 —— **一定要传 limit**，不传走默认值。
     *
     * <p><b>⚠️ 为什么要分页（血泪）</b>：这个接口返回的是「每店一条」——
     * 在 22 家演示数据下就是 22 条，没人觉得有问题。
     * 数据扩到 1620 家真实店铺后，一次请求返回 **1620 条 / 909KB**，
     * 而客户端的「附近」流本来就是无限下滑的，一次性给全量既浪费带宽
     * 又让首屏变慢。现在默认 limit=30，前端下滑时用 offset 续拉。
     *
     * <p>筛选态下的两条关键差异：
     * 1. 每店取「符合筛选条件的最新一条」——而不是笼统的最新一条，
     *    否则筛"麻辣"时，某店最新菜不辣就会被整店剔除，
     *    即使店里更早有辣的菜。
     * 2. 不做内容补位 —— 补位会把不符合条件的菜塞回来，筛选失效。
     */
    @GetMapping("/feed")
    public org.springframework.http.ResponseEntity<R<List<Map<String, Object>>>> feed(
                                              @RequestParam(defaultValue = "nearby") String tab,
                                              @RequestParam(required = false) String city,
                                              @RequestParam(required = false) String browsed,
                                              @RequestParam(required = false) List<String> tiers,
                                              @RequestParam(required = false) List<String> tastes,
                                              @RequestParam(defaultValue = "30") int limit,
                                              @RequestParam(defaultValue = "0") int offset) {
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

        // 分页要在**补位之前**做：
        // 补位是「供给不足时兜底」，它保证的是「这一页至少有 3 条」，
        // 如果先补位再分页，第 5 页会莫名其妙被补进几条第 1 页就看过的菜。
        // 先把完整流裁出来，再对这一页判断要不要补位。
        int pageSize = Math.max(1, Math.min(limit, MAX_FEED_LIMIT));
        int from = Math.max(0, Math.min(offset, feed.size()));
        int to = Math.min(feed.size(), from + pageSize);
        int total = feed.size();

        if (from > 0 || to < feed.size()) {
            feed = new ArrayList<>(feed.subList(from, to));
        }

        // 内容供给不足时补位（冻结规则 D2）。
        // 注意：只补「尚未出现在 feed 里的店铺」的历史内容，
        // 否则同一家店会有两条内容同时占据首页，破坏"每店一条"。
        // 只在第一页补 —— 后续页补位会把前面看过的内容又塞回来。
        if (!filtered && offset == 0 && feed.size() < 3 && total > 0) {
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

        // ⚠️ 分页信息放在响应头而不是改返回结构 ——
        // 前端（api.js）把 feed 当纯数组用（`cache.dishes = d`），
        // 改成 {list, total} 会让所有读 feed 的地方静默失效。
        // 用 X-Total-Count 这种约定俗成的方式，前端要分页时自己读头。
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.add("X-Total-Count", String.valueOf(total));
        h.add("X-Page-Offset", String.valueOf(from));
        h.add("X-Page-Size", String.valueOf(feed.size()));
        h.add("Access-Control-Expose-Headers", "X-Total-Count, X-Page-Offset, X-Page-Size");

        return org.springframework.http.ResponseEntity.ok()
                .headers(h)
                .body(R.ok(views.dishes(feed)));
    }

    /**
     * feed 单次最多返回多少条。前端传再大也封顶 ——
     * 否则「分页」等于没做，一个 limit=99999 又回到全量。
     */
    private static final int MAX_FEED_LIMIT = 100;

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
     * 筛选结果明细：返回**符合条件的**菜品，不做"每店一条"去重。
     * 维度内部 OR（多选口味命中任一即可），维度之间 AND。
     * 若要"每店一条"的筛选信息流，用 GET /feed?tiers=..&tastes=..
     *
     * <p>⚠️ 同样要封顶：数据扩到 1620 家后，不筛条件就是 6944 条 / 4MB，
     * 比 /feed 更危险（这里不去重）。limit 默认 50，上限 200。
     */
    @PostMapping("/filter")
    public R<List<Map<String, Object>>> filter(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> tiers = (List<String>) body.getOrDefault("tiers", new ArrayList<>());
        @SuppressWarnings("unchecked")
        List<String> tastes = (List<String>) body.getOrDefault("tastes", new ArrayList<>());
        int limit = 50;
        Object lv = body.get("limit");
        if (lv instanceof Number n) limit = n.intValue();
        limit = Math.max(1, Math.min(limit, 200));

        List<Dish> hit = new ArrayList<>();
        for (Dish d : dishService.listNormal()) {
            if (matchFilter(d, tiers, tastes)) {
                hit.add(d);
                if (hit.size() >= limit) break;
            }
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
