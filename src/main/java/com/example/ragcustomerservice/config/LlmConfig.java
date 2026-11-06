package com.example.ragcustomerservice.config;

import com.example.ragcustomerservice.model.VolcengineEmbeddingModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class LlmConfig {

    @Value("${volcengine.api-key}")
    private String apiKey;

    @Value("${volcengine.base-url}")
    private String baseUrl;

    @Value("${volcengine.chat-model}")
    private String chatModel;

    @Value("${volcengine.embedding-endpoint-id}")
    private String embeddingEndpointId;

    /**
     * 主对话模型。
     *
     * 原来写死成 chat-endpoint-id（一个小号 doubao 端点），
     * 换成 deepseek-v4-flash 之后通用问答、推理、写作能力都强一截，
     * 实测 2-7 秒响应，适合客服这种要即时感的场景。
     * 想换回去改 application.yml 里的 volcengine.chat-model 即可，不用动代码。
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(chatModel)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(60))
                .maxTokens(800)
                .maxRetries(1)
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(chatModel)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(60))
                .maxTokens(800)
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService sseExecutor() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new VolcengineEmbeddingModel(apiKey, baseUrl, embeddingEndpointId);
    }
}