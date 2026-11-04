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

    @Value("${volcengine.chat-endpoint-id}")
    private String chatEndpointId;

    @Value("${volcengine.embedding-endpoint-id}")
    private String embeddingEndpointId;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(chatEndpointId)
                .temperature(0.2)
                .timeout(Duration.ofSeconds(30))
                .maxTokens(400)
                .maxRetries(0)
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(chatEndpointId)
                .temperature(0.2)
                .timeout(Duration.ofSeconds(30))
                .maxTokens(400)
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