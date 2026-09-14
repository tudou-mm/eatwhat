package com.eatwhat.entity;

import jakarta.persistence.*;

/**
 * 举报记录。
 * type: dish | comment
 * status: pending | confirmed(确认违规) | rejected(驳回)
 */
@Entity
@Table(name = "report")
public class Report {

    @Id
    private String id;

    /** dish | comment */
    private String type;

    private String targetId;
    private String targetName;
    private String reason;
    private String reporter;
    private String at;

    /** pending | confirmed | rejected */
    private String status = "pending";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getReporter() { return reporter; }
    public void setReporter(String reporter) { this.reporter = reporter; }
    public String getAt() { return at; }
    public void setAt(String at) { this.at = at; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
