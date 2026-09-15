package com.eatwhat.service;

import com.eatwhat.common.BizException;
import com.eatwhat.entity.PlatformConfig;
import com.eatwhat.repository.PlatformConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 平台配置服务：价格档、口味标签、菜系、全局发布规则。
 * 价格档的区间定义在这里，菜品的档位归档也归它管。
 */
@Service
public class ConfigService {

    private final PlatformConfigRepository repo;
    private final ObjectMapper json = new ObjectMapper();

    public ConfigService(PlatformConfigRepository repo) {
        this.repo = repo;
    }

    /** 取配置单，不存在则初始化 */
    public PlatformConfig get() {
        return repo.findById(1L).orElseGet(() -> {
            PlatformConfig c = new PlatformConfig();
            c.setId(1L);
            c.setPriceTiers(toJson(List.of(
                    Map.of("id", "tier_mid",   "name", "中等",   "min", 0,   "max", 50),
                    Map.of("id", "tier_high",  "name", "高等",   "min", 50,  "max", 150),
                    Map.of("id", "tier_ultra", "name", "超高级", "min", 150, "max", -1)
            )));
            c.setTasteTags(toJson(List.of("麻辣", "清淡", "烧烤", "日料", "面食", "小吃", "甜点")));
            c.setCuisines(toJson(List.of("川菜", "火锅", "烧烤", "日料", "面食", "小吃", "西餐", "甜品", "粤菜", "湘菜")));
            return repo.save(c);
        });
    }

    /** 价格档列表。max = -1 表示无上限（对应前端的 Infinity，JSON 不能存 Infinity） */
    public List<Map<String, Object>> priceTiers() {
        return fromJsonList(get().getPriceTiers());
    }

    public List<String> tasteTags() {
        return fromJsonStringList(get().getTasteTags());
    }

    public List<String> cuisines() {
        return fromJsonStringList(get().getCuisines());
    }

    // ==================== 核心规则：价格自动归档 ====================

    /**
     * 按商家填写的价格，自动归档到平台定义的档位。
     * 商家不可手选档位 —— 这是冻结的业务规则。
     *
     * @return 档位 id，无匹配返回 null
     */
    public String resolveTier(Double price) {
        if (price == null || price < 0) return null;
        List<Map<String, Object>> tiers = priceTiers();
        for (Map<String, Object> t : tiers) {
            double min = num(t.get("min"), 0);
            double max = num(t.get("max"), -1);
            // max = -1 表示无穷大
            boolean upperOk = (max < 0) || (price < max);
            if (price >= min && upperOk) return String.valueOf(t.get("id"));
        }
        // 兜底：归到最后一档
        return tiers.isEmpty() ? null : String.valueOf(tiers.get(tiers.size() - 1).get("id"));
    }

    /** 保存价格档，并返回旧档位列表（调用方据此重算所有菜品） */
    public List<Map<String, Object>> savePriceTiers(List<Map<String, Object>> tiers) {
        for (Map<String, Object> t : tiers) {
            double min = num(t.get("min"), 0);
            double max = num(t.get("max"), -1);
            if (max >= 0 && min >= max) {
                throw new BizException("「" + t.get("name") + "」的价格区间不合法");
            }
        }
        PlatformConfig c = get();
        c.setPriceTiers(toJson(tiers));
        repo.save(c);
        return tiers;
    }

    public void saveTasteTags(List<String> tags) {
        PlatformConfig c = get();
        c.setTasteTags(toJson(tags));
        repo.save(c);
    }

    public void saveCuisines(List<String> list) {
        PlatformConfig c = get();
        c.setCuisines(toJson(list));
        repo.save(c);
    }

    // ==================== 全局默认发布规则 ====================

    /**
     * 全局默认发布规则。
     * 它决定新店铺的初始值，也是单店「自定义」标记的比较基准 ——
     * 所以必须落库，不能只放在前端内存里（刷新就丢，还会把
     * 所有店铺都误判成"自定义"）。
     */
    public Map<String, Object> publishRule() {
        PlatformConfig c = get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intervalHours", c.getDefaultIntervalHours() == null ? 24 : c.getDefaultIntervalHours());
        m.put("dailyLimit", c.getDefaultDailyLimit() == null ? 1 : c.getDefaultDailyLimit());
        return m;
    }

    public Map<String, Object> savePublishRule(Integer intervalHours, Integer dailyLimit) {
        if (intervalHours != null && (intervalHours < 1 || intervalHours > 168)) {
            throw new BizException("发布间隔应在 1-168 小时之间");
        }
        if (dailyLimit != null && (dailyLimit < 1 || dailyLimit > 10)) {
            throw new BizException("每日上限应在 1-10 条之间");
        }
        PlatformConfig c = get();
        if (intervalHours != null) c.setDefaultIntervalHours(intervalHours);
        if (dailyLimit != null) c.setDefaultDailyLimit(dailyLimit);
        repo.save(c);
        return publishRule();
    }

    // ==================== JSON 工具 ====================

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (Exception e) { throw new BizException("配置序列化失败"); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fromJsonList(String s) {
        try {
            if (s == null || s.isBlank()) return new ArrayList<>();
            return json.readValue(s, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private List<String> fromJsonStringList(String s) {
        try {
            if (s == null || s.isBlank()) return new ArrayList<>();
            return json.readValue(s, new TypeReference<List<String>>() {});
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private double num(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); }
        catch (Exception e) { return def; }
    }
}
