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
    private final ShopService shopService;

    public RankService(ShopRepository shopRepo, ShopService shopService) {
        this.shopRepo = shopRepo;
        this.shopService = shopService;
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
     * 首屏用：从可见店铺里取「最该先给用户看的」前 limit 家。
     *
     * <p><b>为什么不是简单取前 N 条</b>：{@link #sortShops} 是
     * 「置顶 &gt; 权重 &gt; 距离 &gt; 时间衰减」，权重主导。
     * 而 {@link com.eatwhat.config.DataSeeder} 的权重是**刻意并列**的
     * （90/85/…/30 分档，同档几十家），所以纯按它取前 200 家，
     * 会集中在「权重最高的档位」里，下面的店永远进不了首屏。
     *
     * <p>这里改成**先按距离取、层内按权重**：保证首屏覆盖的是
     * 用户周围一圈真实的店，而不是一批权重虚高的店。
     * 权重依然参与（同距离段内起作用），只是不再独占主导权。
     *
     * <p><b>三类店必须保底进首屏</b>（不受距离限制）：
     * <ol>
     *   <li><b>置顶店</b> —— 运营手动指定的，一般是重要合作方</li>
     *   <li><b>刚上线的店</b> —— 否则「审核通过 → 客户端立刻可见」会失效。
     *       审核验收动作就是「通过 → 去客户端刷新」，它必须稳定成立，
     *       不能因为店在城郊、距离排不进前 200 就看不到。</li>
     *   <li><b>刚改过资料的店</b> —— 否则「商家改完简介 → 去客户端确认」
     *       看到的还是旧文案，商家会以为保存没生效。</li>
     * </ol>
     * 后两类的判定统一收在 {@link ShopService#isRecentlyChanged}。
     */
    public List<Shop> topShopsByDistance(List<Shop> shops, int limit, String city) {
        List<Shop> pool = (city == null || city.isBlank())
                ? shops
                : shops.stream().filter(s -> city.equals(s.getCity())).collect(Collectors.toList());
        if (pool.size() <= limit) return sortShops(pool);

        // 保底：置顶店 + 刚上线/刚改过资料的店
        List<Shop> must = pool.stream()
                .filter(s -> Boolean.TRUE.equals(s.getPinned()) || shopService.isRecentlyChanged(s))
                .collect(Collectors.toList());
        Set<String> mustIds = new HashSet<>();
        for (Shop s : must) mustIds.add(s.getId());
        int rest = Math.max(0, limit - must.size());

        // 其余按距离排序取前 rest 家；distance 为 null 的排最后（不会混进来）
        List<Shop> byDist = pool.stream()
                .filter(s -> !mustIds.contains(s.getId()))
                .sorted(Comparator.comparingDouble(
                        s -> s.getDistance() == null ? Double.MAX_VALUE : s.getDistance()))
                .limit(rest)
                .collect(Collectors.toList());

        List<Shop> out = new ArrayList<>(must);
        out.addAll(byDist);
        return sortShops(out);
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
