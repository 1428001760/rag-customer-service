package com.example.ragcustomerservice.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import com.example.ragcustomerservice.util.ChromaClient;
import com.example.ragcustomerservice.util.LogUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 知识库服务。
 *
 * 这里最关键的是「怎么切块」。原来的实现用 DocumentSplitters.recursive(500, 50)
 * 直接切，问题是它只按字符数切，会把一条完整的业务规则从中间劈开：
 * 比如「黄金会员：消费累计满5000元升级，享受9折优惠，生日当月赠送200元优惠券」
 * 被切成前半句和后半句两个向量，检索时两边都不够像，相似度双双掉到阈值以下。
 *
 * 现在的策略是三层：
 *   1. 先按 Markdown/中文标题（一、二、三 / 1. 2. 3. / # 标题）切成「章节」
 *   2. 章节内按空行切成「条目」，一个条目就是一个语义单元
 *   3. 单个条目仍然超过上限时，才退化成按句子边界切，且句子不切断
 *
 * 每个块都带 heading 元数据，检索命中后前端能显示「命中章节」，
 * 而不是只知道文件名。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChromaClient chromaClient;
    private final LogUtil logUtil;
    private final Tika tika = new Tika();

    @Value("${chroma.collection-name}")
    private String collectionName;

    /** 单块最大字符数。中文场景 300 字已经能容纳一条完整规则 */
    private static final int MAX_CHUNK_CHARS = 300;

    /** 块之间保留的重叠，避免跨块语义断裂 */
    private static final int OVERLAP_CHARS = 40;

    /** 一级标题：一、二、三 / 第一章 / # 标题 */
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(?:#{1,6}\\s*)?(?:[一二三四五六七八九十]+[、.．]|第[一二三四五六七八九十]+[章节条])\\s*(.+)$"
    );

    /** 编号条目：1. 2. 3. / （1） / - 列表 */
    private static final Pattern ITEM_PATTERN = Pattern.compile(
            "^\\s*(?:\\d+[、.．)）]|[-*•])\\s*(.+)$"
    );

    /**
     * 上传知识库文档。
     * 支持 TXT / PDF / DOCX / Markdown，Apache Tika 统一解析为纯文本。
     * 同名文档自动覆盖。
     */
    public String uploadKnowledge(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename() == null
                ? "document.txt" : file.getOriginalFilename();

        if (file.isEmpty()) {
            return "文件为空，请重新选择";
        }

        String content;
        try {
            content = tika.parseToString(file.getInputStream());
        } catch (Exception e) {
            return "文档「" + originalName + "」解析失败：" + e.getMessage();
        }

        if (content == null || content.trim().isEmpty()) {
            return "文档「" + originalName + "」没有解析出文字内容，可能是扫描件或图片";
        }

        int chunkCount = ingest(originalName, content);

        logUtil.record("system", "knowledge", "upload",
                "上传知识库文档：" + originalName + "，切分为 " + chunkCount + " 个知识块");

        return "文档「" + originalName + "」上传成功，已切分为 " + chunkCount
                + " 个知识块并完成向量化（同名文档已覆盖更新）";
    }

    /**
     * 把一份文档切成知识块、向量化、写入 Chroma。
     * 返回实际写入的块数。
     */
    public int ingest(String fileName, String content) {
        // 1. 同名覆盖：先删掉这个文件之前的所有块，避免重复堆积
        try {
            Filter oldDocFilter = metadataKey("file_name").isEqualTo(fileName);
            embeddingStore.removeAll(oldDocFilter);
        } catch (Exception e) {
            log.warn("清理旧向量失败（首次上传可忽略）：{}", e.getMessage());
        }

        // 2. 切块
        List<Chunk> chunks = split(content);
        if (chunks.isEmpty()) {
            return 0;
        }

        // 3. 逐块向量化并写入
        int written = 0;
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            try {
                Metadata metadata = Metadata.from(new HashMap<>());
                metadata.put("file_name", fileName);
                metadata.put("heading", c.heading == null ? "" : c.heading);
                metadata.put("chunk_index", String.valueOf(i));

                TextSegment segment = TextSegment.from(c.text, metadata);
                var embedding = embeddingModel.embed(segment).content();
                embeddingStore.add(embedding, segment);
                written++;
            } catch (Exception e) {
                log.error("第 {} 块向量化失败：{}", i, e.getMessage());
            }
        }
        log.info("文档 [{}] 切分 {} 块，成功写入 {} 块", fileName, chunks.size(), written);
        return written;
    }

    /**
     * 三层切块。
     */
    List<Chunk> split(String content) {
        List<Chunk> result = new ArrayList<>();

        // 统一换行符
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");

        // ---- 第一层：按标题切成章节 ----
        List<Section> sections = new ArrayList<>();
        String currentHeading = "概述";
        StringBuilder currentBody = new StringBuilder();

        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            Matcher hm = HEADING_PATTERN.matcher(line);
            // 标题行：以「一、」「第一章」开头，或者 # 开头，且长度不过长
            boolean isHeading = (hm.matches() || line.startsWith("#")) && line.length() <= 40;

            if (isHeading) {
                if (currentBody.length() > 0) {
                    sections.add(new Section(currentHeading, currentBody.toString().trim()));
                    currentBody.setLength(0);
                }
                currentHeading = line.replaceAll("^#+\\s*", "").trim();
            } else {
                currentBody.append(line).append("\n");
            }
        }
        if (currentBody.length() > 0) {
            sections.add(new Section(currentHeading, currentBody.toString().trim()));
        }

        // ---- 第二层：章节内按空行/编号条目切成小块 ----
        for (Section section : sections) {
            if (section.body.isEmpty()) {
                continue;
            }

            // 按空行分段
            String[] paragraphs = section.body.split("\n\\s*\n");
            for (String para : paragraphs) {
                String p = para.trim();
                if (p.isEmpty()) {
                    continue;
                }

                if (p.length() <= MAX_CHUNK_CHARS) {
                    result.add(new Chunk(section.heading, withHeading(section.heading, p)));
                } else {
                    // ---- 第三层：超长段落按句子边界切 ----
                    for (String piece : splitLongText(p)) {
                        result.add(new Chunk(section.heading, withHeading(section.heading, piece)));
                    }
                }
            }
        }

        return result;
    }

    /**
     * 超长文本按句子边界切分，保证句子不被拦腰截断。
     */
    private List<String> splitLongText(String text) {
        List<String> pieces = new ArrayList<>();
        // 按中文句号/问号/叹号/分号断句，保留标点
        String[] sentences = text.split("(?<=[。！？；\\n])");

        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (buf.length() + s.length() > MAX_CHUNK_CHARS && buf.length() > 0) {
                pieces.add(buf.toString().trim());
                // 保留尾部一点重叠，避免上下文断裂
                String tail = buf.length() > OVERLAP_CHARS
                        ? buf.substring(buf.length() - OVERLAP_CHARS) : "";
                buf.setLength(0);
                buf.append(tail);
            }
            buf.append(s);
        }
        if (buf.length() > 0) {
            pieces.add(buf.toString().trim());
        }
        return pieces;
    }

    /**
     * 给块加上标题前缀。
     * 这一步很重要：向量化的是带标题的完整文本，
     * 用户问「会员有什么优惠」时，块里带着「三、会员权益」，
     * 语义相似度会明显高于裸文本。
     */
    private String withHeading(String heading, String body) {
        if (heading == null || heading.isEmpty() || "概述".equals(heading)) {
            return body;
        }
        return "【" + heading + "】\n" + body;
    }

    /**
     * 删除某个文档的全部知识块。
     */
    public int removeByFileName(String fileName) {
        try {
            Filter filter = metadataKey("file_name").isEqualTo(fileName);
            embeddingStore.removeAll(filter);
            logUtil.record("system", "knowledge", "delete", "删除知识库文档：" + fileName);
            return 1;
        } catch (Exception e) {
            log.error("删除文档失败：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 文档摘要列表（公开接口用）。
     *
     * 和 /admin/knowledge/list 的区别：这里只返回文件名 + 块数，
     * 不返回章节名，避免把知识库结构暴露给未登录的访客。
     */
    public List<Map<String, Object>> listDocumentSummary() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Map<String, Object> meta : chromaClient.getAllMetadatas(collectionId())) {
                String fileName = String.valueOf(meta.get("file_name"));
                if (fileName == null || "null".equals(fileName)) {
                    continue;
                }
                Map<String, Object> found = out.stream()
                        .filter(m -> fileName.equals(m.get("fileName")))
                        .findFirst()
                        .orElse(null);
                if (found == null) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("fileName", fileName);
                    item.put("chunkCount", 1);
                    out.add(item);
                } else {
                    item0(found);
                }
            }
        } catch (Exception e) {
            log.warn("读取文档摘要失败：{}", e.getMessage());
        }
        return out;
    }

    private void item0(Map<String, Object> item) {
        item.put("chunkCount", ((Number) item.get("chunkCount")).intValue() + 1);
    }

    /** 取当前 collection 的 uuid */
    private String collectionId() {
        for (Map<String, Object> c : chromaClient.getCollections()) {
            if (collectionName.equals(String.valueOf(c.get("name")))) {
                return String.valueOf(c.get("id"));
            }
        }
        return null;
    }

    private record Section(String heading, String body) {}

    /** 一个知识块 */
    public record Chunk(String heading, String text) {}
}
