package com.eatwhat.entity;

import jakarta.persistence.*;

/**
 * 评论。含商家回评（replyContent 非空即为已回）。
 */
@Entity
@Table(name = "comment")
public class Comment {

    @Id
    private String id;

    private String dishId;
    private String userId;
    private String userName;

    @Column(length = 500)
    private String avatar;

    @Column(length = 1000)
    private String content;

    private String at;
    private Long atTs;

    /** 商家回评内容，null 表示未回 */
    @Column(length = 1000)
    private String replyContent;

    private String replyAt;

    /** normal | removed */
    private String status = "normal";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDishId() { return dishId; }
    public void setDishId(String dishId) { this.dishId = dishId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getAt() { return at; }
    public void setAt(String at) { this.at = at; }
    public Long getAtTs() { return atTs; }
    public void setAtTs(Long atTs) { this.atTs = atTs; }
    public String getReplyContent() { return replyContent; }
    public void setReplyContent(String replyContent) { this.replyContent = replyContent; }
    public String getReplyAt() { return replyAt; }
    public void setReplyAt(String replyAt) { this.replyAt = replyAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
