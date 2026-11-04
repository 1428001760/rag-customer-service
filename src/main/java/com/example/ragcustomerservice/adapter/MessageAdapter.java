package com.example.ragcustomerservice.adapter;

/**
 * 消息适配器抽象层
 * 预留多渠道接入能力，网页端、企业微信等渠道只需实现该接口即可接入
 */
public interface MessageAdapter {

    /**
     * 发送消息给用户
     * @param sessionId 会话ID
     * @param message 消息内容
     */
    void sendMessage(String sessionId, String message);

    /**
     * 接收用户消息
     * @param sessionId 会话ID
     * @param message 用户输入
     * @return AI回复
     */
    String receiveMessage(String sessionId, String message);

    /**
     * 渠道标识
     */
    String getChannel();
}
