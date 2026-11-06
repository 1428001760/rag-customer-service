package com.example.ragcustomerservice.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ragcustomerservice.entity.ChatLog;
import com.example.ragcustomerservice.service.ChatLogService;
import com.example.ragcustomerservice.service.KnowledgeService;
import com.example.ragcustomerservice.util.ChromaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 知识库 + 数据看板接口。
 *
 * 原来的管理后台「知识库管理」只是一个静态说明页，什么也做不了。
 * 这里补上真正的增删查，以及一个能反映系统运行状况的统计看板。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class KnowledgeAdminController {

    private final KnowledgeService knowledgeService;
    private final ChatLogService chatLogService;
    private final ChromaClient chromaClient;
    private final JdbcTemplate jdbcTemplate;

    @Value("${chroma.collection-name}")
    private String collectionName;

    // ==================== 知识库 ====================

    /**
     * 知识库文档列表。
     *
     * Chroma 没有「按文档聚合」的接口，只能把所有块的元数据捞出来，
     * 在应用层按 file_name 分组统计块数。
     */
    @GetMapping("/knowledge/list")
    public Map<String, Object> knowledgeList() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> docs = new ArrayList<>();

        try {
            // 1. 找到 collection 的 uuid（Chroma 的 get 接口只认 uuid）
            String collectionId = null;
            for (Map<String, Object> c : chromaClient.getCollections()) {
                if (collectionName.equals(String.valueOf(c.get("name")))) {
                    collectionId = String.valueOf(c.get("id"));
                    break;
                }
            }

            if (collectionId != null) {
                // 2. 捞出所有块的元数据
                Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
                for (Map<String, Object> meta : chromaClient.getAllMetadatas(collectionId)) {
                    String fileName = String.valueOf(meta.get("file_name"));
                    if (fileName == null || "null".equals(fileName)) {
                        continue;
                    }
                    Map<String, Object> item = grouped.computeIfAbsent(fileName, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("fileName", k);
                        m.put("chunkCount", 0);
                        m.put("headings", new ArrayList<String>());
                        return m;
                    });
                    item.put("chunkCount", ((Number) item.get("chunkCount")).intValue() + 1);

                    String heading = String.valueOf(meta.get("heading"));
                    if (heading != null && !heading.isEmpty() && !"null".equals(heading)) {
                        @SuppressWarnings("unchecked")
                        List<String> hs = (List<String>) item.get("headings");
                        if (!hs.contains(heading)) {
                            hs.add(heading);
                        }
                    }
                }
                docs = new ArrayList<>(grouped.values());
            }
        } catch (Exception e) {
            log.warn("读取知识库列表失败：{}", e.getMessage());
        }

        result.put("code", 200);
        result.put("data", docs);
        return result;
    }

    /**
     * 删除知识库文档（连同其所有向量块）。
     */
    @PostMapping("/knowledge/delete")
    public Map<String, Object> deleteKnowledge(@RequestParam String fileName) {
        int n = knowledgeService.removeByFileName(fileName);
        Map<String, Object> result = new HashMap<>();
        result.put("code", n > 0 ? 200 : 500);
        result.put("message", n > 0 ? "已删除「" + fileName + "」" : "删除失败");
        return result;
    }

    // ==================== 数据看板 ====================

    /**
     * 概览统计。
     *
     * 这几个数字都是真实算出来的，不是写死的：
     *   - 总对话数、命中率、平均响应耗时：来自 chat_log
     *   - 工单各状态数量：来自 work_order
     *   - 今日对话量：按 create_time 过滤
     */
    @GetMapping("/stats/overview")
    public Map<String, Object> overview() {
        Map<String, Object> data = new HashMap<>();

        Integer totalChat = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_log", Integer.class);
        Integer hitChat = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_log WHERE is_hit = 1", Integer.class);
        Integer todayChat = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_log WHERE DATE(create_time) = CURDATE()", Integer.class);
        Double avgDuration = jdbcTemplate.queryForObject(
                "SELECT ROUND(AVG(duration), 0) FROM chat_log", Double.class);

        Integer totalOrder = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM work_order", Integer.class);
        Integer pendingOrder = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE status = 1", Integer.class);
        Integer doingOrder = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE status = 2", Integer.class);
        Integer doneOrder = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE status = 3", Integer.class);

        int total = totalChat == null ? 0 : totalChat;
        int hit = hitChat == null ? 0 : hitChat;

        data.put("totalChat", total);
        data.put("hitChat", hit);
        data.put("todayChat", todayChat == null ? 0 : todayChat);
        data.put("hitRate", total == 0 ? 0 : Math.round(hit * 1000.0 / total) / 10.0);
        data.put("avgDuration", avgDuration == null ? 0 : avgDuration.intValue());

        data.put("totalOrder", totalOrder == null ? 0 : totalOrder);
        data.put("pendingOrder", pendingOrder == null ? 0 : pendingOrder);
        data.put("doingOrder", doingOrder == null ? 0 : doingOrder);
        data.put("doneOrder", doneOrder == null ? 0 : doneOrder);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    /**
     * 最近 7 天的对话趋势，用于画折线。
     */
    @GetMapping("/stats/trend")
    public Map<String, Object> trend() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT DATE(create_time) AS day,
                       COUNT(*) AS total,
                       SUM(CASE WHEN is_hit = 1 THEN 1 ELSE 0 END) AS hit
                FROM chat_log
                WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)
                GROUP BY DATE(create_time)
                ORDER BY day
                """);

        // 补齐没有数据的日期，避免前端折线断裂
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byDay.put(String.valueOf(row.get("day")), row);
        }

        List<String> days = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        List<Integer> hits = new ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            String d = today.minusDays(i).toString();
            days.add(d);
            Map<String, Object> row = byDay.get(d);
            totals.add(row == null ? 0 : ((Number) row.get("total")).intValue());
            hits.add(row == null ? 0 : ((Number) row.get("hit")).intValue());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("days", days);
        data.put("totals", totals);
        data.put("hits", hits);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    /**
     * 高频问题 Top 10，用于运营侧看用户在问什么。
     */
    @GetMapping("/stats/topQuestions")
    public Map<String, Object> topQuestions() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT question, COUNT(*) AS cnt
                FROM chat_log
                GROUP BY question
                ORDER BY cnt DESC
                LIMIT 10
                """);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", rows);
        return result;
    }

    /**
     * 会话列表：按 session_id 聚合，能看到每个会话聊了多少轮。
     */
    @GetMapping("/session/list")
    public Map<String, Object> sessionList() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT session_id,
                       COUNT(*) AS rounds,
                       MAX(create_time) AS last_time,
                       SUM(CASE WHEN is_hit = 1 THEN 1 ELSE 0 END) AS hit_count
                FROM chat_log
                GROUP BY session_id
                ORDER BY last_time DESC
                LIMIT 100
                """);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", rows);
        return result;
    }

    /**
     * 某个会话的完整对话记录。
     */
    @GetMapping("/session/detail")
    public Map<String, Object> sessionDetail(@RequestParam String sessionId) {
        LambdaQueryWrapper<ChatLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatLog::getSessionId, sessionId);
        wrapper.orderByAsc(ChatLog::getCreateTime);
        List<ChatLog> list = chatLogService.list(wrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    /**
     * 系统健康检查：前端顶部状态条用。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();

        // MySQL
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            data.put("mysql", true);
        } catch (Exception e) {
            data.put("mysql", false);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }
}
