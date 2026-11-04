package com.example.ragcustomerservice.controller;

import com.example.ragcustomerservice.service.ChatService;
import com.example.ragcustomerservice.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final KnowledgeService knowledgeService;
    private final ExecutorService sseExecutor;

    /**
     * 单轮问答
     */
    @GetMapping("/ask")
    public Map<String, Object> ask(@RequestParam String question) {
        String answer = chatService.chat(question);
        Map<String, Object> result = new HashMap<>();
        result.put("answer", answer);
        return result;
    }

    /**
     * 多轮问答
     */
    @GetMapping("/askWithSession")
    public Map<String, Object> askWithSession(@RequestParam String question, @RequestParam String sessionId) {
        String answer = chatService.chatWithSession(question, sessionId);
        Map<String, Object> result = new HashMap<>();
        result.put("answer", answer);
        return result;
    }

    /**
     * 流式问答(SSE):每个 token 以 "data:xxx\n\n" 推给前端
     */
    @GetMapping(value = "/askStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestParam String question, @RequestParam String sessionId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        sseExecutor.execute(() -> chatService.chatWithSessionStream(
                question,
                sessionId,
                token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::complete,
                emitter::completeWithError
        ));
        return emitter;
    }

    /**
     * 获取会话历史记录
     */
    @GetMapping("/history")
    public Map<String, Object> getHistory(@RequestParam String sessionId) {
        List<String> history = chatService.getHistory(sessionId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", history);
        return result;
    }

    /**
     * 上传知识库文档
     */
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws IOException {
        return knowledgeService.uploadKnowledge(file);
    }
}