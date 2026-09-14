package com.eatwhat.service;

import com.eatwhat.common.BizException;
import com.eatwhat.entity.AppUser;
import com.eatwhat.repository.AppUserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户服务。承载违规三级处理：警告 → 限评论 → 封号。
 * 封号保留历史评论，不做物理删除。
 */
@Service
public class AppUserService {

    private final AppUserRepository repo;

    public AppUserService(AppUserRepository repo) {
        this.repo = repo;
    }

    public AppUser get(String id) {
        return repo.findById(id).orElseThrow(() -> new BizException(404, "用户不存在"));
    }

    /** 列表：按被举报次数倒序，风险用户浮在最前 */
    public List<AppUser> listByRisk() {
        return repo.findAllByOrderByReportCountDesc();
    }

    /** 注册 / 登录：手机号不存在则自动创建 */
    public AppUser loginByPhone(String phone) {
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            throw new BizException("手机号格式不正确");
        }
        AppUser u = repo.findByPhone(phone);
        if (u != null) {
            if ("banned".equals(u.getStatus())) throw new BizException(403, "该账号已被封禁");
            return u;
        }
        AppUser nu = new AppUser();
        nu.setId("u_" + System.currentTimeMillis());
        nu.setPhone(phone);
        nu.setName("用户" + phone.substring(7));
        nu.setStatus("normal");
        nu.setCommentCount(0);
        nu.setReportCount(0);
        nu.setAt(LocalDate.now().toString());
        return repo.save(nu);
    }

    /** 违规三级处理：normal | warned | banned */
    public AppUser setStatus(String id, String status) {
        if (!List.of("normal", "warned", "banned").contains(status)) {
            throw new BizException("非法的状态值");
        }
        AppUser u = get(id);
        u.setStatus(status);
        repo.save(u);
        return u;
    }

    /** 被举报次数 +1，达 3 次自动标记为需处理 */
    public AppUser incReport(String id) {
        AppUser u = get(id);
        int n = (u.getReportCount() == null ? 0 : u.getReportCount()) + 1;
        u.setReportCount(n);
        // 达到阈值自动警告
        if (n >= 3 && "normal".equals(u.getStatus())) {
            u.setStatus("warned");
        }
        return repo.save(u);
    }

    public void incCommentCount(String id) {
        repo.findById(id).ifPresent(u -> {
            u.setCommentCount((u.getCommentCount() == null ? 0 : u.getCommentCount()) + 1);
            repo.save(u);
        });
    }

    /** 平台端数据概览用 */
    public long count() { return repo.count(); }
    public long countByStatus(String status) {
        return repo.findAll().stream().filter(u -> status.equals(u.getStatus())).count();
    }
}
