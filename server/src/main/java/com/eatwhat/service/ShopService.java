package com.eatwhat.service;

import com.eatwhat.common.BizException;
import com.eatwhat.entity.Shop;
import com.eatwhat.repository.ShopRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 店铺服务。含审核、禁言、封店、单店发布规则覆盖。
 */
@Service
public class ShopService {

    private final ShopRepository repo;

    public ShopService(ShopRepository repo) {
        this.repo = repo;
    }

    public List<Shop> listAll() {
        return repo.findAll();
    }

    public Shop get(String id) {
        return repo.findById(id).orElseThrow(() -> new BizException(404, "店铺不存在"));
    }

    /** 客户端可见的店铺（排除封禁） */
    public List<Shop> listVisible() {
        return repo.findAll().stream()
                .filter(s -> !"banned".equals(s.getStatus()))
                .toList();
    }

    // ==================== 违规三级处理 ====================

    /** 禁言：不能发布、不能回评 */
    public Shop mute(String id) {
        Shop s = get(id);
        s.setStatus("muted");
        return repo.save(s);
    }

    /** 封店：客户端彻底下架 */
    public Shop ban(String id) {
        Shop s = get(id);
        s.setStatus("banned");
        return repo.save(s);
    }

    /** 解禁 / 恢复 */
    public Shop restore(String id) {
        Shop s = get(id);
        s.setStatus("normal");
        return repo.save(s);
    }

    // ==================== 单店发布规则覆盖 ====================

    /**
     * 修改单店发布规则。
     * 规则变更不影响已发记录，从下次算起 —— 所以这里不动 lastPublishAt。
     */
    public Shop setRule(String id, Integer intervalHours, Integer dailyLimit) {
        if (intervalHours != null && (intervalHours < 1 || intervalHours > 168)) {
            throw new BizException("发布间隔应在 1-168 小时之间");
        }
        if (dailyLimit != null && (dailyLimit < 1 || dailyLimit > 10)) {
            throw new BizException("每日上限应在 1-10 条之间");
        }
        Shop s = get(id);
        if (intervalHours != null) s.setIntervalHours(intervalHours);
        if (dailyLimit != null) s.setDailyLimit(dailyLimit);
        return repo.save(s);
    }

    /** 平台端拖拽：单独设置权重 */
    public Shop setWeight(String id, Integer weight) {
        Shop s = get(id);
        s.setWeight(weight == null ? 0 : weight);
        return repo.save(s);
    }

    /** 置顶开关 */
    public Shop setPinned(String id, boolean pinned) {
        Shop s = get(id);
        s.setPinned(pinned);
        return repo.save(s);
    }

    /** 新增店铺（审核通过后落库） */
    public Shop create(Map<String, Object> body) {
        Shop s = new Shop();
        s.setId("s_" + System.currentTimeMillis());
        s.setName(str(body.get("name")));
        s.setCuisine(str(body.get("cuisine")));
        s.setCity(str(body.get("city")));
        s.setDistrict(str(body.get("district")));
        s.setAddress(str(body.get("address")));
        s.setPhone(str(body.get("phone")));
        s.setHours(str(body.get("hours")));
        s.setIntro(str(body.get("intro")));
        s.setCover(str(body.get("cover")));
        s.setLogo(str(body.get("logo")));
        s.setStatus("normal");
        s.setCanPostToday(true);
        s.setWeight(0);
        s.setPinned(false);
        s.setIntervalHours(24);
        s.setDailyLimit(1);
        return repo.save(s);
    }

    /** 保存店铺（内部使用） */
    public Shop save(Shop s) {
        return repo.save(s);
    }

    private String str(Object o) { return o == null ? null : o.toString(); }
}
