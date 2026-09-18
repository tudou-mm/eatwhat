package com.eatwhat.config;

import com.eatwhat.common.DistanceCalculator;
import com.eatwhat.controller.MerchantController;
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
 * <p><b>店铺基础信息是真实数据</b>：店名 / 地址 / 经纬度 / 营业时间 / 电话取自
 * POI 采集库 {@code yizheng-food-radar}（仪征市 2582 家餐饮 POI，高德合规采集）。
 * 菜品、评论、互动数仍是构造的 —— 高德不提供菜品级数据，这是数据源的边界。
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

    /**
     * 建店铺用。直接注入 Controller 而不是自己 new Shop，
     * 是为了让「演示数据的待审店铺」和「商家真入驻的店铺」**走同一条代码路径** ——
     * 否则哪天改了 createPending 的规则，演示数据还按老规则躺着，
     * 上线验收时才会发现待审列表里全是特例。
     */
    private final MerchantController merchantController;

    @Value("${eatwhat.seed:true}")
    private boolean enabled;

    public DataSeeder(ShopRepository shopRepo, DishRepository dishRepo, CommentRepository commentRepo,
                      AppUserRepository userRepo, ReportRepository reportRepo,
                      ConfigService configService, MerchantController merchantController) {
        this.shopRepo = shopRepo;
        this.dishRepo = dishRepo;
        this.commentRepo = commentRepo;
        this.userRepo = userRepo;
        this.reportRepo = reportRepo;
        this.configService = configService;
        this.merchantController = merchantController;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        if (shopRepo.count() > 0) {
            log.info("演示数据已存在，跳过初始化");
            return;
        }
        log.info("正在灌入演示数据…");

        seedShops();            // 手写演示店（s_001~s_022，被测试硬依赖）
        seedFromResource();     // 全量店铺（seed/shops.json，仪征 1600+ 家）
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
     *
     * <p>⚠️ 这里**没有 distance 字段**。早先手工写死过一份，结果 22 家里有 21 家的值
     * 和 {@link com.eatwhat.common.DistanceCalculator} 算出来的对不上（最大差 6.7km）——
     * 演示时看列表排序是一种结果，商家改个位置再刷新又是另一种，很像是排序算法坏了。
     * 现在统一由坐标推导（见 seedShops），保证「库里的距离」永远等于「公式的距离」。
     */
    private record ShopSeed(String id, String name, String cuisine, String city, String district,
                            String address, String phone, String hours, String intro,
                            double lat, double lng,
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
     * 22 家在营店铺，全部在**仪征市**（覆盖真州镇 / 仪化生活区 / 胥浦 / 十二圩）。
     *
     * <p><b>数据来源（重要）</b>：店名 / 地址 / 经纬度 / 营业时间 / 电话
     * 全部取自本平台的 POI 采集库 {@code yizheng-food-radar}
     * （高德 Web 服务 API 合规采集，GCJ-02 坐标系），**不是编造的**。
     * 评分与人均存在 radar.db 里，作为 {@code intro} 的文案依据。
     *
     * <p>这带来一个连带好处：{@code docs/07} 讲的「换机恢复」不再是抽象的 ——
     * 换台电脑重跑采集，这批店能一比一还原。
     *
     * <p><b>选题原则</b>（改数据时请保持）：
     * <ul>
     *   <li><b>价格档要覆盖三档</b>：中等 / 高等 / 超高档都至少有一家，
     *       否则平台端「价格档分布」图表会缺一根柱子</li>
     *   <li><b>weight 要刻意并列</b>：并列时才会走到「距离」和「时间衰减」两级，
     *       否则权重一旦全不相同，后两级排序规则永远演示不出来</li>
     *   <li><b>电话必须存在</b>：手机号认领流程靠店铺电话匹配（见 docs/05），
     *       没有电话的店在演示里走不通认领那一步</li>
     *   <li>品类尽量分散：淮扬菜是本地特色，必须占多数</li>
     * </ul>
     *
     * <p><b>刻意不放第二个城市</b>：前端 {@code buildFeed} 是本地实现、不按城市过滤，
     * 一旦后端多一个城市，就会出现「客户端刷得到、App 端 feed 刷不到」的分叉，
     * 而且会让客户端可刷内容变少 —— 与「多给点数据」的初衷相反。
     * 城市维度留给平台端 ranking 的 city 参数（接口已支持，见 docs/05）。
     *
     * <p>⚠️ 这里**没有 distance 字段**。距离统一由坐标推导（见 seedShops），
     * 保证「库里的距离」永远等于「公式的距离」。
     */
    private static final List<ShopSeed> SHOPS = List.of(
            // ---- 仪征 · 真州镇（主城，占比最高）----
            new ShopSeed("s_001", "永安水煮活鱼", "川菜", "仪征市", "真州镇",
                    "真州镇沿山河东路（红叶新村段）", "13800138000", "周一至周日 10:00-13:30,16:30-20:30",
                    "中餐，地图评分 4.8，人均约 72 元。", 32.286496, 119.182631,
                    95, true, 24, 1,
                    3325, 494, 228, 47, 33, 5),
            new ShopSeed("s_002", "钱亮亮小火锅", "火锅", "仪征市", "真州镇",
                    "解放东路与东园北路交叉口西80米", "17798962517", "周一至周日 10:30-21:00",
                    "小火锅，地图评分 4.7，人均约 62 元。", 32.272766, 119.188654,
                    90, true, 24, 1,
                    3150, 468, 216, 45, 31, 5),
            new ShopSeed("s_003", "望江渔村", "江鲜", "仪征市", "真州镇",
                    "大庆南路江堤边", "13852533377", "周一至周日 10:00-21:00",
                    "中餐，地图评分 4.6，人均约 241 元。", 32.246371, 119.182818,
                    90, true, 24, 1,
                    3150, 468, 216, 45, 31, 5),
            new ShopSeed("s_004", "迎春酒楼", "淮扬菜", "仪征市", "真州镇",
                    "人民路8号", "0514-83631577", "周一至周日 08:00-20:00",
                    "淮扬菜，地图评分 4.7，人均约 37 元。", 32.239318, 119.244134,
                    80, false, 24, 1,
                    2800, 416, 192, 40, 28, 5),
            new ShopSeed("s_005", "真湘园(迎江东路店)", "湘菜", "仪征市", "真州镇",
                    "真州镇仪化环南路(白沙四村对面原老消防队旁边仪华超市东边)", "18136474777", "周一至周日 09:00-22:00",
                    "湘菜，地图评分 4.7，人均约 59 元。", 32.280063, 119.159003,
                    80, false, 24, 1,
                    2800, 416, 192, 40, 28, 5),
            new ShopSeed("s_006", "鼓蘭庭院", "淮扬菜", "仪征市", "仪化生活区",
                    "真州镇国庆路168号开元小区1-209", "13390631177", "周一至周日 09:00-13:30,16:30-21:00",
                    "淮扬菜，地图评分 4.5，人均约 78 元。", 32.267053, 119.183071,
                    80, false, 24, 1,
                    2800, 416, 192, 40, 28, 5),
            new ShopSeed("s_007", "市井川菜(东亚御景湾店)", "川菜", "仪征市", "真州镇",
                    "御景湾14栋106室(停车场正对面张氏药房旁边)", "18168268866", "周一至周日 11:00-13:00,17:00-21:00",
                    "川菜，地图评分 4.7，人均约 72 元。", 32.276081, 119.203175,
                    70, false, 24, 1,
                    2450, 364, 168, 35, 24, 5),
            new ShopSeed("s_008", "重庆江湖菜(化纤大排档店)", "川菜", "仪征市", "仪化生活区",
                    "真州镇仪化环南路(浦东一村商用城西小街3-4号,剑津招待所对面)", "15252594266", "周一至周日 10:00-02:00",
                    "江湖菜，地图评分 4.5，人均约 62 元。", 32.283761, 119.148146,
                    70, false, 24, 1,
                    2450, 364, 168, 35, 24, 5),
            new ShopSeed("s_009", "真香园·现炒淮扬菜(博览家店)", "淮扬菜", "仪征市", "真州镇",
                    "解放西路339号(博览家门口锦绣家园对面仪征中学旁边)", "18168268868", "周一至周日 09:00-13:30,16:00-20:30",
                    "湘菜，地图评分 4.7，人均约 68 元。", 32.269957, 119.168583,
                    70, false, 24, 1,
                    2450, 364, 168, 35, 24, 5),
            new ShopSeed("s_010", "醉真州私房菜馆", "淮扬菜", "仪征市", "真州镇",
                    "大庆北路136-10号", "0514-83987777;15252532778", "周一至周日 09:00-13:30,16:00-21:00",
                    "私房菜，地图评分 4.6，人均约 132 元。", 32.281196, 119.173828,
                    70, false, 24, 1,
                    2450, 364, 168, 35, 24, 5),
            new ShopSeed("s_011", "川平饭店", "川菜", "仪征市", "胥浦",
                    "胥浦街60号", "0514-83271917;17712862099;18905252917", "周一至周日 09:00-13:00,16:00-21:00",
                    "中餐，地图评分 4.6，人均约 90 元。", 32.282048, 119.145311,
                    70, false, 24, 1,
                    2450, 364, 168, 35, 24, 5),
            new ShopSeed("s_012", "大楼烧烤(二店)", "烧烤", "仪征市", "真州镇",
                    "江城路16号", "13235241628", "周一至周日 17:00-02:00",
                    "烤串，地图评分 4.6，人均约 78 元。", 32.253105, 119.216908,
                    60, false, 24, 1,
                    2100, 312, 144, 30, 21, 5),
            new ShopSeed("s_013", "东北家庭烤肉(解放东路总店)", "烧烤", "仪征市", "真州镇",
                    "解放东路136号", "18021714966", "周一至周日 10:00-13:30,17:00-22:00",
                    "中餐，地图评分 4.6，人均约 75 元。", 32.273250, 119.190978,
                    60, false, 24, 1,
                    2100, 312, 144, 30, 21, 5),
            new ShopSeed("s_014", "袁记串串香(仪征店)", "火锅", "仪征市", "真州镇",
                    "真州镇解放东路224号袁记串串香", "13952502273", "周一至周日 09:30-22:00",
                    "串串香，地图评分 4.6，人均约 68 元。", 32.272626, 119.187412,
                    60, false, 24, 1,
                    2100, 312, 144, 30, 21, 5),
            new ShopSeed("s_015", "山中无老虎·鱼蛙火锅(仪征店)", "火锅", "仪征市", "真州镇",
                    "解放东路227-4号", "17368918872", "周一至周四 09:30-13:30,16:30-21:30；周五至周日 09:30-13:30,16:30-22:30",
                    "火锅，地图评分 4.6，人均约 77 元。", 32.272267, 119.187777,
                    60, false, 24, 1,
                    2100, 312, 144, 30, 21, 5),
            new ShopSeed("s_016", "广汉楼韩国馆(新天地花苑店)", "韩餐", "仪征市", "真州镇",
                    "真州镇新天地花苑6号楼92号", "13651538937", "周一至周日 09:00-13:30,16:00-21:00",
                    "韩国料理，地图评分 4.6，人均约 71 元。", 32.277170, 119.163211,
                    60, false, 24, 1,
                    2100, 312, 144, 30, 21, 5),
            new ShopSeed("s_017", "吾妈妈饭店·仪征早茶·地标美食", "小吃", "仪征市", "真州镇",
                    "文兴路6号", "0514-83906977;0514-83907777", "周一至周日 06:00-10:00,11:00-13:30,17:00-21:00",
                    "中餐，地图评分 4.6，人均约 63 元。", 32.267925, 119.211175,
                    60, false, 24, 1,
                    2100, 312, 144, 30, 21, 5),
            new ShopSeed("s_018", "顺水楼(紫星店)", "淮扬菜", "仪征市", "真州镇",
                    "真州镇胥浦工业园区浦西路1号", "18952522969", "周一至周日 09:30-22:00",
                    "中餐，地图评分 4.5，人均约 127 元。", 32.291875, 119.129325,
                    50, false, 24, 1,
                    1750, 260, 120, 25, 17, 5),
            new ShopSeed("s_019", "仪扬茶社(真州西路店)", "小吃", "仪征市", "真州镇",
                    "真州西路165号", "0514-83266777;0514-83417028", "周一至周日 06:00-13:30,15:30-19:30",
                    "茶座，地图评分 4.6，人均约 31 元。", 32.278817, 119.165206,
                    50, false, 24, 1,
                    1750, 260, 120, 25, 17, 5),
            new ShopSeed("s_020", "海荣汤包馆(中央花园店)", "小吃", "仪征市", "真州镇",
                    "真州西路139-36号(中央花园西北门旁)", "13852166399", "周一至周日 06:00-13:30,16:30-20:00",
                    "小吃快餐，地图评分 4.6，人均约 26 元。", 32.278355, 119.168446,
                    50, false, 24, 1,
                    1750, 260, 120, 25, 17, 5),
            new ShopSeed("s_021", "遇禧馄饨·手作糖水(立新巷店)", "甜品", "仪征市", "真州镇",
                    "立新巷与解放西路交叉口南80米", "19551698540", "周一至周日 10:00-20:30",
                    "糖水，地图评分 4.6，人均约 24 元。", 32.270003, 119.179576,
                    50, false, 24, 1,
                    1750, 260, 120, 25, 17, 5),
            new ShopSeed("s_022", "蓝湾咖啡·甜品·简餐(大庆南路店)", "咖啡", "仪征市", "真州镇",
                    "真州镇大庆南路店152号", "0514-85715618;13179799702", "周一至周日 09:30-24:00",
                    "咖啡，地图评分 4.5，人均约 54 元。", 32.266922, 119.178165,
                    50, false, 24, 1,
                    1750, 260, 120, 25, 17, 5)
    );

    /**
     * 待审核队列（客户端完全看不到）。
     *
     * <p>坐标必填 —— 它们代表「商家在申请页地图上选的点」。
     * 之前只有 p_001~p_003 有坐标，p_004/p_005 是 null，
     * 结果平台端点「通过」时那两家会算不出距离（distance=null，排到列表末尾），
     * 演示时看着像排序坏了。现在五家都有真实坐标。
     */
    private record PendingSeed(String id, String name, String cuisine, String city, String district,
                               String address, String phone, String hours, String intro,
                               double lat, double lng,
                               int submittedDaysAgo, int submittedHour) {}

    private static final List<PendingSeed> PENDING = List.of(
            new PendingSeed("p_001", "新开的螺蛳粉", "小吃", "仪征市", "真州镇",
                    "真州镇解放东路 66 号", "0514-88887777", "10:00-23:00",
                    "正宗柳州味道，酸笋每天现发。", 32.2720, 119.1900, 1, 9),
            new PendingSeed("p_002", "巷子口串串香", "火锅", "仪征市", "真州镇",
                    "真州镇鼓楼西路 8 号", "0514-88888888", "16:00-03:00",
                    "老巷子里的苍蝇馆子，开了七年。", 32.2675, 119.1813, 1, 10),
            new PendingSeed("p_003", "深夜豆浆油条", "小吃", "仪征市", "真州镇",
                    "真州镇北城河路 41 号", "0514-88889999", "22:00-06:00",
                    "专做夜宵档，豆浆现磨。", 32.2754, 119.1796, 0, 8),
            new PendingSeed("p_004", "胥浦潮汕牛肉锅", "火锅", "仪征市", "胥浦",
                    "胥浦街 128 号", "0514-88886666", "11:00-23:00",
                    "现宰黄牛，八秒吊龙。", 32.2880, 119.1450, 0, 9),
            new PendingSeed("p_005", "仪化老面馆", "面食", "仪征市", "仪化生活区",
                    "仪化生活区白沙路 22 号", "0514-88886688", "06:30-20:00",
                    "开了二十年的老面馆，杂酱面最出名。", 32.2860, 119.1560, 2, 11)
    );

    /** 已驳回（用于演示驳回理由回显）。 */
    private record RejectedSeed(String id, String name, String cuisine, String city, String district,
                                String address, String intro, int submittedDaysAgo,
                                String reason, int reviewedDaysAgo) {}

    private static final List<RejectedSeed> REJECTED = List.of(
            new RejectedSeed("p_r1", "无名小摊", "小吃", "仪征市", "真州镇", "仪征市某处", "",
                    7, "门头图不清晰，无法辨认店铺招牌，且未填写详细地址", 6),
            new RejectedSeed("p_r2", "皇家御膳私房菜", "川菜", "仪征市", "真州镇", "真州镇某写字楼", "主打高端宴请。",
                    4, "营业执照与经营主体不一致，且上传的门头照为效果图而非实拍", 3)
    );

    private void seedShops() {
        // 待审核队列先建 —— 它们走的是真实入驻接口，独立成方法是为了让
        // 「这块数据怎么来的」在文件结构上一眼可见（不是手搓字段，是调接口）。
        seedPendingShops();

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
            // 距离由坐标推导，不手写 —— 手写过一份，22 家里 21 家和公式对不上（最大差 6.7km）。
            // 现在「演示数据的距离」和「商家改位置后重算的距离」走同一个公式，
            // 排序在前端看起来才是一致的。
            s.setDistance(DistanceCalculator.toCityCenter(x.city(), x.lat(), x.lng()));
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
     * 待审核队列。走真实入驻接口（Controller 直调，绕开 HTTP），
     * 这样待审店铺的字段口径、状态、发布规则与线上完全一致。
     *
     * <p>接口会用 {@code s_<时间戳>} 落一条新记录，演示需要的是 {@code p_00X}
     * 这种稳定 id（前端多处按 p_ 前缀判断待审），所以要换 id。
     *
     * <p>⚠️ 换 id 必须**先删旧行再存新行**，不能直接 {@code setId()} 后 save ——
     * JPA 认为 id 变了就是「另一个实体」，会 INSERT 出一条新记录，
     * 结果待审列表里每条都出现两次（一份 s_xxx、一份 p_00X）。
     * 这个坑很隐蔽：页面不报错，只是列表看着莫名长了一倍。
     */
    private void seedPendingShops() {
        for (PendingSeed x : PENDING) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", x.name());
            body.put("cuisine", x.cuisine());
            body.put("city", x.city());
            body.put("district", x.district());
            body.put("address", x.address());
            body.put("phone", x.phone());
            body.put("hours", x.hours());
            body.put("intro", x.intro());
            body.put("lat", x.lat());
            body.put("lng", x.lng());
            Shop s = merchantController.applyPending(body);

            String autoId = s.getId();
            s.setId(x.id());
            s.setSubmittedAt(fmt(ts(x.submittedDaysAgo(), x.submittedHour(), 15)));
            s.setReviewedAt(null);
            s.setReviewer(null);
            s.setRejectReason(null);
            s.setLogo(picsum(x.id() + "logo", 200, 200));
            s.setCover(picsum(x.id() + "cover", 800, 600));
            shopRepo.save(s);

            // 删掉接口自动生成的那一行（新行已存好，现在删不会丢数据）
            if (!autoId.equals(x.id())) shopRepo.deleteById(autoId);
        }
    }

    /**
     * 已驳回店铺的公共部分。
     * distance 必须是 null 而不是 0 —— 0 在排序里等于「就在你脚下」，
     * 会把还没上线的店顶到推荐榜首。权重同理给 0。
     *
     * <p>这里**存坐标但不算 distance**：被驳回的申请同样保留商家选的点，
     * 万一商家改好资料重新提交，位置不用重选；而 distance 是「上线后离用户多远」，
     * 没上线就谈不上，留给审核通过那一刻再算。
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
        // 仪征市中心广场附近 —— 之前写死 (31.0, 104.3) 在外地，
        // 驳回店铺将来若被恢复上线，距离会凭空多出几百公里。
        s.setLat(32.2728);
        s.setLng(119.1845);
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
            new UserSeed("u_001", "小吃货", "13800000001", "仪征市", "真州镇", "normal", 12, 0, "2026-08-01"),
            new UserSeed("u_002", "隔壁老王", "13800000002", "仪征市", "真州镇", "normal", 45, 0, "2026-07-12"),
            new UserSeed("u_003", "干饭人小李", "13800000003", "仪征市", "真州镇", "warned", 88, 5, "2026-06-20"),
            new UserSeed("u_004", "夜宵战神", "13800000004", "仪征市", "仪化生活区", "normal", 26, 0, "2026-08-15"),
            new UserSeed("u_005", "麻辣小丸子", "13800000005", "仪征市", "真州镇", "normal", 33, 1, "2026-07-28"),
            new UserSeed("u_006", "一只吃货", "13800000006", "仪征市", "胥浦", "normal", 51, 0, "2026-06-05"),
            new UserSeed("u_007", "仪征老饕", "13800000007", "仪征市", "真州镇", "normal", 67, 2, "2026-05-18"),
            new UserSeed("u_008", "深夜放毒", "13800000008", "仪征市", "十二圩", "banned", 94, 4, "2026-04-30"),
            new UserSeed("u_009", "甜品控", "13800000009", "仪征市", "真州镇", "normal", 21, 0, "2026-08-22"),
            new UserSeed("u_010", "螺蛳粉星人", "13800000010", "仪征市", "仪化生活区", "normal", 18, 1, "2026-08-08"),
            new UserSeed("u_011", "减脂餐战士", "13800000011", "仪征市", "胥浦", "normal", 9, 0, "2026-09-01"),
            new UserSeed("u_012", "串串狂热者", "13800000012", "仪征市", "真州镇", "warned", 72, 3, "2026-05-06"),
            new UserSeed("u_013", "只喝汤", "13800000013", "仪征市", "真州镇", "normal", 14, 0, "2026-08-19"),
            new UserSeed("u_014", "面食之王", "13800000014", "仪征市", "仪化生活区", "normal", 29, 1, "2026-07-03")
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
            new ReportSeed("r_005", "dish", "d_052", "麻辣烤鱼", "疑似盗用他人图片", "仪征老饕", 2, "confirmed"),
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

    // ==================== 全量店铺（资源文件驱动）====================

    /**
     * 从 {@code classpath:seed/shops.json} 灌入全量店铺与菜品。
     *
     * <p><b>为什么数据外置成 JSON 而不是继续手写 Java 字面量</b>：
     * 这批数据是 1620 家店 + 7000 道菜，写成 Java 字面量会让本文件涨到 1.5 万行 ——
     * 编辑器打不开、编译变慢、每次调数据 git diff 都是万行级。
     * 外置后这里只留「读文件 + 落库」的循环，数据怎么涨代码都不动。
     *
     * <p>数据由 {@code server/tools/radar_to_seed.py} 从 POI 采集库
     * {@code yizheng-food-radar}（仪征市 2582 家餐饮 POI，高德合规采集）生成。
     * <b>换机后重跑那个脚本即可一比一还原。</b>
     *
     * <p>上面手写的 {@link #SHOPS}（s_001~s_022）依然优先灌入 ——
     * 它们是被测试和演示**硬依赖**的（s_001/13800138000 是商家端登录账号），
     * 资源文件里的记录会跳过这些 id，不会打架。
     */
    private void seedFromResource() {
        java.io.InputStream in = getClass().getResourceAsStream("/seed/shops.json");
        if (in == null) {
            log.warn("未找到 seed/shops.json，只灌入手写的 {} 家演示店", SHOPS.size());
            return;
        }
        List<Map<String, Object>> all;
        try (java.io.InputStream is = in) {
            all = json.readValue(is, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.error("解析 seed/shops.json 失败，跳过全量数据", e);
            return;
        }

        Set<String> exists = new HashSet<>();
        for (Shop s : shopRepo.findAll()) exists.add(s.getId());

        int shops = 0, dishes = 0;
        for (Map<String, Object> x : all) {
            String id = str(x.get("id"));
            if (id == null || exists.contains(id)) continue;   // 手写段已占用的 id 跳过

            Shop s = new Shop();
            s.setId(id);
            s.setName(str(x.get("name")));
            s.setCuisine(str(x.get("cuisine")));
            s.setAmapCuisine(str(x.get("amapCuisine")));
            s.setCuisineConfidence(str(x.get("cuisineConfidence")));
            s.setCity(str(x.get("city")));
            s.setDistrict(str(x.get("district")));
            s.setAddress(str(x.get("address")));
            s.setPhone(str(x.get("phone")));
            s.setHours(str(x.get("hours")));
            s.setIntro(str(x.get("intro")));
            Double lat = dbl(x.get("lat")), lng = dbl(x.get("lng"));
            s.setLat(lat);
            s.setLng(lng);
            // 距离由坐标推导 —— 与手写段、与商家改位置走同一个公式
            s.setDistance(lat == null || lng == null ? null
                    : DistanceCalculator.toCityCenter(str(x.get("city")), lat, lng));
            s.setStatus("normal");
            s.setWeight(intOf(x.get("weight"), 0));
            s.setPinned(false);
            s.setIntervalHours(intOf(x.get("intervalHours"), 24));
            s.setDailyLimit(intOf(x.get("dailyLimit"), 1));
            s.setLogo(picsum(id + "logo", 200, 200));
            s.setCover(picsum(id + "cover", 800, 600));
            // 审核痕迹：这批是「已通过的存量数据」，给一个统一的历史时间
            s.setSubmittedAt(fmt(ts(intOf(x.get("reviewDaysAgo"), 5), 8, 40)));
            s.setReviewedAt(fmt(ts(intOf(x.get("reviewDaysAgo"), 5), 14, 20)));
            s.setReviewer("平台运营");
            // 互动数按店铺评分生成（高评分的店人气高，与菜品数一个逻辑）
            int base = (int) (intOf(x.get("weight"), 40) * 30);
            s.setStatViews(base);
            s.setStatLikes(base / 7);
            s.setStatFavorites(base / 15);
            s.setStatComments(base / 40);
            s.setStatCheckins(base / 60);
            shopRepo.save(s);
            shops++;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ds = (List<Map<String, Object>>) x.get("dishes");
            if (ds == null) continue;
            for (Map<String, Object> dd : ds) {
                Dish d = new Dish();
                d.setId(str(dd.get("id")));
                d.setShopId(id);
                d.setShopName(s.getName());
                d.setName(str(dd.get("name")));
                d.setDescr(str(dd.get("desc")));
                String type = str(dd.get("type"));
                d.setType(type == null ? "image" : type);
                List<String> media = "video".equals(d.getType())
                        ? List.of(picsum(d.getId() + "a", 800, 1200))
                        : List.of(picsum(d.getId() + "a", 800, 1200),
                                   picsum(d.getId() + "b", 800, 1200));
                d.setMedia(toJson(media));
                d.setCover(media.get(0));
                double price = dbl(dd.get("price")) == null ? 30.0 : dbl(dd.get("price"));
                d.setPrice(price);
                d.setPriceTierId(configService.resolveTier(price));
                // ⚠️ 菜品是**按品类模板生成的**，不是店主实拍 → 一律 pending（待核），
                // 绝不能标 real —— 那等于对用户撒谎说「这是实拍认证的」。
                d.setRealTag("pending");
                d.setTasteTags(toJson(splitCsv(str(dd.get("taste")))));
                int daysAgo = intOf(dd.get("daysAgo"), 0);
                long published = ts(daysAgo, 10 + (daysAgo % 4), (daysAgo * 7) % 60);
                d.setPublishedTs(published);
                d.setPublishedAt(fmt(published));
                d.setStatus("normal");
                int dv = base / 5 + (int) (price * 3);
                d.setStatViews(dv);
                d.setStatLikes(dv / 8);
                d.setStatFavorites(dv / 20);
                d.setStatComments(dv / 55);
                d.setStatCheckins(dv / 90);
                dishRepo.save(d);
                dishes++;
            }
        }
        log.info("资源文件灌入：店铺 {} 家 · 菜品 {} 道", shops, dishes);
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.valueOf(String.valueOf(o)); }
        catch (NumberFormatException e) { return null; }
    }

    private int intOf(Object o, int def) {
        Double d = dbl(o);
        return d == null ? def : d.intValue();
    }
}
