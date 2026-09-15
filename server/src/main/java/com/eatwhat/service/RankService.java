package com.eatwhat.service;

import com.eatwhat.entity.Dish;
import com.eatwhat.entity.Shop;
import com.eatwhat.repository.ShopRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 推荐排序服务。
 * 排序优先级（冻结规则）：置顶 > 权重 > 距离 > 时间衰减
 * 且按地区独立。
 */
@Service
public class RankService {

    private final ShopRepository shopRepo;

    public RankService(ShopRepository shopRepo) {
        this.shopRepo = shopRepo;
    }

    /**
     * 时间衰减：24 小时内线性衰减，超过 24 小时归零。
     * 归零不等于删除 —— 内容仍保留在店铺页。
     */
    public double timeDecay(Long publishedTs) {
        if (publishedTs == null) return 0;
        double hours = (System.currentTimeMillis() - publishedTs) / 3600000.0;
        if (hours >= 24) return 0;
        if (hours < 0) return 1;
        return 1 - hours / 24;
    }

    /**
     * 店铺排序：置顶 > 权重 > 距离 > 时间衰减
     */
    public List<Shop> sortShops(List<Shop> shops) {
        return shops.stream()
                .sorted((a, b) -> {
                    // 1. 置顶
                    boolean pa = Boolean.TRUE.equals(a.getPinned());
                    boolean pb = Boolean.TRUE.equals(b.getPinned());
                    if (pa != pb) return pa ? -1 : 1;

                    // 2. 权重（大者优先）
                    int wa = a.getWeight() == null ? 0 : a.getWeight();
                    int wb = b.getWeight() == null ? 0 : b.getWeight();
                    if (wa != wb) return wb - wa;

                    // 3. 距离（近者优先，distance 为 null 视为最远）
                    double da = a.getDistance() == null ? Double.MAX_VALUE : a.getDistance();
                    double db = b.getDistance() == null ? Double.MAX_VALUE : b.getDistance();
                    if (Math.abs(da - db) > 0.001) return Double.compare(da, db);

                    // 4. 时间衰减（新者优先）
                    return Double.compare(timeDecay(b.getLastPublishAt()), timeDecay(a.getLastPublishAt()));
                })
                .collect(Collectors.toList());
    }

    /** 按地区取店铺并排序 */
    public List<Shop> rankByCity(String city) {
        List<Shop> list = (city == null || city.isBlank())
                ? shopRepo.findAll()
                : shopRepo.findByCityOrderByWeightDesc(city);
        // 客户端只看得到真正上线的店：
        // 封禁、待审核、已驳回的一律排除。
        // 尤其是待审核的店 —— 它们没有 distance（null），不排掉的话
        // 会被 sortShops 当成"最远"或者因脏数据插到榜首，污染推荐结果。
        list = list.stream().filter(s -> {
            String st = s.getStatus();
            return !"banned".equals(st) && !"pending".equals(st) && !"rejected".equals(st);
        }).collect(Collectors.toList());
        return sortShops(list);
    }

    /**
     * 平台端拖拽排序后的落库：按传入顺序重写 weight。
     * 越靠前权重越高（weight = 总数 - 下标）。
     * 注意：排序时置顶优先于权重，所以置顶店即使权重不高也会排在前面。
     */
    public void applyOrder(List<String> orderedShopIds) {
        int total = orderedShopIds.size();
        List<Shop> toSave = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            shopRepo.findById(orderedShopIds.get(i)).ifPresent(s -> {
                s.setWeight(total - idx);
                toSave.add(s);
            });
        }
        shopRepo.saveAll(toSave);
    }

    // ==================== 信息流 ====================

    private static final Random RANDOM = new Random();

    /**
     * 「附近」Tab：按店铺排序，取每家店铺的最新一条菜品。
     * 排序遵循：置顶 > 权重 > 距离 > 时间衰减。
     */
    public List<Dish> buildNearbyFeed(String city, Map<String, Dish> latestByShop) {
        List<Shop> shops = rankByCity(city);
        List<Dish> feed = new ArrayList<>();
        for (Shop s : shops) {
            Dish d = latestByShop.get(s.getId());
            if (d != null) feed.add(d);
        }
        return feed;
    }

    /**
     * 「随心看」Tab：随机排序 + 排除已浏览。
     * 全部浏览完则重置一轮，避免出现空白页。
     */
    public List<Dish> buildRandomFeed(List<Dish> pool, Set<String> browsed) {
        List<Dish> list = pool.stream()
                .filter(d -> !browsed.contains(d.getId()))
                .collect(Collectors.toList());
        Collections.shuffle(list, RANDOM);

        if (list.isEmpty()) {
            list = new ArrayList<>(pool);
            Collections.shuffle(list, RANDOM);
        }
        return list;
    }
}
