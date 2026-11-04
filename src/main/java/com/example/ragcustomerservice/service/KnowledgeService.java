package com.example.ragcustomerservice.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.Filter;
import com.example.ragcustomerservice.util.LogUtil;
import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final LogUtil logUtil;
    private final Tika tika = new Tika();

    /**
     * 上传知识库文档
     * 支持 TXT / PDF / DOCX / Markdown 格式，Apache Tika自动解析为纯文本
     * 同名文档自动覆盖更新
     */
    public String uploadKnowledge(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename() == null ? "document.txt" : file.getOriginalFilename();

        try {
            // 1. Tika解析多格式文档为纯文本
            String content = tika.parseToString(file.getInputStream());

            // 2. 同名覆盖:先删除该文件之前的所有向量块
            Filter oldDocFilter = metadataKey("file_name").isEqualTo(originalName);
            embeddingStore.removeAll(oldDocFilter);

            // 3. 构造文档元数据
            Metadata metadata = Metadata.from(new HashMap<>());
            metadata.put("file_name", originalName);
            Document document = Document.from(content, metadata);

            // 4. 文本分块 + 向量化 + 存入Chroma向量库
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .documentSplitter(DocumentSplitters.recursive(500, 50))
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .build();

            ingestor.ingest(document);

            // 5. 记录操作日志
            logUtil.record("system", "knowledge", "upload", "上传知识库文档(同名覆盖)：" + originalName);

        } catch (Exception e) {
            return "文档「" + originalName + "」解析失败：" + e.getMessage();
        }

        return "文档「" + originalName + "」上传成功，已向量化存入知识库（同名文档已覆盖更新）";
    }
}