package com.example.ragcustomerservice.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 人工客服消息推送:在线直推 + 失败重试 + 离线补偿队列
 */
@Service
@RequiredArgsConstructor
public class PushService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PENDING_PUSH_PREFIX = "pending_push:";
    private static final Duration PENDING_TTL = Duration.ofHours(24);
    private static final int MAX_RETRY = 3;
    private static final long RETRY_INTERVAL_MS = 300;

    /**
     * 发送人工客服消息:在线直推(失败重试3次);离线/推送失败写入补偿队列
     */
    public void sendOrQueue(String sessionId, String message) {
        String json = buildAgentEvent(message);
        for (int i = 0; i < MAX_RETRY; i++) {
            if (ChatWebSocketHandler.sendDirect(sessionId, json)) {
                return;
            }
            sleepQuietly(RETRY_INTERVAL_MS);
        }
        String key = PENDING_PUSH_PREFIX + sessionId;
        redisTemplate.opsForList().rightPush(key, json);
        redisTemplate.expire(key, PENDING_TTL);
    }

    /**
     * 补发离线消息:用户建立 WebSocket 连接时调用
     */
    public void drainPending(String sessionId) {
        String key = PENDING_PUSH_PREFIX + sessionId;
        while (true) {
            Object message = redisTemplate.opsForList().leftPop(key);
            if (message == null) {
                break;
            }
            ChatWebSocketHandler.sendDirect(sessionId, message.toString());
        }
    }

    private String buildAgentEvent(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "agent");
        node.put("text", text);
        return node.toString();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}