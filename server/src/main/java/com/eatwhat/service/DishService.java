package com.eatwhat.service;

import com.eatwhat.common.BizException;
import com.eatwhat.entity.Dish;
import com.eatwhat.entity.Shop;
import com.eatwhat.repository.DishRepository;
import com.eatwhat.repository.ShopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 菜品服务。
 * 承载两条最硬的业务规则：
 *   1) 每店按 intervalHours / dailyLimit 限发
 *   2) 图文与视频二选一，发布后不可改类型
 */
@Service
public class DishService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DishRepository dishRepo;
    private final ShopRepository shopRepo;
    private final ConfigService configService;

    public DishService(DishRepository dishRepo, ShopRepository shopRepo, ConfigService configService) {
        this.dishRepo = dishRepo;
        this.shopRepo = shopRepo;
        this.configService = configService;
    }

    public List<Dish> listByShop(String shopId) {
        return dishRepo.findByShopIdOrderByPublishedTsDesc(shopId);
    }

    public Dish get(String id) {
        return dishRepo.findById(id).orElseThrow(() -> new BizException(404, "菜品不存在"));
    }

    /** 全部正常菜品（客户端信息流用） */
    public List<Dish> listNormal() {
        return dishRepo.findByStatusOrderByPublishedTsDesc("normal");
    }

    /** 按状态取菜品（平台端内容管理用：normal / removed） */
    public List<Dish> listByStatus(String status) {
        return dishRepo.findByStatusOrderByPublishedTsDesc(status);
    }

    // ==================== 核心规则 1：发布限制 ====================

    /**
     * 校验今日能否发布。
     * 规则：距上次发布不足 intervalHours 小时 → 拒绝；当日已达 dailyLimit 条 → 拒绝。
     * 注意：规则变更不影响已发记录，只从下一次算起 —— 所以这里读的是店铺当前的规则值。
     */
    public void checkPublishLimit(Shop shop) {
        if ("banned".equals(shop.getStatus())) {
            throw new BizException("店铺已被封禁，无法发布");
        }
        if ("muted".equals(shop.getStatus())) {
            throw new BizException("店铺已被禁言，无法发布");
        }

        int interval = shop.getIntervalHours() == null ? 24 : shop.getIntervalHours();
        int limit = shop.getDailyLimit() == null ? 1 : shop.getDailyLimit();

        // 当日已发数量
        long dayStart = todayStartMs();
        long todayCount = dishRepo.countByShopIdAndPublishedTsAfter(shop.getId(), dayStart);
        if (todayCount >= limit) {
            throw new BizException("今日已达发布上限（" + limit + " 条），请明天再来");
        }

        // 冷却校验
        if (shop.getLastPublishAt() != null) {
            long elapsedH = (System.currentTimeMillis() - shop.getLastPublishAt()) / 1000 / 3600;
            if (elapsedH < interval) {
                long remain = interval - elapsedH;
                throw new BizException("距上次发布不足 " + interval + " 小时，还需等待约 " + remain + " 小时");
            }
        }
    }

    /** 剩余冷却秒数，供商家端工作台倒计时显示 */
    public long remainingCooldownSeconds(Shop shop) {
        if (shop.getLastPublishAt() == null) return 0;
        int interval = shop.getIntervalHours() == null ? 24 : shop.getIntervalHours();
        long passed = (System.currentTimeMillis() - shop.getLastPublishAt()) / 1000;
        long total = (long) interval * 3600;
        return Math.max(0, total - passed);
    }

    // ==================== 核心规则 2：发布 ====================

    /**
     * 发布菜品。
     * - type 必为 image 或 video，二选一
     * - 价格由系统自动归档档位
     */
    @Transactional
    public Dish publish(Map<String, Object> body) {
        String shopId = str(body.get("shopId"));
        if (shopId == null) throw new BizException("缺少店铺 id");

        Shop shop = shopRepo.findById(shopId)
                .orElseThrow(() -> new BizException(404, "店铺不存在"));

        checkPublishLimit(shop);

        String name = str(body.get("name"));
        if (name == null || name.isBlank()) throw new BizException("请填写菜品名称");

        String type = str(body.get("type"));
        if (!"image".equals(type) && !"video".equals(type)) {
            throw new BizException("内容形式必须是 image 或 video");
        }

        @SuppressWarnings("unchecked")
        List<String> media = (List<String>) body.getOrDefault("media", new ArrayList<>());
        if (media.isEmpty()) throw new BizException("请至少上传一张图片或一个视频");
        if ("image".equals(type) && media.size() > 9) throw new BizException("图文最多 9 张");

        String descr = str(body.get("desc"));
        if (descr != null && descr.length() > 100) throw new BizException("简介不能超过 100 字");

        Double price = body.get("price") == null ? null : Double.valueOf(body.get("price").toString());

        Dish d = new Dish();
        d.setId("d_" + System.currentTimeMillis());
        d.setShopId(shop.getId());
        d.setShopName(shop.getName());
        d.setName(name);
        d.setDescr(descr);
        d.setType(type);
        d.setMedia(toJson(media));
        d.setCover(str(body.get("cover")) != null ? str(body.get("cover")) : media.get(0));
        // 视频菜品：media 里放的是封面图，真视频单独存
        if ("video".equals(type)) {
            d.setVideoUrl(str(body.get("videoUrl")));
        }
        d.setPrice(price);

        // 价格自动归档 —— 商家不可手选
        d.setPriceTierId(configService.resolveTier(price));

        d.setRealTag("pending");
        @SuppressWarnings("unchecked")
        List<String> taste = (List<String>) body.getOrDefault("tasteTags", new ArrayList<>());
        d.setTasteTags(toJson(taste));

        long now = System.currentTimeMillis();
        d.setPublishedTs(now);
        d.setPublishedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT));
        d.setStatus("normal");

        dishRepo.save(d);

        // 更新店铺的最后发布时间
        shop.setLastPublishAt(now);
        shop.setCanPostToday(false);
        shopRepo.save(shop);

        return d;
    }

    /**
     * 编辑菜品。
     * 关键：type 不可更改；价格变动会重新归档档位。
     */
    @Transactional
    public Dish update(String id, Map<String, Object> body) {
        Dish d = get(id);

        if (body.get("type") != null) {
            String newType = str(body.get("type"));
            if (!newType.equals(d.getType())) {
                throw new BizException("内容形式发布后不可更改");
            }
        }

        if (body.get("name") != null) d.setName(str(body.get("name")));
        if (body.get("desc") != null) d.setDescr(str(body.get("desc")));
        if (body.get("cover") != null) d.setCover(str(body.get("cover")));

        if (body.get("media") != null) {
            @SuppressWarnings("unchecked")
            List<String> media = (List<String>) body.get("media");
            d.setMedia(toJson(media));
        }

        // 价格变了要重新归档
        if (body.get("price") != null) {
            Double price = Double.valueOf(body.get("price").toString());
            d.setPrice(price);
            d.setPriceTierId(configService.resolveTier(price));
        }

        if (body.get("tasteTags") != null) {
            @SuppressWarnings("unchecked")
            List<String> taste = (List<String>) body.get("tasteTags");
            d.setTasteTags(toJson(taste));
        }

        return dishRepo.save(d);
    }

    /** 平台端：打真实性标签 */
    public Dish tagReal(String id, String realTag) {
        Dish d = get(id);
        d.setRealTag(realTag);
        return dishRepo.save(d);
    }

    /** 平台端：下架 / 恢复（不物理删除） */
    public Dish setStatus(String id, String status) {
        Dish d = get(id);
        d.setStatus(status);
        return dishRepo.save(d);
    }

    /**
     * 平台端改价格档配置后，重算所有菜品的档位。
     * @return 被改动的菜品数量
     */
    @Transactional
    public int recalcAllTiers() {
        int changed = 0;
        for (Dish d : dishRepo.findAll()) {
            if (d.getPrice() == null) continue;
            String newTier = configService.resolveTier(d.getPrice());
            if (!java.util.Objects.equals(newTier, d.getPriceTierId())) {
                d.setPriceTierId(newTier);
                dishRepo.save(d);
                changed++;
            }
        }
        return changed;
    }

    /** 打卡计数 +1 */
    @Transactional
    public void incCheckin(String dishId) {
        dishRepo.findById(dishId).ifPresent(d -> {
            d.setStatCheckins((d.getStatCheckins() == null ? 0 : d.getStatCheckins()) + 1);
            dishRepo.save(d);
        });
    }

    /** 点赞 / 收藏计数 */
    @Transactional
    public void incStat(String dishId, String field) {
        dishRepo.findById(dishId).ifPresent(d -> {
            switch (field) {
                case "likes" -> d.setStatLikes(nz(d.getStatLikes()) + 1);
                case "favorites" -> d.setStatFavorites(nz(d.getStatFavorites()) + 1);
                case "views" -> d.setStatViews(nz(d.getStatViews()) + 1);
                default -> { }
            }
            dishRepo.save(d);
        });
    }

    private int nz(Integer i) { return i == null ? 0 : i; }

    private long todayStartMs() {
        return java.time.LocalDate.now()
                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();
    }

    private String str(Object o) { return o == null ? null : o.toString(); }

    private String toJson(Object o) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { return "[]"; }
    }
}
