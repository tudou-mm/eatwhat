package com.eatwhat.entity;

import jakarta.persistence.*;

/**
 * 用户（客户端食客）。
 * status: normal | warned(已警告) | banned(封号，保留历史评论)
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private String id;

    private String name;

    @Column(length = 500)
    private String avatar;

    private String phone;
    private String city;
    private String district;

    /** normal | warned | banned */
    private String status = "normal";

    /** 累计评论数 */
    private Integer commentCount = 0;

    /** 被举报次数 */
    private Integer reportCount = 0;

    /** 注册日期 */
    private String at;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCommentCount() { return commentCount; }
    public void setCommentCount(Integer commentCount) { this.commentCount = commentCount; }
    public Integer getReportCount() { return reportCount; }
    public void setReportCount(Integer reportCount) { this.reportCount = reportCount; }
    public String getAt() { return at; }
    public void setAt(String at) { this.at = at; }
}
