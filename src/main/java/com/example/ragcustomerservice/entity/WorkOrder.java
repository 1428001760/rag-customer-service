package com.example.ragcustomerservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("work_order")
public class WorkOrder {
    public static final int STATUS_PENDING = 1;    // 待处理
    public static final int STATUS_PROCESSING = 2; // 处理中(已认领)
    public static final int STATUS_DONE = 3;       // 已完成

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String question;
    private Integer status;
    private String handler;
    private String reply;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}