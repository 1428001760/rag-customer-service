package com.example.ragcustomerservice.service;

import com.example.ragcustomerservice.entity.ChatLog;
import com.example.ragcustomerservice.mapper.ChatLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatLogService {

    private final ChatLogMapper chatLogMapper;

    /**
     * 保存一条对话日志
     */
    public void saveLog(String sessionId, String question, String answer, Integer isHit, Integer duration) {
        ChatLog log = new ChatLog();
        log.setSessionId(sessionId);
        log.setQuestion(question);
        log.setAnswer(answer);
        log.setIsHit(isHit);
        log.setDuration(duration);
        chatLogMapper.insert(log);
    }

    /**
     * 查询所有对话日志
     */
    public List<ChatLog> listAll() {
        return chatLogMapper.selectList(null);
    }
}
