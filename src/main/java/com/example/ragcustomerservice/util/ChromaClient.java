package com.example.ragcustomerservice.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Chroma 向量库的轻量 HTTP 客户端。
 *
 * langchain4j 的 ChromaEmbeddingStore 只封装了 add / search / remove，
 * 没有暴露「列出所有文档」这类管理接口。管理后台需要按文件名聚合展示，
 * 所以这里直接用 RestTemplate 打 Chroma 的 REST API。
 *
 * 注意：Chroma v1 的 collection 接口只认 uuid，不认 name，
 * 所以要先 getCollections() 拿到 name -> id 的映射。
 */
@Slf4j
@Component
public class ChromaClient {

    @Value("${chroma.url}")
    private String chromaUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /** 列出所有 collection，返回 [{name, id, ...}, ...] */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getCollections() {
        try {
            String url = chromaUrl + "/api/v1/collections";
            ResponseEntity<List> resp = restTemplate.getForEntity(url, List.class);
            List<?> body = resp.getBody();
            if (body == null) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : body) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("获取 collection 列表失败：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 取某个 collection 下所有块的元数据 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllMetadatas(String collectionId) {
        try {
            String url = chromaUrl + "/api/v1/collections/" + collectionId + "/get";
            Map<String, Object> req = new HashMap<>();
            req.put("limit", 10000);
            req.put("include", List.of("metadatas"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(req, headers);

            ResponseEntity<Map> resp = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> body = resp.getBody();
            if (body == null) {
                return Collections.emptyList();
            }
            Object metas = body.get("metadatas");
            if (metas instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        out.add((Map<String, Object>) m);
                    }
                }
                return out;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("读取 Chroma 元数据失败：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 取某个 collection 的总块数 */
    public int count(String collectionId) {
        try {
            String url = chromaUrl + "/api/v1/collections/" + collectionId + "/count";
            ResponseEntity<Integer> resp = restTemplate.getForEntity(url, Integer.class);
            return resp.getBody() == null ? 0 : resp.getBody();
        } catch (Exception e) {
            return 0;
        }
    }
}
