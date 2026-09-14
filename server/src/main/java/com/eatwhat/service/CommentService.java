package com.eatwhat.service;

import com.eatwhat.common.BizException;
import com.eatwhat.entity.AppUser;
import com.eatwhat.entity.Comment;
import com.eatwhat.repository.CommentRepository;
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

    public CommentService(CommentRepository repo, AppUserService userService) {
        this.repo = repo;
        this.userService = userService;
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

    /** 商家回评 */
    public Comment reply(String commentId, String content) {
        Comment c = repo.findById(commentId)
                .orElseThrow(() -> new BizException(404, "评论不存在"));
        if (content == null || content.isBlank()) throw new BizException("回复内容不能为空");

        c.setReplyContent(content);
        c.setReplyAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT));
        return repo.save(c);
    }

    /** 商家侧：不回复（清空回评） */
    public Comment clearReply(String commentId) {
        Comment c = repo.findById(commentId)
                .orElseThrow(() -> new BizException(404, "评论不存在"));
        c.setReplyContent(null);
        c.setReplyAt(null);
        return repo.save(c);
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
