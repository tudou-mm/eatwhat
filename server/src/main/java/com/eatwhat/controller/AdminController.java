package com.eatwhat.controller;

import com.eatwhat.common.BizException;
import com.eatwhat.common.R;
import com.eatwhat.common.Views;
import com.eatwhat.entity.*;
import com.eatwhat.repository.ReportRepository;
import com.eatwhat.service.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 平台端接口。
 *
 * 两条约定（和客户端保持一致）：
 * 1. 所有出参都过 {@link Views}，绝不直接返回实体。
 *    实体是扁平字段（statViews），前端读的是嵌套结构（stats.views），
 *    直接返回实体页面只会静默显示空白，不报错、极难查。
 * 2. 所有写操作都返回**操作后的最新对象**。
 *    适配层拿到后直接替换内存里的那份，页面不必再发一次列表请求。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final ShopService shopService;
    private final DishService dishService;
    private final CommentService commentService;
    private final AppUserService userService;
    private final ConfigService configService;
    private final RankService rankService;
    private final ReportRepository reportRepo;
    private final Views views;

    public AdminController(ShopService shopService, DishService dishService,
                           CommentService commentService, AppUserService userService,
                           ConfigService configService, RankService rankService,
                           ReportRepository reportRepo, Views views) {
        this.shopService = shopService;
        this.dishService = dishService;
        this.commentService = commentService;
        this.userService = userService;
        this.configService = configService;
        this.rankService = rankService;
        this.reportRepo = reportRepo;
        this.views = views;
    }

    // ==================== 启动聚合接口 ====================

    /**
     * 平台端首屏聚合。一次请求拿全 8 个页面要用的数据。
     *
     * 为什么不用分散拉取：适配层是「同步预加载」，请求数直接等于白屏时长。
     * 平台端要用到 overview / shops / dishes / removed / reports / users /
     * 待审核 / 已通过 / 已驳回 / config 共 10 份数据，分散就是 10 次同步请求，
     * 首屏会卡到没法演示。聚合成 1 次，稳定在百毫秒级。
     */
    @GetMapping("/bootstrap")
    public R<Map<String, Object>> bootstrap() {
        List<Shop> all = shopService.listAll();
        List<Dish> dishes = dishService.listNormal();
        List<Dish> removed = dishService.listByStatus("removed");
        List<Report> reports = reportRepo.findAllByOrderByAtDesc();
        List<AppUser> users = userService.listByRisk();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overview", overviewOf(all, dishes, reports, users));
        m.put("shops", views.shops(all));
        m.put("dishes", views.dishes(dishes));
        m.put("removed", views.dishes(removed));
        m.put("reports", views.reports(reports));
        m.put("users", views.users(users));
        m.put("pendingShops", views.shops(shopService.listPending()));
        m.put("approvedShops", views.shops(shopService.listApproved()));
        m.put("rejectedShops", views.shops(shopService.listRejected()));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("priceTiers", configService.priceTiers());
        cfg.put("tasteTags", configService.tasteTags());
        cfg.put("cuisines", configService.cuisines());
        cfg.put("publishRule", configService.publishRule());
        m.put("config", cfg);

        return R.ok(m);
    }

    // ==================== 数据概览 ====================

    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(overviewOf(shopService.listAll(), dishService.listNormal(),
                reportRepo.findByStatusOrderByAtDesc("pending"), userService.listByRisk()));
    }

    private Map<String, Object> overviewOf(List<Shop> shops, List<Dish> dishes,
                                           List<Report> reports, List<AppUser> users) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shopCount", shops.size());
        m.put("shopNormal", shops.stream().filter(s -> "normal".equals(s.getStatus())).count());
        m.put("shopMuted", shops.stream().filter(s -> "muted".equals(s.getStatus())).count());
        m.put("shopBanned", shops.stream().filter(s -> "banned".equals(s.getStatus())).count());
        m.put("shopPending", shops.stream().filter(s -> "pending".equals(s.getStatus())).count());

        m.put("dishCount", dishes.size());
        m.put("userCount", users.size());
        m.put("reportPending", reports.size());

        // 今日发布数：按上海时区当日 0 点起算
        long dayStart = LocalDate.now(CN).atStartOfDay(CN).toInstant().toEpochMilli();
        m.put("dishToday", dishes.stream()
                .filter(d -> d.getPublishedTs() != null && d.getPublishedTs() >= dayStart)
                .count());

        // 近 7 日发布趋势，给首页柱状图用
        List<Map<String, Object>> trend = new ArrayList<>();
        String[] wk = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        for (int i = 6; i >= 0; i--) {
            LocalDate day = LocalDate.now(CN).minusDays(i);
            long from = day.atStartOfDay(CN).toInstant().toEpochMilli();
            long to = day.plusDays(1).atStartOfDay(CN).toInstant().toEpochMilli();
            long cnt = dishes.stream()
                    .filter(d -> d.getPublishedTs() != null
                            && d.getPublishedTs() >= from && d.getPublishedTs() < to)
                    .count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("d", i == 0 ? "今日" : wk[day.getDayOfWeek().getValue() - 1]);
            row.put("v", cnt);
            trend.add(row);
        }
        m.put("trend", trend);
        return m;
    }

    // ==================== 商家审核 ====================

    /** 待审核队列 */
    @GetMapping("/audit/pending")
    public R<List<Map<String, Object>>> pendingShops() {
        return R.ok(views.shops(shopService.listPending()));
    }

    /** 审核通过：商家上线，客户端立刻可见 */
    @PostMapping("/audit/{id}/approve")
    public R<Map<String, Object>> approve(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        String reviewer = body == null ? null : str(body.get("reviewer"));
        return R.ok(views.shop(shopService.approve(id, reviewer)));
    }

    /** 审核驳回：必须填理由 */
    @PostMapping("/audit/{id}/reject")
    public R<Map<String, Object>> reject(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        return R.ok(views.shop(shopService.reject(id, str(body.get("reason")), str(body.get("reviewer")))));
    }

    // ==================== 店铺管理 ====================

    /** 全量店铺（含待审核、封禁 —— 平台端必须看得到全部） */
    @GetMapping("/shops")
    public R<List<Map<String, Object>>> shops() {
        return R.ok(views.shops(shopService.listAll()));
    }

    @PostMapping("/shop")
    public R<Map<String, Object>> createShop(@RequestBody Map<String, Object> body) {
        return R.ok(views.shop(shopService.create(body)));
    }

    @PostMapping("/shop/{id}/mute")
    public R<Map<String, Object>> mute(@PathVariable String id) {
        return R.ok(views.shop(shopService.mute(id)));
    }

    @PostMapping("/shop/{id}/ban")
    public R<Map<String, Object>> ban(@PathVariable String id) {
        return R.ok(views.shop(shopService.ban(id)));
    }

    @PostMapping("/shop/{id}/restore")
    public R<Map<String, Object>> restore(@PathVariable String id) {
        return R.ok(views.shop(shopService.restore(id)));
    }

    /** 单店发布规则覆盖 */
    @PostMapping("/shop/{id}/rule")
    public R<Map<String, Object>> setRule(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Integer interval = body.get("intervalHours") == null ? null
                : Integer.valueOf(body.get("intervalHours").toString());
        Integer limit = body.get("dailyLimit") == null ? null
                : Integer.valueOf(body.get("dailyLimit").toString());
        return R.ok(views.shop(shopService.setRule(id, interval, limit)));
    }

    // ==================== 推荐排名 ====================

    /** 按地区取店铺（含封禁，平台端要看到全部） */
    @GetMapping("/ranking")
    public R<List<Map<String, Object>>> ranking(@RequestParam(required = false) String city) {
        List<Shop> list = shopService.listAll();
        if (city != null && !city.isBlank()) {
            list = list.stream().filter(s -> city.equals(s.getCity())).toList();
        }
        return R.ok(views.shops(rankService.sortShops(new ArrayList<>(list))));
    }

    /** 拖拽排序落库 */
    @PostMapping("/ranking/order")
    public R<List<Map<String, Object>>> saveOrder(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("shopIds");
        if (ids == null || ids.isEmpty()) throw new BizException("排序结果为空");
        rankService.applyOrder(ids);
        // 返回**排过序**的列表：前端拿它直接刷新界面，
        // 若返回未排序的 listAll()，页面上的顺序会和刚拖出来的对不上。
        return R.ok(views.shops(rankService.sortShops(shopService.listAll())));
    }

    /** 置顶开关 */
    @PostMapping("/shop/{id}/pinned")
    public R<Map<String, Object>> setPinned(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean pinned = Boolean.parseBoolean(String.valueOf(body.get("pinned")));
        return R.ok(views.shop(shopService.setPinned(id, pinned)));
    }

    /** 单独设权重 */
    @PostMapping("/shop/{id}/weight")
    public R<Map<String, Object>> setWeight(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Integer w = body.get("weight") == null ? 0 : Integer.valueOf(body.get("weight").toString());
        return R.ok(views.shop(shopService.setWeight(id, w)));
    }

    // ==================== 内容管理 ====================

    @GetMapping("/contents")
    public R<Map<String, Object>> contents() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("all", views.dishes(dishService.listByStatus("normal")));
        m.put("removed", views.dishes(dishService.listByStatus("removed")));
        m.put("reports", views.reports(reportRepo.findAllByOrderByAtDesc()));
        return R.ok(m);
    }

    /** 打真实性标签；realTag 传 null 表示清除标签 */
    @PostMapping("/dish/{id}/real-tag")
    public R<Map<String, Object>> tagReal(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(views.dish(dishService.tagReal(id, str(body.get("realTag")))));
    }

    /** 下架 / 恢复 */
    @PostMapping("/dish/{id}/status")
    public R<Map<String, Object>> dishStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(views.dish(dishService.setStatus(id, String.valueOf(body.get("status")))));
    }

    /** 举报处理：确认违规 / 驳回 */
    @PostMapping("/report/{id}/handle")
    public R<Map<String, Object>> handleReport(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Report r = reportRepo.findById(id).orElseThrow(() -> new BizException(404, "举报不存在"));
        String action = String.valueOf(body.get("action")); // confirmed | rejected
        r.setStatus(action);

        if ("confirmed".equals(action)) {
            // 内容类举报：下架内容；评论类：下架评论并给用户记一次
            if ("dish".equals(r.getType())) {
                dishService.setStatus(r.getTargetId(), "removed");
            } else if ("comment".equals(r.getType())) {
                commentService.remove(r.getTargetId());
            }
        }
        return R.ok(views.report(reportRepo.save(r)));
    }

    // ==================== 用户管理 ====================

    @GetMapping("/users")
    public R<List<Map<String, Object>>> users() {
        return R.ok(views.users(userService.listByRisk()));
    }

    @PostMapping("/user/{id}/status")
    public R<Map<String, Object>> userStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(views.user(userService.setStatus(id, String.valueOf(body.get("status")))));
    }

    // ==================== 平台配置 ====================

    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priceTiers", configService.priceTiers());
        m.put("tasteTags", configService.tasteTags());
        m.put("cuisines", configService.cuisines());
        m.put("publishRule", configService.publishRule());
        return R.ok(m);
    }

    /**
     * 全局默认发布规则。
     * 它决定新店铺的初始值，也是单店「自定义」标记的比较基准。
     */
    @PostMapping("/config/publish-rule")
    public R<Map<String, Object>> savePublishRule(@RequestBody Map<String, Object> body) {
        Integer interval = body.get("intervalHours") == null ? null
                : Integer.valueOf(body.get("intervalHours").toString());
        Integer limit = body.get("dailyLimit") == null ? null
                : Integer.valueOf(body.get("dailyLimit").toString());
        return R.ok(configService.savePublishRule(interval, limit));
    }

    /**
     * 保存价格档 —— 保存后会重算所有菜品的档位。
     * 这是"商家只填价格、系统自动归档"的兑现点。
     */
    @PostMapping("/config/price-tiers")
    public R<Map<String, Object>> savePriceTiers(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tiers = (List<Map<String, Object>>) body.get("tiers");
        if (tiers == null || tiers.isEmpty()) throw new BizException("至少保留一个价格档");
        configService.savePriceTiers(tiers);

        // 重算所有菜品档位 —— 兑现"商家只填价格、系统自动归档"
        int changed = dishService.recalcAllTiers();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priceTiers", configService.priceTiers());
        m.put("recalculated", changed);
        // 档位变了，菜品也跟着变，一并回传，省得前端再拉一次
        m.put("dishes", views.dishes(dishService.listNormal()));
        return R.ok(m);
    }

    @PostMapping("/config/taste-tags")
    public R<List<String>> saveTasteTags(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.get("tags");
        configService.saveTasteTags(tags == null ? new ArrayList<>() : tags);
        return R.ok(configService.tasteTags());
    }

    @PostMapping("/config/cuisines")
    public R<List<String>> saveCuisines(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) body.get("cuisines");
        configService.saveCuisines(list == null ? new ArrayList<>() : list);
        return R.ok(configService.cuisines());
    }

    private String str(Object o) { return o == null ? null : o.toString(); }
}
