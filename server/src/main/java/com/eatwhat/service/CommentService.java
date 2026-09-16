package com.eatwhat.service;

import com.eatwhat.common.BizException;
import com.eatwhat.entity.AppUser;
import com.eatwhat.entity.Comment;
import com.eatwhat.entity.Dish;
import com.eatwhat.entity.Shop;
import com.eatwhat.repository.CommentRepository;
import com.eatwhat.repository.DishRepository;
import com.eatwhat.repository.ShopRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 评论服务。含商家回评与违规处理。
 */
@Service
public class CommentService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CommentRepository repo;
    private final AppUserService userService;
    private final DishRepository dishRepo;
    private final ShopRepository shopRepo;

    public CommentService(CommentRepository repo, AppUserService userService,
                          DishRepository dishRepo, ShopRepository shopRepo) {
        this.repo = repo;
        this.userService = userService;
        this.dishRepo = dishRepo;
        this.shopRepo = shopRepo;
    }

    public List<Comment> listByDish(String dishId) {
        return repo.findByDishIdAndStatusOrderByAtTsDesc(dishId, "normal");
    }

    /** 用户发评论 —— 会校验用户是否被封号 */
    public Comment add(String dishId, String userId, String content) {
        if (content == null || content.isBlank()) throw new BizException("评论内容不能为空");

        AppUser u = userService.get(userId);

        // 封号 / 警告中的用户处理
        if ("banned".equals(u.getStatus())) {
            throw new BizException(403, "账号已被封禁，无法发表评论");
        }

        Comment c = new Comment();
        c.setId("c_" + System.currentTimeMillis());
        c.setDishId(dishId);
        c.setUserId(u.getId());
        c.setUserName(u.getName());
        c.setAvatar(u.getAvatar());
        c.setContent(content);
        long now = System.currentTimeMillis();
        c.setAtTs(now);
        c.setAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT));
        c.setStatus("normal");

        repo.save(c);
        userService.incCommentCount(u.getId());
        return c;
    }

    /** 取单条评论（越权校验要先拿到它才知道属于哪道菜） */
    public Comment get(String commentId) {
        return repo.findById(commentId)
                .orElseThrow(() -> new BizException(404, "评论不存在"));
    }

    /** 商家回评 */
    public Comment reply(String commentId, String content) {
        Comment c = repo.findById(commentId)
                .orElseThrow(() -> new BizException(404, "评论不存在"));
        if (content == null || content.isBlank()) throw new BizException("回复内容不能为空");
        assertShopCanReply(c.getDishId());

        c.setReplyContent(content);
        c.setReplyAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT));
        return repo.save(c);
    }

    /** 商家侧：不回复（清空回评） */
    public Comment clearReply(String commentId) {
        Comment c = repo.findById(commentId)
                .orElseThrow(() -> new BizException(404, "评论不存在"));
        assertShopCanReply(c.getDishId());
        c.setReplyContent(null);
        c.setReplyAt(null);
        return repo.save(c);
    }

    /**
     * 回评前的店铺状态校验。
     *
     * 这条规则原先只写在 {@code ShopService.mute()} 的注释里（「禁言：不能发布、不能回评」），
     * 但**只有「不能发布」真的落地了**，回评这条一直没实现 ——
     * 被禁言的店照样能正常回评。注释里写了规则、代码里没写，等于没写。
     *
     * 放在 Service 层而不是 Controller：规则是业务属性，换个入口（比如以后加个小程序端接口）
     * 就不该再漏一次。
     */
    private void assertShopCanReply(String dishId) {
        Dish d = dishRepo.findById(dishId).orElse(null);
        if (d == null) return;                     // 菜都没了，让上层按正常流程报错
        Shop s = shopRepo.findById(d.getShopId()).orElse(null);
        if (s == null) return;
        if ("muted".equals(s.getStatus())) {
            throw new BizException(403, "店铺已被禁言，无法回评");
        }
        // 封店的请求其实在拦截器层就被 401 拦掉了，这句是兜底：
        // 万一以后有别的路径能调进来，这里仍然拦得住
        if ("banned".equals(s.getStatus())) {
            throw new BizException(403, "店铺已被封禁，无法回评");
        }
    }

    /** 平台端：下架评论（不物理删除） */
    public Comment remove(String commentId) {
        Comment c = repo.findById(commentId)
                .orElseThrow(() -> new BizException(404, "评论不存在"));
        c.setStatus("removed");
        return repo.save(c);
    }

    /** 全部评论（平台端内容管理用） */
    public List<Comment> listAll() {
        return repo.findAll();
    }
}
