package com.eatwhat.config;

import com.eatwhat.entity.*;
import com.eatwhat.repository.*;
import com.eatwhat.service.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 首次启动灌入演示数据（对齐前端 mock.js）。
 * 库里已有数据则跳过，不会覆盖你的修改。
 *
 * <p><b>数据规模</b>：店铺 29 家（在线 22 / 待审 5 / 已驳回 2）、菜品 54 道、用户 14 人、
 * 评论 28 条、举报 9 条。全部由 {@code server/tools/export_mock.py} 导出到
 * {@code assets/data/mock.js}，两边不会分叉 —— 改数据只改这里，然后重跑导出脚本。
 *
 * <p><b>为什么是表驱动而不是一堆 seed 方法调用</b>：
 * 数据量到了几百行以后，一条条手写方法调用既难查重、又难看出分布。
 * 现在改成「静态表 + 统一写入循环」，加一行就是加一条数据，
 * 而且能一眼看出某天是不是漏了内容（趋势图会空一根柱子）。
 *
 * <p><b>时间戳约定（重要）</b>：菜品的发布时间锚定在「今天 0 点」再往前推 N 天，
 * 而不是「当前时刻往前推 N 小时」。原因：
 * <ul>
 *   <li>「今日发布数」是按日历日算的，用相对小时会在凌晨跑出 0，趋势图也跟着错位</li>
 *   <li>锚定日历日后，凌晨 0:30 启动也不会出现「未来发布」的内容（会被 clamp 到当前时刻）</li>
 * </ul>
 *
 * <p>店铺的 lastPublishAt 不再手写，而是由 {@link #syncShopPublishTimes()}
 * 从「该店最新一条菜品」反推 —— 避免出现「菜品是今天发的、店铺却显示三天没发」这种矛盾。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final long HOUR = 3600_000L;
    private static final long DAY = 24 * HOUR;

    private final ObjectMapper json = new ObjectMapper();

    private final ShopRepository shopRepo;
    private final DishRepository dishRepo;
    private final CommentRepository commentRepo;
    private final AppUserRepository userRepo;
    private final ReportRepository reportRepo;
    private final ConfigService configService;

    @Value("${eatwhat.seed:true}")
    private boolean enabled;

    public DataSeeder(ShopRepository shopRepo, DishRepository dishRepo, CommentRepository commentRepo,
                      AppUserRepository userRepo, ReportRepository reportRepo,
                      ConfigService configService) {
        this.shopRepo = shopRepo;
        this.dishRepo = dishRepo;
        this.commentRepo = commentRepo;
        this.userRepo = userRepo;
        this.reportRepo = reportRepo;
        this.configService = configService;
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
        syncShopPublishTimes();

        log.info("演示数据完成：店铺 {} 家（在线 {} / 待审 {} / 驳回 {}）· 菜品 {} 道 · 用户 {} 人 · 评论 {} 条 · 举报 {} 条",
                shopRepo.count(),
                shopRepo.findAll().stream().filter(s -> "normal".equals(s.getStatus()) || "muted".equals(s.getStatus())).count(),
                shopRepo.findAll().stream().filter(s -> "pending".equals(s.getStatus())).count(),
                shopRepo.findAll().stream().filter(s -> "rejected".equals(s.getStatus())).count(),
                dishRepo.count(), userRepo.count(), commentRepo.count(), reportRepo.count());
    }

    // ==================== 时间工具 ====================

    /** 今天 0 点（上海时区） */
    private long todayStart() {
        return LocalDate.now(CN).atStartOfDay(CN).toInstant().toEpochMilli();
    }

    /**
     * 生成「N 天前的某时刻」。
     * 若算出来落在未来（凌晨启动时会这样），压到当前时刻前 90 秒，
     * 保证库里永远不会出现「未来发布」的内容。
     */
    private long ts(int daysAgo, int hour, int minute) {
        long v = todayStart() - daysAgo * DAY + hour * HOUR + minute * 60_000L;
        return Math.min(v, System.currentTimeMillis() - 90_000L);
    }

    private String fmt(long ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), CN).format(FMT);
    }

    // ==================== 载体定义 ====================

    /**
     * 在线店铺。
     * weight 刻意做成「有并列」—— 并列时才会走到「距离」和「时间衰减」两级，
     * 否则权重一旦全不相同，后两级规则永远演示不出来。
     */
    private record ShopSeed(String id, String name, String cuisine, String city, String district,
                            String address, String phone, String hours, String intro,
                            double lat, double lng, double distance,
                            int weight, boolean pinned,
                            int intervalHours, int dailyLimit,
                            int views, int likes, int fav, int cmt, int checkin,
                            int reviewDaysAgo) {}

    /** 菜品。tier 不写死，由 ConfigService.resolveTier(price) 算出来，保证与平台配置一致。 */
    private record DishSeed(String id, String shopId, String name, String desc, String type,
                            double price, String realTag, String tastes,
                            int daysAgo, int hour, int minute,
                            int views, int likes, int fav, int cmt, int checkin) {}

    private record UserSeed(String id, String name, String phone, String city, String district,
                            String status, int comments, int reports, String at) {}

    private record CommentSeed(String id, String dishId, String userId, int hoursAgo,
                               String content, String reply) {}

    private record ReportSeed(String id, String type, String targetId, String targetName,
                              String reason, String reporter, int daysAgo, String status) {}

    // ==================== 店铺 ====================

    /**
     * 22 家在线店铺，全部在成都（覆盖 7 个区）。
     *
     * <p>刻意不放第二个城市：前端 {@code buildFeed} 是本地实现、不按城市过滤，
     * 一旦后端多一个城市，就会出现「客户端刷得到、App 端 feed 刷不到」的分叉，
     * 而且会让客户端可刷内容变少 —— 与「多给点数据」的初衷相反。
     * 城市维度留给平台端 ranking 的 city 参数（接口已支持，见 docs/05）。
     *
     * <p>weight 刻意做成「有并列」—— 并列时才会走到「距离」和「时间衰减」两级，
     * 否则权重一旦全不相同，后两级规则永远演示不出来。
     */
    private static final List<ShopSeed> SHOPS = List.of(
            // ---- 成都 · 武侯区 ----
            // s_001 是演示商家账号：登录页填 13800138000（或直接填 s_001）即可进这家店
            new ShopSeed("s_001", "蜀香小馆", "川菜", "成都市", "武侯区", "武侯区科华北路 12 号", "13800138000",
                    "11:00-22:00", "开了十年的苍蝇馆子，麻婆豆腐是招牌。",
                    30.65, 104.07, 0.8, 60, false, 24, 1, 3200, 412, 188, 36, 24, 5),
            new ShopSeed("s_002", "老李炭火烧烤", "烧烤", "成都市", "武侯区", "武侯区一环路南三段 88 号", "028-85234567",
                    "17:00-02:00", "炭火现烤，五花肉厚切。",
                    30.64, 104.08, 1.2, 90, true, 24, 1, 2810, 356, 142, 28, 19, 6),
            new ShopSeed("s_004", "一味面馆", "面食", "成都市", "武侯区", "武侯区人民南路 33 号", "028-85456789",
                    "07:00-20:00", "红油抄手，皮薄馅大。",
                    30.63, 104.06, 0.5, 70, false, 24, 1, 1180, 142, 58, 9, 31, 9),
            new ShopSeed("s_007", "陈记冒菜", "川菜", "成都市", "武侯区", "武侯区双楠路 21 号", "028-85789012",
                    "10:30-21:30", "一锅一煮，麻辣自选。",
                    30.62, 104.04, 1.6, 80, false, 24, 1, 1640, 208, 96, 17, 12, 4),
            new ShopSeed("s_012", "玉林路小酒馆", "烧烤", "成都市", "武侯区", "武侯区玉林西路 55 号", "028-85234560",
                    "18:00-03:00", "把把烧配冰啤酒。",
                    30.61, 104.05, 0.9, 80, false, 24, 1, 2260, 298, 134, 41, 22, 7),

            // ---- 成都 · 锦江区 ----
            new ShopSeed("s_003", "川味坊", "川菜", "成都市", "锦江区", "锦江区春熙路 5 号", "028-85345678",
                    "10:00-21:00", "家常川菜，回锅肉一绝。",
                    30.66, 104.09, 2.1, 90, true, 24, 1, 1420, 168, 62, 12, 8, 7),
            new ShopSeed("s_005", "樱町日料", "日料", "成都市", "锦江区", "锦江区红星路三段 1 号", "028-85567890",
                    "11:30-22:00", "每日空运，蓝鳍金枪鱼限量。",
                    30.67, 104.10, 3.4, 90, true, 24, 1, 1960, 288, 210, 44, 6, 9),
            new ShopSeed("s_008", "牛市口钵钵鸡", "小吃", "成都市", "锦江区", "锦江区牛市口街 9 号", "028-85890123",
                    "11:00-23:00", "藤椒味最正，签子按根算。",
                    30.69, 104.11, 2.4, 70, false, 24, 1, 1780, 246, 118, 22, 34, 3),
            new ShopSeed("s_010", "青石桥海鲜大排档", "粤菜", "成都市", "锦江区", "锦江区青石桥中街 7 号", "028-85012345",
                    "17:00-02:00", "现杀现做，蒜蓉粉丝扇贝。",
                    30.66, 104.08, 3.8, 60, false, 24, 1, 1520, 196, 88, 16, 11, 12),
            new ShopSeed("s_013", "春熙路茶餐厅", "粤菜", "成都市", "锦江区", "锦江区中纱帽街 12 号", "028-85345670",
                    "10:00-22:00", "菠萝油和丝袜奶茶是招牌。",
                    30.65, 104.09, 2.9, 60, false, 24, 1, 1340, 172, 74, 13, 9, 14),

            // ---- 成都 · 青羊区 ----
            new ShopSeed("s_006", "老字号甜水面", "小吃", "成都市", "青羊区", "青羊区宽窄巷子 8 号", "028-85678901",
                    "09:00-19:00", "一根面拇指粗，酱料甜辣。",
                    30.68, 104.05, 4.2, 70, false, 24, 1, 980, 176, 84, 15, 47, 10),
            new ShopSeed("s_011", "老成都锅盔", "小吃", "成都市", "青羊区", "青羊区文殊院街 15 号", "028-85123450",
                    "07:30-19:00", "军屯锅盔，现烤现卖。",
                    30.67, 104.06, 4.6, 70, false, 24, 1, 1120, 158, 66, 14, 28, 8),

            // ---- 成都 · 高新区 ----
            new ShopSeed("s_009", "高新串串实验室", "火锅", "成都市", "高新区", "高新区天府三街 199 号", "028-85901234",
                    "16:00-01:00", "锅底自己配，牛油现炒。",
                    30.55, 104.06, 5.2, 95, true, 24, 1, 3480, 512, 264, 58, 33, 2),
            new ShopSeed("s_016", "天府三街寿司郎", "日料", "成都市", "高新区", "高新区天府三街 288 号", "028-85678900",
                    "11:00-22:30", "回转寿司，人均亲民。",
                    30.54, 104.08, 8.4, 60, false, 24, 1, 1060, 138, 58, 11, 7, 16),

            // ---- 成都 · 成华区 ----
            new ShopSeed("s_014", "建设路烤鱼", "火锅", "成都市", "成华区", "成华区建设路 26 号", "028-85456780",
                    "16:30-01:00", "万州烤鱼，麻辣与蒜香双拼。",
                    30.67, 104.13, 6.1, 60, false, 24, 1, 1880, 264, 124, 26, 18, 5),
            new ShopSeed("s_017", "玉双路糖水铺", "甜品", "成都市", "成华区", "成华区玉双路 3 号", "028-85789010",
                    "12:00-23:00", "广式糖水，姜撞奶现撞。",
                    30.66, 104.12, 5.5, 50, false, 24, 1, 860, 214, 132, 19, 41, 11),
            new ShopSeed("s_019", "东郊记忆西餐厅", "西餐", "成都市", "成华区", "成华区建设南支路 4 号", "028-85901230",
                    "11:00-22:00", "牛排现切，环境安静。",
                    30.65, 104.14, 9.8, 50, false, 24, 1, 740, 96, 44, 8, 4, 13),

            // ---- 成都 · 金牛区 ----
            new ShopSeed("s_015", "抚琴豆花面", "面食", "成都市", "金牛区", "金牛区抚琴西路 44 号", "028-85567890",
                    "06:30-14:00", "豆花嫩，红油香。",
                    30.70, 104.05, 7.3, 60, false, 24, 1, 920, 128, 52, 10, 16, 15),
            new ShopSeed("s_018", "湘遇小炒", "湘菜", "成都市", "金牛区", "金牛区解放路二段 18 号", "028-85890120",
                    "11:00-21:30", "剁椒鱼头够辣。",
                    30.68, 104.03, 6.8, 60, false, 24, 1, 1240, 164, 72, 21, 9, 12),

            // ---- 成都 · 成华区 / 金牛区 / 武侯区（继续补量）----
            new ShopSeed("s_020", "老绵阳米粉", "小吃", "成都市", "成华区", "成华区双桥路 18 号", "028-86123456",
                    "06:00-14:00", "米粉细滑，红汤清汤都行。",
                    30.66, 104.11, 1.4, 80, false, 24, 1, 1660, 232, 104, 27, 38, 6),
            new ShopSeed("s_021", "老码头火锅", "火锅", "成都市", "金牛区", "金牛区西安中路 9 号", "028-86234567",
                    "17:00-02:00", "老码头牛油锅，本地人常去。",
                    30.67, 104.04, 2.7, 70, false, 24, 1, 1480, 206, 92, 24, 15, 8),
            new ShopSeed("s_022", "炭匠烤肉", "烧烤", "成都市", "武侯区", "武侯区外双楠 88 号", "028-86345678",
                    "17:30-01:00", "大块牛排串，分量足。",
                    30.60, 104.03, 3.3, 60, false, 24, 1, 1020, 144, 64, 12, 6, 10)
    );

    /** 待审核队列（客户端完全看不到）。 */
    private record PendingSeed(String id, String name, String cuisine, String city, String district,
                               String address, String phone, String hours, String intro,
                               int submittedDaysAgo, int submittedHour) {}

    private static final List<PendingSeed> PENDING = List.of(
            new PendingSeed("p_001", "新开的螺蛳粉", "小吃", "成都市", "成华区",
                    "成华区建设路 66 号", "028-88887777", "10:00-23:00",
                    "正宗柳州味道，酸笋每天现发。", 1, 9),
            new PendingSeed("p_002", "巷子口串串香", "火锅", "成都市", "金牛区",
                    "金牛区抚琴西路 8 号", "028-88888888", "16:00-03:00",
                    "老巷子里的苍蝇馆子，开了七年。", 1, 10),
            new PendingSeed("p_003", "深夜豆浆油条", "小吃", "成都市", "锦江区",
                    "锦江区东大街 41 号", "028-88889999", "22:00-06:00",
                    "专做夜宵档，豆浆现磨。", 0, 8),
            new PendingSeed("p_004", "城南潮汕牛肉锅", "火锅", "成都市", "高新区",
                    "高新区府城大道 128 号", "028-88886666", "11:00-23:00",
                    "现宰黄牛，八秒吊龙。", 0, 9),
            new PendingSeed("p_005", "锦江老面馆", "面食", "成都市", "锦江区",
                    "锦江区梨花街 22 号", "0816-2288999", "06:30-20:00",
                    "开了二十年的老面馆，杂酱面最出名。", 2, 11)
    );

    /** 已驳回（用于演示驳回理由回显）。 */
    private record RejectedSeed(String id, String name, String cuisine, String city, String district,
                                String address, String intro, int submittedDaysAgo,
                                String reason, int reviewedDaysAgo) {}

    private static final List<RejectedSeed> REJECTED = List.of(
            new RejectedSeed("p_r1", "无名小摊", "小吃", "成都市", "金牛区", "成都市某处", "",
                    7, "门头图不清晰，无法辨认店铺招牌，且未填写详细地址", 6),
            new RejectedSeed("p_r2", "皇家御膳私房菜", "川菜", "成都市", "武侯区", "武侯区某写字楼", "主打高端宴请。",
                    4, "营业执照与经营主体不一致，且上传的门头照为效果图而非实拍", 3)
    );

    private void seedShops() {
        for (ShopSeed x : SHOPS) {
            Shop s = new Shop();
            s.setId(x.id());
            s.setName(x.name());
            s.setCuisine(x.cuisine());
            s.setCity(x.city());
            s.setDistrict(x.district());
            s.setAddress(x.address());
            s.setPhone(x.phone());
            s.setHours(x.hours());
            s.setIntro(x.intro());
            s.setLat(x.lat());
            s.setLng(x.lng());
            s.setDistance(x.distance());
            s.setStatus("normal");
            s.setWeight(x.weight());
            s.setPinned(x.pinned());
            s.setIntervalHours(x.intervalHours());
            s.setDailyLimit(x.dailyLimit());
            s.setStatViews(x.views());
            s.setStatLikes(x.likes());
            s.setStatFavorites(x.fav());
            s.setStatComments(x.cmt());
            s.setStatCheckins(x.checkin());
            // 审核痕迹 —— 平台端「已通过」列表据此显示提交/审核时间
            s.setSubmittedAt(fmt(ts(x.reviewDaysAgo(), 8, 40)));
            s.setReviewedAt(fmt(ts(x.reviewDaysAgo(), 14, 20)));
            s.setReviewer("平台运营");
            s.setLogo(picsum(x.id() + "logo", 200, 200));
            s.setCover(picsum(x.id() + "cover", 800, 600));
            shopRepo.save(s);
        }

        for (PendingSeed x : PENDING) {
            Shop s = baseShop(x.id(), x.name(), x.cuisine(), x.city(), x.district(),
                    x.address(), x.phone(), x.hours(), x.intro());
            s.setStatus("pending");
            s.setSubmittedAt(fmt(ts(x.submittedDaysAgo(), x.submittedHour(), 15)));
            s.setReviewedAt(null);
            s.setRejectReason(null);
            shopRepo.save(s);
        }

        for (RejectedSeed x : REJECTED) {
            Shop s = baseShop(x.id(), x.name(), x.cuisine(), x.city(), x.district(),
                    x.address(), "028-00000000", "不定", x.intro());
            s.setStatus("rejected");
            s.setSubmittedAt(fmt(ts(x.submittedDaysAgo(), 9, 0)));
            s.setReviewedAt(fmt(ts(x.reviewedDaysAgo(), 10, 20)));
            s.setReviewer("平台运营");
            s.setRejectReason(x.reason());
            shopRepo.save(s);
        }
    }

    /**
     * 待审核 / 已驳回店铺的公共部分。
     * 距离必须是 null 而不是 0 —— 0 在排序里等于「就在你脚下」，
     * 会把还没上线的店顶到推荐榜首。权重同理给 0。
     */
    private Shop baseShop(String id, String name, String cuisine, String city, String district,
                          String address, String phone, String hours, String intro) {
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
        s.setLat(31.0);
        s.setLng(104.3);
        s.setDistance(null);
        s.setWeight(0);
        s.setPinned(false);
        s.setCanPostToday(false);
        s.setIntervalHours(24);
        s.setDailyLimit(1);
        s.setStatViews(0);
        s.setStatLikes(0);
        s.setStatFavorites(0);
        s.setStatComments(0);
        s.setStatCheckins(0);
        s.setLogo(picsum(id + "logo", 200, 200));
        s.setCover(picsum(id + "cover", 800, 600));
        return s;
    }

    // ==================== 菜品 ====================

    /**
     * 54 道菜，横跨近 7 天。
     * 分布刻意不均匀但每天都有 —— 趋势图 7 根柱子必须都有值，
     * 否则一眼看去像后端坏了。
     */
    private static final List<DishSeed> DISHES = List.of(
            // ---- 今天 ----
            new DishSeed("d_001", "s_001", "麻婆豆腐", "石磨豆腐配自家花椒，麻辣鲜香。", "image",
                    28, "real", "麻辣", 0, 9, 30, 3200, 412, 188, 36, 24),
            new DishSeed("d_002", "s_002", "炭烤五花肉", "厚切带皮五花，炭火慢烤四十分钟，外焦里嫩。", "video",
                    68, "real", "烧烤", 0, 8, 45, 2810, 356, 142, 28, 19),
            new DishSeed("d_003", "s_003", "回锅肉", "二刀肉先煮后炒，豆瓣是自家晒的。", "image",
                    42, "ad", "麻辣", 0, 8, 20, 1420, 168, 62, 12, 8),
            new DishSeed("d_004", "s_005", "蓝鳍金枪鱼大腹", "每日空运，限量六份。油脂分布像雪花一样。", "image",
                    288, "real", "日料,清淡", 0, 9, 5, 1960, 288, 210, 44, 6),
            new DishSeed("d_005", "s_007", "冒菜小锅", "一锅一煮，麻辣度可选。", "image",
                    36, "real", "麻辣,火锅", 0, 10, 20, 1640, 208, 96, 17, 12),
            new DishSeed("d_006", "s_008", "藤椒钵钵鸡", "藤椒油现淋，麻得清爽。", "image",
                    38, "real", "麻辣,小吃", 0, 11, 10, 1780, 246, 118, 22, 34),
            new DishSeed("d_007", "s_009", "现炒牛油锅底", "牛油加二十余味香料现炒，端上桌还在滚。", "video",
                    58, "real", "麻辣,火锅", 0, 9, 50, 3480, 512, 264, 58, 33),
            new DishSeed("d_008", "s_011", "军屯锅盔", "现烤现卖，椒盐味最传统。", "image",
                    10, "real", "小吃", 0, 12, 40, 1120, 158, 66, 14, 28),
            new DishSeed("d_009", "s_012", "把把烧", "小串一次上二十把，配冰啤酒。", "video",
                    58, "real", "烧烤,麻辣", 0, 13, 20, 2260, 298, 134, 41, 22),
            new DishSeed("d_010", "s_014", "万州烤鱼", "先烤后炖，麻辣与蒜香双拼。", "image",
                    108, "real", "麻辣,火锅", 0, 14, 15, 1880, 264, 124, 26, 18),
            new DishSeed("d_011", "s_020", "绵阳米粉", "红汤米粉，臊子是牛肉。", "image",
                    12, "real", "麻辣,小吃,面食", 0, 7, 20, 1660, 232, 104, 27, 38),

            // ---- 1 天前 ----
            new DishSeed("d_012", "s_003", "蒜泥白肉", "肉片薄可透光，蒜泥现舂。", "image",
                    46, "real", "麻辣", 1, 12, 30, 1280, 154, 58, 11, 7),
            new DishSeed("d_013", "s_004", "红油抄手", "皮薄如纸，红油是自家舂的辣椒。", "image",
                    18, "real", "麻辣,面食", 1, 7, 40, 1180, 142, 58, 9, 31),
            new DishSeed("d_014", "s_006", "甜水面", "一根面有拇指粗，酱料甜中带辣。", "image",
                    12, "real", "小吃,面食,甜点", 1, 10, 15, 980, 176, 84, 15, 47),
            new DishSeed("d_015", "s_009", "手打虾滑", "整只青虾手打，弹牙。", "video",
                    46, "real", "清淡,火锅", 1, 17, 30, 2980, 436, 228, 52, 29),
            new DishSeed("d_016", "s_010", "蒜蓉粉丝扇贝", "现杀现做，粉丝吸满蒜蓉汁。", "image",
                    78, "ad", "清淡", 1, 18, 10, 1520, 196, 88, 16, 11),
            new DishSeed("d_017", "s_015", "豆花面", "豆花嫩，红油香，面是细的。", "image",
                    14, "real", "麻辣,面食", 1, 7, 10, 920, 128, 52, 10, 16),
            new DishSeed("d_018", "s_016", "回转寿司拼盘", "八贯拼盘，师傅现场捏。", "image",
                    88, "real", "日料,清淡", 1, 11, 40, 1060, 138, 58, 11, 7),
            new DishSeed("d_019", "s_018", "剁椒鱼头", "剁椒自家腌，鱼头两斤起。", "image",
                    88, "real", "麻辣", 1, 12, 50, 1240, 164, 72, 21, 9),
            new DishSeed("d_020", "s_021", "越王楼牛油锅", "本地牛油锅，香而不燥。", "image",
                    68, "real", "麻辣,火锅", 1, 18, 30, 1480, 206, 92, 24, 15),

            // ---- 2 天前 ----
            new DishSeed("d_021", "s_001", "水煮牛肉", "牛肉片现片，热油一泼。", "image",
                    48, "real", "麻辣", 2, 12, 10, 3120, 388, 172, 33, 21),
            new DishSeed("d_022", "s_007", "藤椒鸡片", "藤椒清麻，鸡肉嫩。", "image",
                    42, "real", "麻辣", 2, 11, 50, 1580, 196, 88, 15, 10),
            new DishSeed("d_023", "s_008", "红油钵钵鸡", "经典红油，微甜收口。", "image",
                    38, "real", "麻辣,小吃", 2, 12, 20, 1720, 232, 110, 20, 31),
            new DishSeed("d_024", "s_012", "烤鸡皮", "烤到起泡，脆得响。", "image",
                    28, "real", "烧烤", 2, 19, 50, 2180, 282, 128, 38, 20),
            new DishSeed("d_025", "s_013", "菠萝油", "现烤菠萝包夹冰黄油。", "image",
                    18, "real", "甜点", 2, 9, 30, 1340, 172, 74, 13, 9),
            new DishSeed("d_026", "s_014", "蒜香烤鱼", "蒜香版本，不吃辣也能吃。", "image",
                    98, "real", "清淡,火锅", 2, 18, 40, 1820, 248, 116, 24, 16),
            new DishSeed("d_027", "s_017", "姜撞奶", "现撞现凝，姜味冲。", "image",
                    22, "real", "甜点,清淡", 2, 13, 30, 860, 214, 132, 19, 41),
            new DishSeed("d_028", "s_019", "战斧牛排", "整块战斧现切，五分熟最佳。", "image",
                    268, "real", "清淡", 2, 19, 10, 740, 96, 44, 8, 4),
            new DishSeed("d_029", "s_022", "大块牛排串", "牛排切块串起来烤，分量足。", "video",
                    118, "real", "烧烤", 2, 20, 0, 1020, 144, 64, 12, 6),

            // ---- 3 天前 ----
            new DishSeed("d_030", "s_002", "烤脑花", "麻辣重口，老饕最爱。", "image",
                    45, "real", "麻辣,烧烤", 3, 19, 20, 2760, 342, 138, 27, 18),
            new DishSeed("d_031", "s_005", "三文鱼刺身拼盘", "厚切三文鱼配现磨山葵。", "image",
                    138, "real", "日料,清淡", 3, 12, 0, 1920, 276, 198, 42, 6),
            new DishSeed("d_032", "s_009", "鲜毛肚", "当天现发，七上八下。", "video",
                    52, "real", "麻辣,火锅", 3, 18, 20, 3320, 486, 248, 55, 31),
            new DishSeed("d_033", "s_011", "牛肉锅盔", "夹牛肉馅的，一个顶俩。", "image",
                    14, "real", "小吃", 3, 8, 30, 1080, 146, 62, 13, 26),
            new DishSeed("d_034", "s_018", "小炒黄牛肉", "黄牛肉配小米辣，下饭。", "image",
                    68, "real", "麻辣", 3, 11, 20, 1180, 154, 68, 19, 8),
            new DishSeed("d_035", "s_020", "清汤米粉", "清汤底更鲜，早上吃一碗。", "image",
                    10, "real", "清淡,汤类,小吃", 3, 6, 50, 1600, 218, 98, 25, 36),

            // ---- 4 天前 ----
            new DishSeed("d_036", "s_003", "麻婆豆腐盖饭", "一人食版本，配一碗米饭。", "image",
                    26, "real", "麻辣", 4, 18, 50, 1360, 158, 60, 11, 7),
            new DishSeed("d_037", "s_004", "担担面", "干拌担担面，芽菜是灵魂。", "image",
                    16, "real", "麻辣,面食", 4, 8, 10, 1140, 136, 54, 9, 29),
            new DishSeed("d_038", "s_008", "鸡杂钵钵鸡", "鸡杂脆爽，重口党喜欢。", "image",
                    42, "real", "麻辣,小吃", 4, 11, 30, 1700, 228, 106, 19, 30),
            new DishSeed("d_039", "s_010", "白灼虾", "活虾白灼，蘸生抽小米辣。", "image",
                    98, "real", "清淡", 4, 19, 30, 1460, 188, 82, 15, 10),
            new DishSeed("d_040", "s_016", "玉子烧", "现做的厚蛋烧，微甜。", "image",
                    18, "real", "日料,甜点", 4, 12, 10, 1020, 132, 54, 10, 6),
            new DishSeed("d_041", "s_021", "鲜鸭肠", "烫八秒，脆得很。", "image",
                    58, "real", "麻辣,火锅", 4, 17, 40, 1420, 198, 88, 22, 14),

            // ---- 5 天前 ----
            new DishSeed("d_042", "s_001", "宫保鸡丁", "荔枝口味，花生米现炸。", "image",
                    38, "real", "酸甜", 5, 18, 40, 3040, 376, 166, 31, 20),
            new DishSeed("d_043", "s_006", "三大炮", "糯米团现捶，黄豆粉裹满。", "image",
                    15, "real", "小吃,甜点", 5, 9, 40, 940, 168, 80, 14, 44),
            new DishSeed("d_044", "s_007", "干拌冒菜", "不带汤，调料挂得住。", "image",
                    32, "real", "麻辣", 5, 19, 10, 1560, 192, 84, 14, 9),
            new DishSeed("d_045", "s_012", "烤韭菜", "一把三块钱，蒜蓉味。", "image",
                    15, "real", "烧烤", 5, 20, 30, 2100, 268, 120, 35, 19),
            new DishSeed("d_046", "s_013", "丝袜奶茶", "茶味重，不甜腻。", "image",
                    16, "real", "甜点", 5, 10, 20, 1300, 166, 70, 12, 8),
            new DishSeed("d_047", "s_017", "双皮奶", "奶皮厚，红豆是自家煮的。", "image",
                    20, "real", "甜点", 5, 14, 0, 820, 202, 124, 18, 38),
            new DishSeed("d_048", "s_022", "烤羊排", "整扇羊排烤，撒孜然。", "video",
                    168, "real", "烧烤", 5, 19, 20, 980, 138, 60, 11, 5),

            // ---- 6 天前 ----
            new DishSeed("d_049", "s_002", "烤茄子", "整只茄子烤软，蒜蓉铺满。", "image",
                    22, "real", "烧烤,清淡", 6, 20, 10, 2700, 330, 132, 26, 17),
            new DishSeed("d_050", "s_005", "鳗鱼饭", "蒲烧鳗鱼，酱汁偏甜。", "image",
                    88, "real", "日料,甜点", 6, 18, 30, 1880, 268, 192, 40, 6),
            new DishSeed("d_051", "s_009", "冰粉", "红糖冰粉配醪糟，解辣。", "image",
                    12, "real", "甜点,清淡", 6, 19, 0, 3260, 472, 242, 54, 30),
            new DishSeed("d_052", "s_014", "麻辣烤鱼", "最辣的那一档，慎点。", "image",
                    118, "real", "麻辣,火锅", 6, 17, 50, 1800, 242, 112, 23, 15),
            new DishSeed("d_053", "s_015", "素椒杂酱面", "杂酱炒得干香，面有嚼劲。", "image",
                    16, "real", "面食,麻辣", 6, 8, 20, 880, 118, 48, 9, 14),
            new DishSeed("d_054", "s_019", "意面", "番茄肉酱，面条有嚼劲。", "image",
                    68, "real", "清淡", 6, 12, 30, 700, 88, 40, 7, 3)
    );

    /**
     * 预置为「已下架」的菜品。
     * 必须与下面 REPORTS 里 status=confirmed 的菜品举报对应上 ——
     * 举报确认成立会自动下架菜品，如果这里还是 normal，
     * 就会出现「举报已确认，但菜还挂在线上」的自相矛盾状态。
     */
    private static final Set<String> REMOVED_DISH_IDS = Set.of("d_028", "d_052");

    private void seedDishes() {
        Map<String, String> shopNames = new HashMap<>();
        for (Shop s : shopRepo.findAll()) shopNames.put(s.getId(), s.getName());

        for (DishSeed x : DISHES) {
            Dish d = new Dish();
            d.setId(x.id());
            d.setShopId(x.shopId());
            d.setShopName(shopNames.getOrDefault(x.shopId(), ""));
            d.setName(x.name());
            d.setDescr(x.desc());
            d.setType(x.type());
            List<String> media = List.of(
                    picsum(x.id() + "a", 800, 1200),
                    picsum(x.id() + "b", 800, 1200));
            // 视频只留一个媒体位（真实场景就是一段视频）
            if ("video".equals(x.type())) media = List.of(picsum(x.id() + "a", 800, 1200));
            d.setMedia(toJson(media));
            d.setCover(media.get(0));
            d.setPrice(x.price());
            // 档位由价格算出，和平台配置共用同一套规则 —— 不手写，避免两边漂移
            d.setPriceTierId(configService.resolveTier(x.price()));
            d.setRealTag(x.realTag());
            d.setTasteTags(toJson(splitCsv(x.tastes())));
            long published = ts(x.daysAgo(), x.hour(), x.minute());
            d.setPublishedTs(published);
            d.setPublishedAt(fmt(published));
            // 下架不是物理删除 —— 记录留着，只是客户端看不到
            d.setStatus(REMOVED_DISH_IDS.contains(x.id()) ? "removed" : "normal");
            d.setStatViews(x.views());
            d.setStatLikes(x.likes());
            d.setStatFavorites(x.fav());
            d.setStatComments(x.cmt());
            d.setStatCheckins(x.checkin());
            dishRepo.save(d);
        }
    }

    /**
     * 用「该店最新一条菜品」反推店铺的 lastPublishAt / canPostToday。
     *
     * <p>手写这两个字段一定会和菜品对不上（比如菜品是今天发的、店铺却显示三天没发），
     * 反推之后两者天然一致。canPostToday 同时考虑「间隔未到」和「今日条数已满」。
     */
    private void syncShopPublishTimes() {
        long now = System.currentTimeMillis();
        long todayStart = todayStart();

        Map<String, Long> latest = new HashMap<>();
        Map<String, Integer> todayCount = new HashMap<>();
        for (Dish d : dishRepo.findAll()) {
            Long t = d.getPublishedTs();
            if (t == null) continue;
            latest.merge(d.getShopId(), t, Math::max);
            if (t >= todayStart) todayCount.merge(d.getShopId(), 1, Integer::sum);
        }

        for (Shop s : shopRepo.findAll()) {
            Long t = latest.get(s.getId());
            if (t == null) continue;   // 待审核 / 已驳回的店没有内容，保持 canPostToday=false
            int interval = s.getIntervalHours() == null ? 24 : s.getIntervalHours();
            int limit = s.getDailyLimit() == null ? 1 : s.getDailyLimit();
            boolean intervalOk = (now - t) >= interval * HOUR;
            s.setLastPublishAt(t);
            s.setCanPostToday(intervalOk && todayCount.getOrDefault(s.getId(), 0) < limit);
            shopRepo.save(s);
        }
    }

    // ==================== 用户 ====================

    /**
     * u_003 的举报数刻意给到最高（5），保证平台端「按被举报次数倒序」有一个
     * 确定的首位 —— 否则并列时的顺序由数据库决定，测试和截图都会飘。
     */
    private static final List<UserSeed> USERS = List.of(
            new UserSeed("u_001", "小吃货", "13800000001", "成都市", "武侯区", "normal", 12, 0, "2026-08-01"),
            new UserSeed("u_002", "隔壁老王", "13800000002", "成都市", "武侯区", "normal", 45, 0, "2026-07-12"),
            new UserSeed("u_003", "干饭人小李", "13800000003", "成都市", "锦江区", "warned", 88, 5, "2026-06-20"),
            new UserSeed("u_004", "夜宵战神", "13800000004", "成都市", "青羊区", "normal", 26, 0, "2026-08-15"),
            new UserSeed("u_005", "麻辣小丸子", "13800000005", "成都市", "锦江区", "normal", 33, 1, "2026-07-28"),
            new UserSeed("u_006", "一只吃货", "13800000006", "成都市", "高新区", "normal", 51, 0, "2026-06-05"),
            new UserSeed("u_007", "成都老饕", "13800000007", "成都市", "武侯区", "normal", 67, 2, "2026-05-18"),
            new UserSeed("u_008", "深夜放毒", "13800000008", "成都市", "成华区", "banned", 94, 4, "2026-04-30"),
            new UserSeed("u_009", "甜品控", "13800000009", "成都市", "青羊区", "normal", 21, 0, "2026-08-22"),
            new UserSeed("u_010", "螺蛳粉星人", "13800000010", "成都市", "金牛区", "normal", 18, 1, "2026-08-08"),
            new UserSeed("u_011", "减脂餐战士", "13800000011", "成都市", "高新区", "normal", 9, 0, "2026-09-01"),
            new UserSeed("u_012", "串串狂热者", "13800000012", "成都市", "武侯区", "warned", 72, 3, "2026-05-06"),
            new UserSeed("u_013", "只喝汤", "13800000013", "成都市", "武侯区", "normal", 14, 0, "2026-08-19"),
            new UserSeed("u_014", "面食之王", "13800000014", "成都市", "金牛区", "normal", 29, 1, "2026-07-03")
    );

    private void seedUsers() {
        for (UserSeed x : USERS) {
            AppUser u = new AppUser();
            u.setId(x.id());
            u.setName(x.name());
            u.setPhone(x.phone());
            u.setCity(x.city());
            u.setDistrict(x.district());
            u.setStatus(x.status());
            u.setCommentCount(x.comments());
            u.setReportCount(x.reports());
            u.setAt(x.at());
            u.setAvatar(picsum(x.id(), 100, 100));
            userRepo.save(u);
        }
    }

    // ==================== 评论 ====================

    /** hoursAgo 用相对小时即可 —— 评论不参与趋势图，只用于"几小时前"展示。 */
    private static final List<CommentSeed> COMMENTS = List.of(
            new CommentSeed("c_001", "d_001", "u_002", 2, "昨天去吃了，豆腐嫩得离谱，配米饭绝了。",
                    "谢谢支持！下次来给您多加点花椒~"),
            new CommentSeed("c_002", "d_001", "u_003", 1, "价格是不是涨了？之前好像 24。", null),
            new CommentSeed("c_003", "d_002", "u_004", 5, "烤四十分钟是真的，等的时候有点久但值得。", null),
            new CommentSeed("c_004", "d_007", "u_012", 1, "牛油锅底是真的香，就是吃完衣服味儿大。", null),
            new CommentSeed("c_005", "d_004", "u_007", 3, "这个价位能吃到这个品质，值。", "感谢认可，欢迎再来~"),
            new CommentSeed("c_006", "d_006", "u_005", 2, "藤椒味比红油更清爽，推荐。", null),
            new CommentSeed("c_007", "d_006", "u_001", 4, "按根算有点贵，一不小心就超预算。", null),
            new CommentSeed("c_008", "d_009", "u_004", 2, "把把烧配冰啤酒，夏夜标配。", null),
            new CommentSeed("c_009", "d_010", "u_006", 6, "蒜香那半边比麻辣好吃，意外。", null),
            new CommentSeed("c_010", "d_011", "u_013", 1, "绵阳人表示这个是正宗的味道。", "老乡好！"),
            new CommentSeed("c_011", "d_013", "u_001", 3, "抄手皮薄到能看见馅，好评。", null),
            new CommentSeed("c_012", "d_014", "u_009", 5, "甜水面第一口惊艳，第三口有点腻。", null),
            new CommentSeed("c_013", "d_015", "u_012", 4, "虾滑是真的手打，能吃到虾肉颗粒。", null),
            new CommentSeed("c_014", "d_017", "u_008", 7, "这家店卫生一般，建议改进。", null),
            new CommentSeed("c_015", "d_019", "u_007", 3, "剁椒够劲，配米饭能干三碗。", null),
            new CommentSeed("c_016", "d_021", "u_002", 20, "水煮牛肉的牛肉片很嫩，没柴。", null),
            new CommentSeed("c_017", "d_024", "u_004", 26, "鸡皮烤得脆，下酒一流。", null),
            new CommentSeed("c_018", "d_027", "u_009", 30, "姜撞奶姜味够冲，我喜欢。", null),
            new CommentSeed("c_019", "d_030", "u_008", 50, "烤脑花处理得干净，没有腥味。", null),
            new CommentSeed("c_020", "d_031", "u_007", 55, "刺身厚度合适，山葵是现磨的。", null),
            new CommentSeed("c_021", "d_032", "u_012", 60, "毛肚七上八下刚好，别烫久了。", null),
            new CommentSeed("c_022", "d_035", "u_013", 40, "清汤才是检验米粉的标准。", null),
            new CommentSeed("c_023", "d_042", "u_005", 90, "宫保鸡丁酸甜口调得很准。", null),
            new CommentSeed("c_024", "d_043", "u_009", 95, "三大炮现场捶的，很有仪式感。", null),
            new CommentSeed("c_025", "d_048", "u_013", 100, "羊排分量足，两个人吃刚好。", null),
            new CommentSeed("c_026", "d_049", "u_003", 110, "茄子烤得偏油了，希望改进。", "收到，已经反馈给后厨了。"),
            new CommentSeed("c_027", "d_051", "u_010", 120, "冰粉解辣一流，必点。", null),
            new CommentSeed("c_028", "d_053", "u_011", 130, "杂酱面分量对减脂人士不太友好哈。", null)
    );

    private void seedComments() {
        long now = System.currentTimeMillis();
        Map<String, AppUser> users = new HashMap<>();
        for (AppUser u : userRepo.findAll()) users.put(u.getId(), u);

        for (CommentSeed x : COMMENTS) {
            Comment c = new Comment();
            c.setId(x.id());
            c.setDishId(x.dishId());
            c.setUserId(x.userId());
            AppUser u = users.get(x.userId());
            c.setUserName(u == null ? "" : u.getName());
            c.setContent(x.content());
            long atTs = now - x.hoursAgo() * HOUR;
            c.setAtTs(atTs);
            c.setAt(fmt(atTs));
            c.setStatus("normal");
            c.setAvatar(picsum(x.userId(), 100, 100));
            if (x.reply() != null) {
                c.setReplyContent(x.reply());
                // 回评在评论之后 30 分钟
                c.setReplyAt(fmt(atTs + 30 * 60_000L));
            }
            commentRepo.save(c);
        }
    }

    // ==================== 举报 ====================

    /** 一半待处理、一半已处理 —— 平台端「待处理举报」和「已处理」两个列表都有内容。 */
    private static final List<ReportSeed> REPORTS = List.of(
            new ReportSeed("r_001", "dish", "d_003", "回锅肉", "图片与实物不符", "干饭人小李", 0, "pending"),
            new ReportSeed("r_002", "comment", "c_002", "评论：价格是不是涨了", "恶意刷差评", "蜀香小馆", 0, "pending"),
            new ReportSeed("r_003", "dish", "d_016", "蒜蓉粉丝扇贝", "价格虚高，涉嫌虚假宣传", "麻辣小丸子", 1, "pending"),
            new ReportSeed("r_004", "comment", "c_014", "评论：这家店卫生一般", "恶意差评，与事实不符", "抚琴豆花面", 1, "pending"),
            new ReportSeed("r_005", "dish", "d_052", "麻辣烤鱼", "疑似盗用他人图片", "成都老饕", 2, "confirmed"),
            new ReportSeed("r_006", "comment", "c_019", "评论：烤脑花处理得干净", "广告嫌疑", "老李炭火烧烤", 3, "rejected"),
            new ReportSeed("r_007", "dish", "d_028", "战斧牛排", "分量与描述不符", "一只吃货", 4, "confirmed"),
            new ReportSeed("r_008", "comment", "c_026", "评论：茄子烤得偏油了", "正常差评，被商家恶意举报", "甜品控", 5, "rejected"),
            new ReportSeed("r_009", "dish", "d_048", "烤羊排", "盗图", "串串狂热者", 3, "pending")
    );

    private void seedReports() {
        for (ReportSeed x : REPORTS) {
            Report r = new Report();
            r.setId(x.id());
            r.setType(x.type());
            r.setTargetId(x.targetId());
            r.setTargetName(x.targetName());
            r.setReason(x.reason());
            r.setReporter(x.reporter());
            r.setAt(fmt(ts(x.daysAgo(), 11, 20)));
            r.setStatus(x.status());
            reportRepo.save(r);
        }
    }

    // ==================== 工具 ====================

    private String picsum(String seed, int w, int h) {
        return "https://picsum.photos/seed/" + seed + "/" + w + "/" + h;
    }

    private List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (Exception e) { return "[]"; }
    }
}
