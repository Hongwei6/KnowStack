package com.knowstack.engine.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("qa_cache_entry")
public class QaCacheEntry {

    @TableId(type = IdType.INPUT)
    private String id;
    private String fingerprint;
    private String questionKey;
    private String question;
    private String answer;
    private String sources;
    private Integer frequency;
    private Double quality;
    private String reason;
    private String status;
    private String reviewer;
    private String reviewNote;
    private LocalDateTime reviewedAt;
    private LocalDateTime expiresAt;
    private String indexError;
    private Long hits;
    private LocalDateTime activatedAt;
    private LocalDateTime disabledAt;
    private String disableReason;
    private String disableDetail;
    private String dependencies;
    private Integer vectorDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
