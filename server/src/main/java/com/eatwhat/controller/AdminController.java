package com.eatwhat.controller;

import com.eatwhat.common.BizException;
import com.eatwhat.common.R;
import com.eatwhat.entity.*;
import com.eatwhat.repository.ReportRepository;
import com.eatwhat.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 平台端接口。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ShopService shopService;
    private final DishService dishService;
    private final CommentService commentService;
    private final AppUserService userService;
    private final ConfigService configService;
    private final RankService rankService;
    private final ReportRepository reportRepo;

    public AdminController(ShopService shopService, DishService dishService,
                           CommentService commentService, AppUserService userService,
                           ConfigService configService, RankService rankService,
                           ReportRepository reportRepo) {
        this.shopService = shopService;
        this.dishService = dishService;
        this.commentService = commentService;
        this.userService = userService;
        this.configService = configService;
        this.rankService = rankService;
        this.reportRepo = reportRepo;
    }

    // ==================== 数据概览 ====================

    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        List<Shop> shops = shopService.listAll();
        List<Dish> dishes = dishService.listNormal();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shopCount", shops.size());
        m.put("dishCount", dishes.size());
        m.put("userCount", userService.count());
        m.put("reportPending", reportRepo.findByStatusOrderByAtDesc("pending").size());
        m.put("shopNormal", shops.stream().filter(s -> "normal".equals(s.getStatus())).count());
        m.put("shopMuted", shops.stream().filter(s -> "muted".equals(s.getStatus())).count());
        m.put("shopBanned", shops.stream().filter(s -> "banned".equals(s.getStatus())).count());
        return R.ok(m);
    }

    // ==================== 店铺管理 ====================

    @GetMapping("/shops")
    public R<List<Shop>> shops() {
        return R.ok(shopService.listAll());
    }

    @PostMapping("/shop")
    public R<Shop> createShop(@RequestBody Map<String, Object> body) {
        return R.ok(shopService.create(body));
    }

    @PostMapping("/shop/{id}/mute")
    public R<Shop> mute(@PathVariable String id) {
        return R.ok(shopService.mute(id));
    }

    @PostMapping("/shop/{id}/ban")
    public R<Shop> ban(@PathVariable String id) {
        return R.ok(shopService.ban(id));
    }

    @PostMapping("/shop/{id}/restore")
    public R<Shop> restore(@PathVariable String id) {
        return R.ok(shopService.restore(id));
    }

    /** 单店发布规则覆盖 */
    @PostMapping("/shop/{id}/rule")
    public R<Shop> setRule(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Integer interval = body.get("intervalHours") == null ? null
                : Integer.valueOf(body.get("intervalHours").toString());
        Integer limit = body.get("dailyLimit") == null ? null
                : Integer.valueOf(body.get("dailyLimit").toString());
        return R.ok(shopService.setRule(id, interval, limit));
    }

    // ==================== 推荐排名 ====================

    /** 按地区取店铺（含封禁，平台端要看到全部） */
    @GetMapping("/ranking")
    public R<List<Shop>> ranking(@RequestParam(required = false) String city) {
        List<Shop> list = shopService.listAll();
        if (city != null && !city.isBlank()) {
            list = list.stream().filter(s -> city.equals(s.getCity())).toList();
        }
        return R.ok(rankService.sortShops(new ArrayList<>(list)));
    }

    /** 拖拽排序落库 */
    @PostMapping("/ranking/order")
    public R<Void> saveOrder(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("shopIds");
        if (ids == null || ids.isEmpty()) throw new BizException("排序结果为空");
        rankService.applyOrder(ids);
        return R.ok();
    }

    /** 置顶开关 */
    @PostMapping("/shop/{id}/pinned")
    public R<Shop> setPinned(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean pinned = Boolean.parseBoolean(String.valueOf(body.get("pinned")));
        return R.ok(shopService.setPinned(id, pinned));
    }

    /** 单独设权重 */
    @PostMapping("/shop/{id}/weight")
    public R<Shop> setWeight(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Integer w = body.get("weight") == null ? 0 : Integer.valueOf(body.get("weight").toString());
        return R.ok(shopService.setWeight(id, w));
    }

    // ==================== 内容管理 ====================

    @GetMapping("/contents")
    public R<Map<String, Object>> contents() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("all", dishService.listByStatus("normal"));
        m.put("removed", dishService.listByStatus("removed"));
        m.put("reports", reportRepo.findAllByOrderByAtDesc());
        return R.ok(m);
    }

    /** 打真实性标签 */
    @PostMapping("/dish/{id}/real-tag")
    public R<Dish> tagReal(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(dishService.tagReal(id, String.valueOf(body.get("realTag"))));
    }

    /** 下架 / 恢复 */
    @PostMapping("/dish/{id}/status")
    public R<Dish> dishStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(dishService.setStatus(id, String.valueOf(body.get("status"))));
    }

    /** 举报处理：确认违规 / 驳回 */
    @PostMapping("/report/{id}/handle")
    public R<Report> handleReport(@PathVariable String id, @RequestBody Map<String, Object> body) {
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
        return R.ok(reportRepo.save(r));
    }

    // ==================== 用户管理 ====================

    @GetMapping("/users")
    public R<List<AppUser>> users() {
        return R.ok(userService.listByRisk());
    }

    @PostMapping("/user/{id}/status")
    public R<AppUser> userStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(userService.setStatus(id, String.valueOf(body.get("status"))));
    }

    // ==================== 平台配置 ====================

    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priceTiers", configService.priceTiers());
        m.put("tasteTags", configService.tasteTags());
        m.put("cuisines", configService.cuisines());
        return R.ok(m);
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
        m.put("tiers", configService.priceTiers());
        m.put("recalculated", changed);
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
}
