package com.example.ragcustomerservice.adapter;

import com.example.ragcustomerservice.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebMessageAdapter implements MessageAdapter {

    private final ChatService chatService;

    @Override
    public void sendMessage(String sessionId, String message) {
        // Web端通过WebSocket推送，已在ChatWebSocketHandler实现
    }

    @Override
    public String receiveMessage(String sessionId, String message) {
        return chatService.chatWithSession(message, sessionId);
    }

    @Override
    public String getChannel() {
        return "web";
    }
}
