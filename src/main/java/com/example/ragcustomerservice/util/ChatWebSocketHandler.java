package com.example.ragcustomerservice.util;

import com.example.ragcustomerservice.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Component
@ServerEndpoint("/ws/chat/{sessionId}")
public class ChatWebSocketHandler {

    // 存储所有在线用户的WebSocket连接，key是sessionId
    private static final Map<String, Session> sessionMap = new ConcurrentHashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @OnOpen
    public void onOpen(Session session, @PathParam("sessionId") String sessionId) {
        sessionMap.put(sessionId, session);
        System.out.println("[WebSocket] 连接建立 session=" + sessionId);
        SpringContextUtil.getBean(PushService.class).drainPending(sessionId);
    }

    @OnClose
    public void onClose(@PathParam("sessionId") String sessionId) {
        sessionMap.remove(sessionId);
        System.out.println("[WebSocket] 连接断开 session=" + sessionId);
    }

    /**
     * 接收用户提问(JSON: {"type":"question","text":"客服电话"})
     * 异步调用大模型,把 token 逐个推回前端;任何异常都会回推 ai_error
     */
    @OnMessage
    public void onMessage(String message, @PathParam("sessionId") String sessionId) {
        System.out.println("[WebSocket] 收到消息 session=" + sessionId + " message=" + message);

        String question;
        try {
            JsonNode node = objectMapper.readTree(message);
            question = node.path("text").asText();
        } catch (Exception e) {
            sendEvent(sessionId, "ai_error", "消息格式错误");
            return;
        }
        if (question == null || question.trim().isEmpty()) {
            return;
        }

        try {
            ChatService chatService = SpringContextUtil.getBean(ChatService.class);
            ExecutorService executor = SpringContextUtil.getBean(ExecutorService.class);
            executor.execute(() -> chatService.chatWithSessionStream(
                    question,
                    sessionId,
                    token -> sendEvent(sessionId, "ai_token", token),
                    () -> sendEvent(sessionId, "ai_done", null),
                    error -> sendEvent(sessionId, "ai_error",
                            error.getMessage() == null ? "生成失败，请稍后重试" : error.getMessage())
            ));
        } catch (Exception e) {
            System.out.println("[WebSocket] 处理异常: " + e.getMessage());
            e.printStackTrace();
            sendEvent(sessionId, "ai_error", "系统内部错误，请稍后重试");
        }
    }

    /**
     * 发送结构化事件:{"type":"ai_token","text":"..."}
     */
    private void sendEvent(String sessionId, String type, String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        if (text != null) {
            node.put("text", text);
        }
        sendDirect(sessionId, node.toString());
    }

    /**
     * 直接推送(加锁防止并发send):成功返回true,连接不存在/已关闭/发送异常返回false
     */
    public static boolean sendDirect(String sessionId, String message) {
        Session session = sessionMap.get(sessionId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        synchronized (session) {
            try {
                session.getBasicRemote().sendText(message);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}