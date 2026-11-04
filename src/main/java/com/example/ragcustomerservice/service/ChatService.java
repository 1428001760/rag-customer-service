package com.example.ragcustomerservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ChatLogService chatLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CHAT_HISTORY_PREFIX = "chat:history:";
    private static final String RETRIEVAL_CACHE_PREFIX = "retrieval_cache:";
    private static final String REWRITE_CACHE_PREFIX = "rewrite_cache:";
    private static final String ANSWER_CACHE_PREFIX = "answer_cache:";
    private static final int MAX_HISTORY_ROUNDS = 3;
    private static final Duration RETRIEVAL_CACHE_TTL = Duration.ofHours(1);
    private static final Duration REWRITE_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration ANSWER_CACHE_TTL = Duration.ofHours(1);
    private static final Duration HISTORY_TTL = Duration.ofMinutes(30);

    /**
     * 寒暄词表:命中直接秒回,不走 embedding + 大模型
     */
    private static final Set<String> CASUAL_WORDS = Set.of(
            "嗯嗯", "嗯", "好的", "好", "谢谢", "感谢", "你好", "您好",
            "在吗", "在不在", "客服在吗", "有人吗", "早上好", "下午好", "晚上好", "hi", "hello", "哈喽"
    );

    private static final String CASUAL_REPLY =
            "您好！我是企业智能客服助手，您可以问我关于企业产品、服务、售后等方面的问题，我会尽力为您解答。";

    /**
     * 检索未命中的固定拒答文案:直接返回,不调用大模型(大模型挂掉时也能秒回)
     */
    private static final String NO_ANSWER_REPLY = "抱歉，该问题超出我的服务范围，请您转人工咨询";

    /**
     * 指代词检测:问题里出现这些字才触发查询改写,避免无谓的额外调用
     */
    private static final Pattern PRONOUN_PATTERN = Pattern.compile("这|那|它|他|她|其|该|上面|刚才|也|还|呢|吗");

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalCache {
        private String context;
        private List<String> docNames;
        private boolean hit;
    }

    /**
     * 单轮问答
     */
    public String chat(String question) {
        return chatWithSession(question, "default");
    }

    /**
     * 多轮问答(非流式,前端 HTTP 兜底用)
     */
    public String chatWithSession(String question, String sessionId) {
        long startTime = System.currentTimeMillis();
        System.out.println("[客服问答] HTTP收到问题 session=" + sessionId + " question=" + question);

        String casualReply = quickCasualReply(question);
        if (casualReply != null) {
            saveHistoryAndLog(sessionId, question, casualReply, false, startTime);
            return casualReply;
        }

        List<String> history = loadHistory(sessionId);
        String historyText = String.join("\n", history);

        String searchQuestion = rewriteQuestionIfNeeded(question, historyText, sessionId);
        RetrievalCache retrieval = retrieve(searchQuestion);
        System.out.println("[客服问答] 检索命中=" + retrieval.isHit() + " 来源=" + retrieval.getDocNames());

        // 检索未命中:直接返回固定拒答,不再调用大模型(大模型挂掉时也能秒回)
        if (!retrieval.isHit()) {
            System.out.println("[客服问答] 检索未命中,秒拒(不调用大模型)");
            saveHistoryAndLog(sessionId, question, NO_ANSWER_REPLY, false, startTime);
            return NO_ANSWER_REPLY;
        }

        // 答案缓存:FAQ 答案固定,跨会话可共享,命中直接秒回
        String finalAnswer = loadAnswerCache(searchQuestion);
        if (finalAnswer != null) {
            System.out.println("[客服问答] 答案缓存命中,秒回: " + searchQuestion);
            saveHistoryAndLog(sessionId, question, finalAnswer, retrieval.isHit(), startTime);
            return finalAnswer;
        }

        String prompt = buildPrompt(historyText, retrieval.getContext(), question);
        String answer = chatLanguageModel.generate(prompt);
        finalAnswer = appendSources(answer, retrieval.getDocNames(), retrieval.isHit());

        if (retrieval.isHit()) {
            redisTemplate.opsForValue().set(ANSWER_CACHE_PREFIX + searchQuestion, finalAnswer, ANSWER_CACHE_TTL);
        }

        saveHistoryAndLog(sessionId, question, finalAnswer, retrieval.isHit(), startTime);
        return finalAnswer;
    }

    /**
     * 多轮问答(流式,供 WebSocket 调用)
     * 全程任何异常都会回调 onError,绝不静默吞掉
     */
    public void chatWithSessionStream(String question, String sessionId,
                                      Consumer<String> onToken,
                                      Runnable onComplete,
                                      Consumer<Throwable> onError) {
        long startTime = System.currentTimeMillis();
        System.out.println("[客服问答] WS收到问题 session=" + sessionId + " question=" + question);

        String casualReply = quickCasualReply(question);
        if (casualReply != null) {
            System.out.println("[客服问答] 寒暄秒回");
            saveHistoryAndLog(sessionId, question, casualReply, false, startTime);
            onToken.accept(casualReply);
            onComplete.run();
            return;
        }

        try {
            List<String> history = loadHistory(sessionId);
            String historyText = String.join("\n", history);

            String searchQuestion = rewriteQuestionIfNeeded(question, historyText, sessionId);
            RetrievalCache retrieval = retrieve(searchQuestion);
            System.out.println("[客服问答] 检索命中=" + retrieval.isHit() + " 来源=" + retrieval.getDocNames());

            // 检索未命中:秒拒,不调用大模型(大模型挂掉时也能秒回)
            if (!retrieval.isHit()) {
                System.out.println("[客服问答] 检索未命中,秒拒(不调用大模型)");
                saveHistoryAndLog(sessionId, question, NO_ANSWER_REPLY, false, startTime);
                onToken.accept(NO_ANSWER_REPLY);
                onComplete.run();
                return;
            }

            // 答案缓存命中:一次性推完,不调大模型
            String cachedAnswer = loadAnswerCache(searchQuestion);
            if (cachedAnswer != null) {
                System.out.println("[客服问答] 答案缓存命中,秒回: " + searchQuestion);
                saveHistoryAndLog(sessionId, question, cachedAnswer, retrieval.isHit(), startTime);
                onToken.accept(cachedAnswer);
                onComplete.run();
                return;
            }

            String prompt = buildPrompt(historyText, retrieval.getContext(), question);

            StringBuilder answerBuilder = new StringBuilder();
            streamingChatLanguageModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    answerBuilder.append(token);
                    onToken.accept(token);
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    try {
                        String finalAnswer = appendSources(answerBuilder.toString(), retrieval.getDocNames(), retrieval.isHit());
                        if (retrieval.isHit()) {
                            redisTemplate.opsForValue().set(ANSWER_CACHE_PREFIX + searchQuestion, finalAnswer, ANSWER_CACHE_TTL);
                        }
                        saveHistoryAndLog(sessionId, question, finalAnswer, retrieval.isHit(), startTime);
                        System.out.println("[客服问答] 完成 耗时=" + (System.currentTimeMillis() - startTime) + "ms");
                    } catch (Exception e) {
                        System.out.println("[客服问答] 落库异常: " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        onComplete.run();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    System.out.println("[客服问答] 生成失败: " + error.getMessage());
                    onError.accept(error);
                }
            });
        } catch (Exception e) {
            System.out.println("[客服问答] 处理异常: " + e.getMessage());
            e.printStackTrace();
            onError.accept(e);
        }
    }

    /**
     * 寒暄秒回:命中词表返回固定回复,未命中返回 null
     */
    private String quickCasualReply(String question) {
        String trimmed = question == null ? "" : question.trim();
        if (CASUAL_WORDS.contains(trimmed.toLowerCase())) {
            return CASUAL_REPLY;
        }
        return null;
    }

    /**
     * 查询改写(带 Redis 缓存):问题带指代词且有多轮历史时,用 LLM 结合历史把问题补全为完整独立的问题。
     * 无历史、无指代词、改写失败时直接返回原问题。
     */
    private String rewriteQuestionIfNeeded(String question, String historyText, String sessionId) {
        if (historyText.isEmpty() || !PRONOUN_PATTERN.matcher(question).find()) {
            return question;
        }

        // 改写缓存:相同会话相同问题直接复用,省掉一次大模型调用
        String cacheKey = REWRITE_CACHE_PREFIX + sessionId + ":" + question;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof String cachedRewrite) {
            System.out.println("[客服问答] 改写缓存命中: " + question + " -> " + cachedRewrite);
            return cachedRewrite;
        }

        try {
            String prompt = "你是查询改写助手。请根据【历史对话】，把【用户当前问题】中省略或指代的内容补全，输出一个完整独立的问题。\n" +
                    "规则：\n" +
                    "1. 只输出改写后的问题本身，不要任何解释、标点修饰或回答\n" +
                    "2. 如果当前问题本身已经是完整问题，原样输出\n\n" +
                    "【历史对话】\n" + historyText + "\n\n" +
                    "【用户当前问题】\n" + question + "\n\n" +
                    "【改写后的问题】";
            String rewritten = chatLanguageModel.generate(prompt).trim();
            if (rewritten.isEmpty() || rewritten.length() > 100) {
                return question;
            }
            System.out.println("[客服问答] 查询改写: " + question + " -> " + rewritten);
            redisTemplate.opsForValue().set(cacheKey, rewritten, REWRITE_CACHE_TTL);
            return rewritten;
        } catch (Exception e) {
            System.out.println("[客服问答] 改写失败,使用原问题: " + e.getMessage());
            return question;
        }
    }

    /**
     * 读取答案缓存
     */
    private String loadAnswerCache(String searchQuestion) {
        Object cached = redisTemplate.opsForValue().get(ANSWER_CACHE_PREFIX + searchQuestion);
        if (cached instanceof String cachedAnswer) {
            return cachedAnswer;
        }
        return null;
    }

    /**
     * 检索(带 Redis 检索结果缓存):向量化 → Chroma 搜索 → 缓存检索结果
     * 传入的是改写后的问题,同义问法可以共享缓存
     */
    private RetrievalCache retrieve(String question) {
        String cacheKey = RETRIEVAL_CACHE_PREFIX + question;

        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof RetrievalCache retrievalCache) {
            System.out.println("[客服问答] 检索缓存命中: " + question);
            return retrievalCache;
        }
        if (cached instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, RetrievalCache.class);
        }

        Embedding questionEmbedding = embeddingModel.embed(question).content();

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(3)
                .minScore(0.3)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(searchRequest).matches();
        boolean hit = !matches.isEmpty();

        String context;
        List<String> docNames = new ArrayList<>();
        if (!hit) {
            context = "（知识库中未检索到相关内容）";
        } else {
            context = matches.stream()
                    .map(match -> match.embedded().text())
                    .collect(Collectors.joining("\n\n"));
            docNames = matches.stream()
                    .map(match -> match.embedded().metadata().getString("file_name"))
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        }

        RetrievalCache result = new RetrievalCache(context, docNames, hit);
        redisTemplate.opsForValue().set(cacheKey, result, RETRIEVAL_CACHE_TTL);
        return result;
    }

    /**
     * 构造严格提示词
     */
    private String buildPrompt(String historyText, String context, String question) {
        return "你是企业官方智能客服，必须严格遵守以下规则：\n" +
                "1. 必须结合【历史对话】理解用户的问题，遇到指代性表述（如“那它呢”“电话呢”）要先补全问题再回答\n" +
                "2. 仅能根据提供的【知识库内容】回答问题，禁止编造任何知识库中没有的信息\n" +
                "3. 如果知识库内容无法回答用户问题，必须回复：「抱歉，该问题超出我的服务范围，请您转人工咨询」\n" +
                "4. 回答必须简洁准确，不要添加无关内容\n\n" +
                "【历史对话】\n" + historyText + "\n\n" +
                "【知识库内容】\n" + context + "\n\n" +
                "【用户当前问题】\n" + question + "\n\n" +
                "【回答】";
    }

    /**
     * 拼接溯源信息
     */
    private String appendSources(String answer, List<String> docNames, boolean hit) {
        if (!hit || docNames.isEmpty()) {
            return answer;
        }
        StringBuilder source = new StringBuilder("\n\n—— 信息来源：\n");
        for (int i = 0; i < docNames.size(); i++) {
            source.append(i + 1).append(". ").append(docNames.get(i)).append("\n");
        }
        return answer + source.toString();
    }

    /**
     * 读取会话历史
     */
    private List<String> loadHistory(String sessionId) {
        @SuppressWarnings("unchecked")
        List<String> history = (List<String>) redisTemplate.opsForValue().get(CHAT_HISTORY_PREFIX + sessionId);
        return history == null ? new ArrayList<>() : history;
    }

    /**
     * 更新会话历史 + 记录对话日志(流式/非流式共用)
     */
    private void saveHistoryAndLog(String sessionId, String question, String answer,
                                   boolean isHit, long startTime) {
        String historyKey = CHAT_HISTORY_PREFIX + sessionId;
        List<String> history = loadHistory(sessionId);
        history.add("用户：" + question);
        history.add("助手：" + answer.split("\n\n——")[0]);
        if (history.size() > MAX_HISTORY_ROUNDS * 2) {
            history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY_ROUNDS * 2, history.size()));
        }
        redisTemplate.opsForValue().set(historyKey, history, HISTORY_TTL);

        int duration = (int) (System.currentTimeMillis() - startTime);
        chatLogService.saveLog(sessionId, question, answer, isHit ? 1 : 0, duration);
    }

    /**
     * 获取会话历史记录
     */
    public List<String> getHistory(String sessionId) {
        return loadHistory(sessionId);
    }

    /**
     * 清空会话历史
     */
    public void clearHistory(String sessionId) {
        redisTemplate.delete(CHAT_HISTORY_PREFIX + sessionId);
    }
}