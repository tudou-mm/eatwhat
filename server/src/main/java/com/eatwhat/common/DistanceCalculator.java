package com.eatwhat.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 距离计算。店铺排序里「距离」这一维度（置顶 &gt; 权重 &gt; <b>距离</b> &gt; 时间衰减）的唯一来源。
 *
 * <p>为什么要单独抽一个类：{@code shop.distance} 是**派生的**，不是商家填的字段。
 * 商家在入驻页选的经纬度只是「店在哪」，而排序要比的是「离我多远」——
 * 两者之间的换算必须有且只有一处，否则前端算一遍、审核时又算一遍，
 * 迟早出现「列表里排第三、详情页显示 1.2km」这种自相矛盾。
 *
 * <p>⚠️ 口径说明（线上必须替换）：目前是**单城单锚点**——
 * 所有距离都是「到本市中心点」的直线距离，因为客户端还没有定位能力。
 * 二期接入真实定位后，把 {@code CITY_CENTER} 换成「用户当前坐标」即可，
 * 公式不用动。这也是为什么排序里「距离」放在「权重」之后：
 * 锚点是死的，真按它排序会把市中心商圈永久顶到前面。
 *
 * <p>⚠️ 坐标口径：高德与种子数据统一用 **GCJ-02（火星坐标）**，
 * 锚点取自 {@code yizheng-food-radar} 采集库的仪征市中心
 * （2582 家 POI 的坐标包围盒中心），与店铺坐标同一坐标系，不需要纠偏。
 */
public final class DistanceCalculator {

    private DistanceCalculator() {}

    /** 地球平均半径（km），Haversine 用 */
    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * 各城市锚点中心 [lat, lng]。
     *
     * 用市中心的**真实坐标**而不是「所有店坐标求平均」：
     * 求平均的话，加一家新店就会让全城所有店的距离集体微调，
     * 排序结果每天都在悄悄变，出了问题根本查不出来。
     *
     * ⚠️ 城市名必须写**全称**，且与 {@code shop.city} 完全一致（含「市」字）。
     * 漏写会静默落到 {@link #DEFAULT_CENTER}，表现为「全城距离集体偏了十几公里」
     * 而排序看起来仍然正常 —— 极难发现。
     */
    private static final Map<String, double[]> CITY_CENTER = new ConcurrentHashMap<>(Map.of(
            // 主城：仪征市中心（取自 yizheng-food-radar 2582 家 POI 的包围盒中心）
            "仪征市", new double[]{32.2728, 119.1845},
            // 其余为二期候选城市，先占位，与仪征同属扬州/苏中片区
            "扬州市", new double[]{32.3942, 119.4129},
            "南京市", new double[]{32.0603, 118.7969},
            "镇江市", new double[]{32.1878, 119.4250},
            "泰州市", new double[]{32.4558, 119.9230}
    ));

    /**
     * 兜底锚点：城市表里没有的一律按**仪征**算。
     *
     * ⚠️ 这里必须与业务主战场一致。种子数据是仪征的店，
     * 若兜底成别的城市，任何城市名拼写差异都会让演示数据整体偏航。
     */
    private static final double[] DEFAULT_CENTER = {32.2728, 119.1845};

    private static double[] centerOf(String city) {
        if (city == null || city.isBlank()) return DEFAULT_CENTER;
        return CITY_CENTER.getOrDefault(city.trim(), DEFAULT_CENTER);
    }

    /**
     * 某坐标到指定城市中心的距离（km，保留一位小数）。
     *
     * 两位小数会让前端展示成「1.23km」，餐饮场景里这个精度没有意义，
     * 一位小数足够区分「隔壁」和「过条街」，也让 export_mock.py 导出的假数据更干净。
     */
    public static Double toCityCenter(String city, double lat, double lng) {
        double[] c = centerOf(city);
        double km = haversine(lat, lng, c[0], c[1]);
        return Math.round(km * 10) / 10.0;
    }

    /** 无城市信息时的重载，等价于按仪征锚点算 */
    public static Double toCityCenter(double lat, double lng) {
        return toCityCenter(null, lat, lng);
    }

    /**
     * Haversine 球面距离。
     *
     * 不用平面近似（{@code √(Δlat²+Δlng²)}）的原因：那个公式漏了经度随纬度收敛，
     * 在仪征（北纬 32.3°）会把东西向距离高估约 18%，越往北错得越离谱。
     * 这里就是几行三角函数，没有性能理由省。
     */
    public static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.asin(Math.sqrt(a));
    }
}
