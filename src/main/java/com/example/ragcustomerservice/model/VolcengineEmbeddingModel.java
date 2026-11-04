package com.example.ragcustomerservice.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

public class VolcengineEmbeddingModel implements EmbeddingModel {

    private static final String EMBEDDINGS_ENDPOINT = "/embeddings/multimodal";

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VolcengineEmbeddingModel(String apiKey, String baseUrl, String modelName) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", modelName);
        request.put("encoding_format", "float");

        ArrayNode input = request.putArray("input");
        for (TextSegment segment : textSegments) {
            ObjectNode part = input.addObject();
            part.put("type", "text");
            part.put("text", segment.text());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<String> entity = new HttpEntity<>(request.toString(), headers);

        String body = restTemplate.postForObject(baseUrl + EMBEDDINGS_ENDPOINT, entity, String.class);
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("解析火山引擎向量化响应失败: " + body, e);
        }

        JsonNode data = root.get("data");
        if (data == null) {
            throw new RuntimeException("火山引擎向量化响应缺少 data 字段: " + body);
        }

        List<Embedding> embeddings = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                embeddings.add(toEmbedding(item));
            }
        } else {
            embeddings.add(toEmbedding(data));
        }

        JsonNode usageNode = root.get("usage");
        TokenUsage tokenUsage = null;
        if (usageNode != null && usageNode.has("total_tokens")) {
            // 修正：TokenUsage构造参数为 inputTokenCount, outputTokenCount, totalTokenCount
            tokenUsage = new TokenUsage(null, null, usageNode.get("total_tokens").asInt());
        }

        return Response.from(embeddings, tokenUsage);
    }

    private Embedding toEmbedding(JsonNode item) {
        JsonNode vectorNode = item.get("embedding");
        if (vectorNode == null || !vectorNode.isArray()) {
            throw new RuntimeException("火山引擎向量化响应缺少 embedding 字段: " + item);
        }
        float[] vector = new float[vectorNode.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) vectorNode.get(i).asDouble();
        }
        return Embedding.from(vector);
    }
}
