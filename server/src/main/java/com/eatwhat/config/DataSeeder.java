package com.eatwhat.config;

import com.eatwhat.entity.*;
import com.eatwhat.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 首次启动灌入演示数据（对齐前端 mock.js）。
 * 库里已有数据则跳过，不会覆盖你的修改。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final ObjectMapper json = new ObjectMapper();

    private final ShopRepository shopRepo;
    private final DishRepository dishRepo;
    private final CommentRepository commentRepo;
    private final AppUserRepository userRepo;
    private final ReportRepository reportRepo;
    private final PlatformConfigRepository configRepo;

    @Value("${eatwhat.seed:true}")
    private boolean enabled;

    public DataSeeder(ShopRepository shopRepo, DishRepository dishRepo, CommentRepository commentRepo,
                      AppUserRepository userRepo, ReportRepository reportRepo,
                      PlatformConfigRepository configRepo) {
        this.shopRepo = shopRepo;
        this.dishRepo = dishRepo;
        this.commentRepo = commentRepo;
        this.userRepo = userRepo;
        this.reportRepo = reportRepo;
        this.configRepo = configRepo;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        if (shopRepo.count() > 0) {
            log.info("演示数据已存在，跳过初始化");
            return;
        }
        log.info("正在灌入演示数据…");

        seedShops();
        seedDishes();
        seedUsers();
        seedComments();
        seedReports();

        log.info("演示数据完成：店铺 {} 家 / 菜品 {} 道 / 用户 {} 人",
                shopRepo.count(), dishRepo.count(), userRepo.count());
    }

    private void seedShops() {
        long now = System.currentTimeMillis();
        long h = 3600_000L;

        // 前三家发布时间刻意放在几小时内 —— 这样「今日发布菜品」和近 7 日趋势
        // 有真实数据可看，同时它们仍在 24h 内，能演示时间衰减。
        shop("s_001", "蜀香小馆", "川菜", "成都市", "武侯区", "武侯区科华北路 12 号", "028-85123456",
                "11:00-22:00", "开了十年的苍蝇馆子，麻婆豆腐是招牌。",
                30.65, 104.07, 0.8, false, 100, false, now - 2 * h, 3200, 412, 188, 36, 24, 24, 1);
        shop("s_002", "老李炭火烧烤", "烧烤", "成都市", "武侯区", "武侯区一环路南三段 88 号", "028-85234567",
                "17:00-02:00", "炭火现烤，五花肉厚切。",
                30.64, 104.08, 1.2, true, 90, false, now - 4 * h, 2810, 356, 142, 28, 19, 24, 1);
        shop("s_003", "川味坊", "川菜", "成都市", "锦江区", "锦江区春熙路 5 号", "028-85345678",
                "10:00-21:00", "家常川菜，回锅肉一绝。",
                30.66, 104.09, 2.1, true, 80, false, now - 6 * h, 1420, 168, 62, 12, 8, 24, 1);
        shop("s_004", "一味面馆", "面食", "成都市", "武侯区", "武侯区人民南路 33 号", "028-85456789",
                "07:00-20:00", "红油抄手，皮薄馅大。",
                30.63, 104.06, 0.5, true, 70, false, now - 30 * h, 1180, 142, 58, 9, 31, 24, 1);
        shop("s_005", "樱町日料", "日料", "成都市", "锦江区", "锦江区红星路三段 1 号", "028-85567890",
                "11:30-22:00", "每日空运，蓝鳍金枪鱼限量。",
                30.67, 104.10, 3.4, true, 60, true, now - 18 * h, 1960, 288, 210, 44, 6, 24, 1);
        shop("s_006", "老字号甜水面", "小吃", "成都市", "青羊区", "青羊区宽窄巷子 8 号", "028-85678901",
                "09:00-19:00", "一根面拇指粗，酱料甜辣。",
                30.68, 104.05, 4.2, false, 50, false, now - 28 * h, 980, 176, 84, 15, 47, 24, 1);

        // 给已上线的店补审核记录，让「已通过」列表有历史可看。
        // 没有这一步，平台端审核页的"已通过"永远是空的。
        markReviewed("s_001", "2026-09-10 14:20", "2026-09-10 09:05");
        markReviewed("s_002", "2026-09-09 11:30", "2026-09-09 08:40");
        markReviewed("s_003", "2026-09-08 16:05", "2026-09-08 10:12");
        markReviewed("s_004", "2026-09-07 15:22", "2026-09-07 09:30");
        markReviewed("s_005", "2026-09-06 14:10", "2026-09-06 11:00");
        markReviewed("s_006", "2026-09-05 17:45", "2026-09-05 13:20");

        // 待审核队列 —— 平台端「商家审核」页的数据源。
        // 审核通过前客户端完全看不到这两家店（listVisible 会过滤掉 pending）。
        auditShop("p_001", "新开的螺蛳粉", "小吃", "成都市", "成华区",
                "成华区建设路 66 号", "028-88887777", "10:00-23:00",
                "正宗柳州味道，酸笋每天现发。", "2026-09-14 09:15", "pending", null, null);

        auditShop("p_002", "巷子口串串香", "火锅", "成都市", "金牛区",
                "金牛区抚琴西路 8 号", "028-88888888", "16:00-03:00",
                "老巷子里的苍蝇馆子，开了七年。", "2026-09-14 10:02", "pending", null, null);

        // 一条已驳回的历史记录，演示驳回理由回显
        auditShop("p_r1", "无名小摊", "小吃", "成都市", "金牛区",
                "成都市某处", "028-00000000", "不定", "",
                "2026-09-08 09:00", "rejected",
                "门头图不清晰，无法辨认店铺招牌，且未填写详细地址", "2026-09-08 10:20");
    }

    /** 给已上线店铺补审核痕迹（审核人 + 审核时间） */
    private void markReviewed(String id, String reviewedAt, String submittedAt) {
        shopRepo.findById(id).ifPresent(s -> {
            s.setReviewedAt(reviewedAt);
            s.setSubmittedAt(submittedAt);
            s.setReviewer("平台运营");
            shopRepo.save(s);
        });
    }

    /**
     * 待审核 / 已驳回的商家。
     * 这类店还没正式上线，因此没有距离、权重、统计数据 —— 全部给 0，
     * 避免平台端列表出现 null 让页面渲染出 "undefined"。
     */
    private void auditShop(String id, String name, String cuisine, String city, String district,
                           String address, String phone, String hours, String intro,
                           String submittedAt, String status, String rejectReason, String reviewedAt) {
        Shop s = new Shop();
        s.setId(id);
        s.setName(name);
        s.setCuisine(cuisine);
        s.setCity(city);
        s.setDistrict(district);
        s.setAddress(address);
        s.setPhone(phone);
        s.setHours(hours);
        s.setIntro(intro);
        s.setLat(30.66);
        s.setLng(104.07);
        // 还没上线的店没有真实距离。给 null 而不是 0 ——
        // 0 在排序里等于"就在你脚下"，会把没上线的店顶到推荐榜首。
        s.setDistance(null);
        s.setStatus(status);
        s.setSubmittedAt(submittedAt);
        s.setReviewedAt(reviewedAt);
        s.setRejectReason(rejectReason);
        if (reviewedAt != null) s.setReviewer("平台运营");
        s.setCanPostToday(false);
        s.setWeight(0);
        s.setPinned(false);
        s.setIntervalHours(24);
        s.setDailyLimit(1);
        s.setStatViews(0);
        s.setStatLikes(0);
        s.setStatFavorites(0);
        s.setStatComments(0);
        s.setStatCheckins(0);
        s.setCover("https://picsum.photos/seed/" + id + "cover/800/600");
        s.setLogo("https://picsum.photos/seed/" + id + "logo/200/200");
        shopRepo.save(s);
    }

    private void shop(String id, String name, String cuisine, String city, String district,
                      String address, String phone, String hours, String intro,
                      Double lat, Double lng, Double distance, Boolean canPost, Integer weight,
                      Boolean pinned, Long lastPublishAt,
                      int views, int likes, int fav, int cmt, int checkin,
                      int intervalHours, int dailyLimit) {
        Shop s = new Shop();
        s.setId(id);
        s.setName(name);
        s.setCuisine(cuisine);
        s.setCity(city);
        s.setDistrict(district);
        s.setAddress(address);
        s.setPhone(phone);
        s.setHours(hours);
        s.setIntro(intro);
        s.setLat(lat);
        s.setLng(lng);
        s.setDistance(distance);
        s.setStatus("normal");
        s.setCanPostToday(canPost);
        s.setWeight(weight);
        s.setPinned(pinned);
        s.setLastPublishAt(lastPublishAt);
        s.setIntervalHours(intervalHours);
        s.setDailyLimit(dailyLimit);
        s.setStatViews(views);
        s.setStatLikes(likes);
        s.setStatFavorites(fav);
        s.setStatComments(cmt);
        s.setStatCheckins(checkin);
        // picsum 占位图
        s.setCover("https://picsum.photos/seed/" + id + "cover/800/600");
        s.setLogo("https://picsum.photos/seed/" + id + "logo/200/200");
        shopRepo.save(s);
    }

    private void seedDishes() {
        long now = System.currentTimeMillis();
        long h = 3600_000L;

        dish("d_001", "s_001", "蜀香小馆", "麻婆豆腐", "石磨豆腐配自家花椒，麻辣鲜香。",
                "image", List.of("https://picsum.photos/seed/dish1a/800/1200",
                        "https://picsum.photos/seed/dish1b/800/1200"),
                28.0, "tier_mid", "real", List.of("麻辣"), now - 2 * h, 3200, 412, 188, 36, 24);

        dish("d_002", "s_002", "老李炭火烧烤", "炭烤五花肉", "厚切带皮五花，炭火慢烤四十分钟，外焦里嫩。",
                "video", List.of("https://picsum.photos/seed/dish2a/800/1200"),
                68.0, "tier_high", "real", List.of("烧烤"), now - 4 * h, 2810, 356, 142, 28, 19);

        dish("d_003", "s_003", "川味坊", "回锅肉", "",
                "image", List.of("https://picsum.photos/seed/dish3a/800/1200",
                        "https://picsum.photos/seed/dish3b/800/1200"),
                42.0, "tier_mid", "ad", List.of("麻辣"), now - 6 * h, 1420, 168, 62, 12, 8);

        dish("d_004", "s_005", "樱町日料", "蓝鳍金枪鱼大腹", "每日空运，限量六份。油脂分布像雪花一样。",
                "image", List.of("https://picsum.photos/seed/dish4a/800/1200",
                        "https://picsum.photos/seed/dish4b/800/1200",
                        "https://picsum.photos/seed/dish4c/800/1200",
                        "https://picsum.photos/seed/dish4d/800/1200"),
                288.0, "tier_ultra", "real", List.of("日料", "清淡"), now - 17 * h, 1960, 288, 210, 44, 6);

        dish("d_005", "s_004", "一味面馆", "红油抄手", "皮薄如纸，红油是自家舂的辣椒。",
                "image", List.of("https://picsum.photos/seed/dish5a/800/1200"),
                18.0, "tier_mid", "real", List.of("麻辣", "面食"), now - 29 * h, 1180, 142, 58, 9, 31);

        dish("d_006", "s_006", "老字号甜水面", "甜水面", "一根面有拇指粗，酱料甜中带辣。",
                "image", List.of("https://picsum.photos/seed/dish6a/800/1200",
                        "https://picsum.photos/seed/dish6b/800/1200"),
                12.0, "tier_mid", "real", List.of("小吃", "面食", "甜点"), now - 27 * h, 980, 176, 84, 15, 47);
    }

    private void dish(String id, String shopId, String shopName, String name, String descr,
                      String type, List<String> media, Double price, String tierId, String realTag,
                      List<String> tasteTags, Long publishedTs,
                      int views, int likes, int fav, int cmt, int checkin) {
        Dish d = new Dish();
        d.setId(id);
        d.setShopId(shopId);
        d.setShopName(shopName);
        d.setName(name);
        d.setDescr(descr);
        d.setType(type);
        d.setMedia(toJson(media));
        d.setCover(media.get(0));
        d.setPrice(price);
        d.setPriceTierId(tierId);
        d.setRealTag(realTag);
        d.setTasteTags(toJson(tasteTags));
        d.setPublishedTs(publishedTs);
        d.setPublishedAt(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(publishedTs), ZoneId.of("Asia/Shanghai")).format(FMT));
        d.setStatus("normal");
        d.setStatViews(views);
        d.setStatLikes(likes);
        d.setStatFavorites(fav);
        d.setStatComments(cmt);
        d.setStatCheckins(checkin);
        dishRepo.save(d);
    }

    private void seedUsers() {
        user("u_001", "小吃货", "13800000001", "成都市", "武侯区", "normal", 12, 0, "2026-08-01");
        user("u_002", "隔壁老王", "13800000002", "成都市", "武侯区", "normal", 45, 0, "2026-07-12");
        user("u_003", "干饭人小李", "13800000003", "成都市", "锦江区", "warned", 88, 3, "2026-06-20");
        user("u_004", "夜宵战神", "13800000004", "成都市", "青羊区", "normal", 26, 0, "2026-08-15");
    }

    private void user(String id, String name, String phone, String city, String district,
                      String status, int comments, int reports, String at) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setName(name);
        u.setPhone(phone);
        u.setCity(city);
        u.setDistrict(district);
        u.setStatus(status);
        u.setCommentCount(comments);
        u.setReportCount(reports);
        u.setAt(at);
        u.setAvatar("https://picsum.photos/seed/" + id + "/100/100");
        userRepo.save(u);
    }

    private void seedComments() {
        long now = System.currentTimeMillis();
        comment("c_001", "d_001", "u_002", "隔壁老王", "昨天去吃了，豆腐嫩得离谱，配米饭绝了。",
                now - 2 * 3600_000L, "谢谢支持！下次来给您多加点花椒~", now - 1 * 3600_000L);
        comment("c_002", "d_001", "u_003", "干饭人小李", "价格是不是涨了？之前好像 24。",
                now - 1 * 3600_000L, null, null);
        comment("c_003", "d_002", "u_004", "夜宵战神", "烤四十分钟是真的，等的时候有点久但值得。",
                now - 5 * 3600_000L, null, null);
    }

    private void comment(String id, String dishId, String userId, String userName,
                         String content, Long atTs, String reply, Long replyTs) {
        Comment c = new Comment();
        c.setId(id);
        c.setDishId(dishId);
        c.setUserId(userId);
        c.setUserName(userName);
        c.setContent(content);
        c.setAtTs(atTs);
        c.setAt(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(atTs),
                ZoneId.of("Asia/Shanghai")).format(FMT));
        if (reply != null) {
            c.setReplyContent(reply);
            c.setReplyAt(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(replyTs),
                    ZoneId.of("Asia/Shanghai")).format(FMT));
        }
        c.setStatus("normal");
        c.setAvatar("https://picsum.photos/seed/" + userId + "/100/100");
        commentRepo.save(c);
    }

    private void seedReports() {
        report("r_001", "dish", "d_003", "回锅肉", "图片与实物不符", "干饭人小李", "2026-09-14 11:20");
        report("r_002", "comment", "c_002", "评论：价格是不是涨了", "恶意刷差评", "蜀香小馆", "2026-09-14 11:45");
    }

    private void report(String id, String type, String targetId, String targetName,
                        String reason, String reporter, String at) {
        Report r = new Report();
        r.setId(id);
        r.setType(type);
        r.setTargetId(targetId);
        r.setTargetName(targetName);
        r.setReason(reason);
        r.setReporter(reporter);
        r.setAt(at);
        r.setStatus("pending");
        reportRepo.save(r);
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (Exception e) { return "[]"; }
    }
}
