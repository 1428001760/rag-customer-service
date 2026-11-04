package com.example.ragcustomerservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("chat_log")
public class ChatLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String question;
    private String answer;
    private Integer isHit;
    private Integer duration;
    private LocalDateTime createTime;
}
