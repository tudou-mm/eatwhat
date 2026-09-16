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
    private final ConfigService configService;

    public ShopService(ShopRepository repo, ConfigService configService) {
        this.repo = repo;
        this.configService = configService;
    }

    public List<Shop> listAll() {
        return repo.findAll();
    }

    public Shop get(String id) {
        return repo.findById(id).orElseThrow(() -> new BizException(404, "店铺不存在"));
    }

    /**
     * 客户端可见的店铺。
     * 排除三类：banned（封店）、pending（还没过审）、rejected（已被驳回）。
     * muted 仍可见 —— 禁言只是不能发布和回评，店还是正常营业的。
     */
    public List<Shop> listVisible() {
        return repo.findAll().stream()
                .filter(s -> !isHidden(s.getStatus()))
                .toList();
    }

    private boolean isHidden(String status) {
        return "banned".equals(status) || "pending".equals(status) || "rejected".equals(status);
    }

    // ==================== 商家入驻审核 ====================

    /** 待审核商家，按提交时间倒序（新的在前，运营优先看） */
    public List<Shop> listPending() {
        return repo.findAll().stream()
                .filter(s -> "pending".equals(s.getStatus()))
                .sorted((a, b) -> str(b.getSubmittedAt()).compareTo(str(a.getSubmittedAt())))
                .toList();
    }

    /** 已通过审核的店铺，按审核时间倒序 */
    public List<Shop> listApproved() {
        return repo.findAll().stream()
                .filter(s -> s.getReviewedAt() != null && !"rejected".equals(s.getStatus()))
                .sorted((a, b) -> str(b.getReviewedAt()).compareTo(str(a.getReviewedAt())))
                .toList();
    }

    /** 已驳回的店铺，按审核时间倒序 */
    public List<Shop> listRejected() {
        return repo.findAll().stream()
                .filter(s -> "rejected".equals(s.getStatus()))
                .sorted((a, b) -> str(b.getReviewedAt()).compareTo(str(a.getReviewedAt())))
                .toList();
    }

    /**
     * 审核通过：pending → normal。
     * 只有 pending 状态才允许审核，避免对已在营店铺重复操作。
     */
    public Shop approve(String id, String reviewer) {
        Shop s = get(id);
        if (!"pending".equals(s.getStatus())) {
            throw new BizException("该商家当前状态为「" + statusName(s.getStatus()) + "」，无需重复审核");
        }
        s.setStatus("normal");
        s.setReviewer(reviewer == null || reviewer.isBlank() ? "平台运营" : reviewer);
        s.setReviewedAt(now());
        s.setRejectReason(null);
        return repo.save(s);
    }

    /** 审核驳回：pending → rejected，必须填理由（要回显给商家） */
    public Shop reject(String id, String reason, String reviewer) {
        if (reason == null || reason.isBlank()) {
            throw new BizException("驳回必须填写理由");
        }
        Shop s = get(id);
        if (!"pending".equals(s.getStatus())) {
            throw new BizException("该商家当前状态为「" + statusName(s.getStatus()) + "」，无需重复审核");
        }
        s.setStatus("rejected");
        s.setRejectReason(reason);
        s.setReviewer(reviewer == null || reviewer.isBlank() ? "平台运营" : reviewer);
        s.setReviewedAt(now());
        return repo.save(s);
    }

    private String statusName(String s) {
        return switch (s == null ? "" : s) {
            case "pending" -> "待审核";
            case "normal" -> "已通过";
            case "rejected" -> "已驳回";
            case "muted" -> "已禁言";
            case "banned" -> "已封店";
            default -> s;
        };
    }

    private String now() {
        return java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private String str(String s) { return s == null ? "" : s; }

    // ==================== 违规三级处理 ====================

    /** 禁言：不能发布、不能回评 */
    public Shop mute(String id) {
        Shop s = get(id);
        s.setStatus("muted");
        return repo.save(s);
    }

    /**
     * 封店：客户端彻底下架，且**立刻踢掉商家手上那张 token**。
     *
     * 版本号 +1 是关键一步。不 +1 的话，封店只挡住「新登录」，
     * 商家在封店前领到的那张 token 还能继续用来改资料、上下架菜品，
     * 一直用到 168 小时后自然过期 —— 等于封了个寂寞。
     */
    public Shop ban(String id) {
        Shop s = get(id);
        s.setStatus("banned");
        s.setTokenVersion(s.getTokenVersion() + 1);
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

    /**
     * 商家入驻申请：落成一家「待审核」店铺。
     *
     * 三个必须守住的点：
     * 1. status = pending，不能直接 normal —— 平台审核这道闸门不能绕。
     * 2. ⚠️ distance / lat / lng 一律 null。待审核店铺还没做过地理校验，
     *    给 0 会让它在「附近」排序里排到所有真实店铺前面，白吃掉首页流量。
     * 3. 发布规则取平台「当前」默认值 —— 新店从此刻的规则开始算，
     *    和「规则变更不影响已发记录」是同一条规则的两面。
     */
    public Shop createPending(Map<String, Object> body) {
        String name = str(body.get("name"));
        if (name == null || name.isBlank()) throw new BizException("请填写店铺名称");
        String phone = str(body.get("phone"));
        if (phone == null || phone.isBlank()) throw new BizException("请填写联系电话");
        // 同一个电话不要反复堆待审核记录 —— 入驻接口是公开的，这是最低限度的防刷
        if (repo.existsByPhoneAndStatus(phone, "pending")) {
            throw new BizException(400, "该手机号已有一条待审核的入驻申请，请等待平台处理");
        }

        Map<String, Object> rule = configService.publishRule();

        Shop s = new Shop();
        s.setId("s_" + System.currentTimeMillis());
        s.setName(name);
        s.setCuisine(str(body.get("cuisine")));
        s.setCity(str(body.get("city")));
        s.setDistrict(str(body.get("district")));
        s.setAddress(str(body.get("address")));
        s.setPhone(phone);
        s.setHours(str(body.get("hours")));
        s.setIntro(str(body.get("intro")));
        s.setCover(str(body.get("cover")));
        s.setLogo(str(body.get("logo")));

        s.setStatus("pending");
        s.setDistance(null);      // 见上文第 2 点
        s.setLat(null);
        s.setLng(null);
        s.setCanPostToday(false); // 没过审谈不上发布
        s.setWeight(0);
        s.setPinned(false);
        s.setIntervalHours(((Number) rule.get("intervalHours")).intValue());
        s.setDailyLimit(((Number) rule.get("dailyLimit")).intValue());
        s.setSubmittedAt(now());
        return repo.save(s);
    }

    private String str(Object o) { return o == null ? null : o.toString(); }
}
