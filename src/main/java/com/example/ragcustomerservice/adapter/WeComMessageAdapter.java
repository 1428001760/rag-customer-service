package com.example.ragcustomerservice.adapter;

import com.example.ragcustomerservice.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 企业微信渠道适配器（Mock预留实现）
 * 后续接入真实企业微信API只需替换内部实现即可
 */
@Component
@RequiredArgsConstructor
public class WeComMessageAdapter implements MessageAdapter {

    private final ChatService chatService;

    @Override
    public void sendMessage(String sessionId, String message) {
        // Mock：预留企业微信消息推送接口
        System.out.println("[企业微信Mock] 向用户" + sessionId + "发送：" + message);
    }

    @Override
    public String receiveMessage(String sessionId, String message) {
        return chatService.chatWithSession(message, sessionId);
    }

    @Override
    public String getChannel() {
        return "wecom";
    }
}
