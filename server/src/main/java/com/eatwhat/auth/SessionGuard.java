package com.eatwhat.auth;

import com.eatwhat.common.BizException;
import com.eatwhat.entity.AppUser;
import com.eatwhat.entity.PlatformConfig;
import com.eatwhat.entity.Shop;
import com.eatwhat.repository.AppUserRepository;
import com.eatwhat.repository.PlatformConfigRepository;
import com.eatwhat.repository.ShopRepository;
import org.springframework.stereotype.Component;

/**
 * 会话有效性守卫：判断一张**签名有效、尚未过期**的 token 到底还算不算数。
 *
 * 为什么需要它 —— JWT 的天然短板是「签发之后就管不了了」：
 * 校验只认签名和过期时间，服务端没有任何办法提前作废它。
 * 于是会出现这种事：平台端把一家店封了，那家店拿着封店前领的 token
 * 照样能改资料、上下架菜品，一直用到 168 小时后自然过期。
 *
 * 补法：给每个可登录主体挂一个 {@code tokenVersion}，签发时写进 payload，
 * 每次请求与库里的当前值比对。**作废 = 把库里的值 +1**，
 * 旧 token 下一毫秒就对不上号了，不用等它过期。
 *
 * 三类主体的版本号存在哪：
 * <pre>
 *   merchant → Shop.tokenVersion
 *   client   → AppUser.tokenVersion
 *   admin    → PlatformConfig.adminTokenVersion（平台账号是配置里的固定账号，没有实体行）
 * </pre>
 *
 * ⚠️ 两个刻意的选择，改动前先想清楚：
 * 1. **失效一律回 401，不是 403**。401 的语义是「你这份凭证不作数了」，
 *    前端适配层收到 401 会清本地会话并送回登录页；回 403 的话
 *    页面会以为「登录还在，只是没权限」，攥着一张死 token 卡在原地。
 * 2. **封禁是独立于版本号的第二道闸**。就算版本号碰巧对得上，
 *    只要 status=banned 照样拒 —— 两道判断不能合并成一道。
 *
 * ⚠️ 代价：每个受保护请求多一次主键查询。当前规模（试点）可以接受；
 *    真要扛量的话应该在这一层加缓存，或改成「吊销名单 + 短 TTL」。
 */
@Component
public class SessionGuard {

    private final ShopRepository shopRepo;
    private final AppUserRepository userRepo;
    private final PlatformConfigRepository configRepo;

    public SessionGuard(ShopRepository shopRepo,
                        AppUserRepository userRepo,
                        PlatformConfigRepository configRepo) {
        this.shopRepo = shopRepo;
        this.userRepo = userRepo;
        this.configRepo = configRepo;
    }

    // ==================== 校验 ====================

    /**
     * 校验当前 token 是否仍然有效。无效直接抛 401，调用方不用管返回值。
     *
     * @param role         角色：merchant / client / admin
     * @param subject      token 主体
     * @param shopId       商家 token 里的店铺 id
     * @param tokenVersion token 里的版本号
     */
    public void check(String role, String subject, String shopId, int tokenVersion) {
        switch (role == null ? "" : role) {
            case "merchant" -> checkShop(shopId, tokenVersion);
            case "client" -> checkUser(subject, tokenVersion);
            case "admin" -> checkAdmin(tokenVersion);
            // 未知角色不在这里拒 —— 交给上层的「角色 ↔ 路径」匹配去拦，
            // 两层各管一件事，混在一起以后会看不懂
            default -> { }
        }
    }

    private void checkShop(String shopId, int tokenVersion) {
        if (shopId == null || shopId.isBlank()) {
            throw new BizException(401, "登录凭证缺少店铺信息，请重新登录");
        }
        Shop s = shopRepo.findById(shopId).orElse(null);
        if (s == null) {
            throw new BizException(401, "店铺不存在或已被移除，请重新登录");
        }
        if ("banned".equals(s.getStatus())) {
            throw new BizException(401, "店铺已被封禁，请联系平台");
        }
        // muted（禁言）**不拦登录** —— 禁言只限制发布和回评，
        // 店家仍然要能登进来看数据、处理评论。拦在这里就变成变相封店了。
        if (s.getTokenVersion() != tokenVersion) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }
    }

    private void checkUser(String userId, int tokenVersion) {
        if (userId == null || userId.isBlank()) {
            throw new BizException(401, "登录凭证无效，请重新登录");
        }
        AppUser u = userRepo.findById(userId).orElse(null);
        if (u == null) {
            throw new BizException(401, "账号不存在，请重新登录");
        }
        if ("banned".equals(u.getStatus())) {
            throw new BizException(401, "账号已被封禁");
        }
        if (u.getTokenVersion() != tokenVersion) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }
    }

    private void checkAdmin(int tokenVersion) {
        if (adminTokenVersion() != tokenVersion) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }
    }

    /** 当前平台端登录态版本号 —— 签发 admin token 时写进 payload */
    public int adminTokenVersion() {
        return config().getAdminTokenVersion();
    }

    // ==================== 作废 ====================

    /** 作废该店已签发的全部 token（封店时调用） */
    public void revokeShop(String shopId) {
        if (shopId == null || shopId.isBlank()) return;   // 登出接口可能拿到残缺 token，静默跳过
        shopRepo.findById(shopId).ifPresent(s -> {
            s.setTokenVersion(s.getTokenVersion() + 1);
            shopRepo.save(s);
        });
    }

    /** 作废该用户已签发的全部 token（封号时调用） */
    public void revokeUser(String userId) {
        if (userId == null || userId.isBlank()) return;
        userRepo.findById(userId).ifPresent(u -> {
            u.setTokenVersion(u.getTokenVersion() + 1);
            userRepo.save(u);
        });
    }

    /** 作废平台端已签发的全部 token（登出 / 改密时调用） */
    public void revokeAdmin() {
        PlatformConfig c = config();
        c.setAdminTokenVersion(c.getAdminTokenVersion() + 1);
        configRepo.save(c);
    }

    /** 单行配置表：读不到就建一条 id=1 的（DataSeeder 之外也要能自愈） */
    private PlatformConfig config() {
        return configRepo.findById(1L).orElseGet(() -> configRepo.save(new PlatformConfig()));
    }
}
