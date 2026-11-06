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
import java.util.regex.Matcher;
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
    private final WebSearchService webSearchService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CHAT_HISTORY_PREFIX = "chat:history:";
    /**
     * 缓存键版本号。
     *
     * 改了提示词、检索阈值、分块策略之后，旧缓存里的答案就过时了，
     * 但键名一样会继续命中。改版本号即可整体失效，不用手动清 Redis。
     */
    private static final String CACHE_VER = "v14:";
    private static final String RETRIEVAL_CACHE_PREFIX = "retrieval_cache:" + CACHE_VER;
    private static final String REWRITE_CACHE_PREFIX = "rewrite_cache:" + CACHE_VER;
    private static final String ANSWER_CACHE_PREFIX = "answer_cache:" + CACHE_VER;
    private static final String FALLBACK_CACHE_PREFIX = "fallback_cache:" + CACHE_VER;
    /** 联网结果缓存：天气这类信息变化快，TTL 给短一点 */
    private static final String WEB_CACHE_PREFIX = "web_cache:" + CACHE_VER;
    private static final int MAX_HISTORY_ROUNDS = 3;
    private static final Duration RETRIEVAL_CACHE_TTL = Duration.ofHours(1);
    private static final Duration REWRITE_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration ANSWER_CACHE_TTL = Duration.ofHours(1);
    private static final Duration FALLBACK_CACHE_TTL = Duration.ofHours(2);
    /** 联网结果：天气/股价变化快，15 分钟就重查 */
    private static final Duration WEB_CACHE_TTL = Duration.ofMinutes(15);
    private static final Duration HISTORY_TTL = Duration.ofMinutes(30);

    /**
     * 寒暄/确认类基础词。
     *
     * 不能再用 Set 精确匹配——用户说的是「好咯」「好嘞」「行吧」「知道了」「谢谢啦」
     * 这类带语气词的变体，精确匹配会把它们全漏掉，然后走到「检索未命中」被直接拒答。
     * 这里先剥掉尾部语气词，再比对基础词。
     */
    private static final Set<String> GREETING_WORDS = Set.of(
            "你好", "您好", "hi", "hello", "哈喽", "在", "在吗", "在不在", "有人吗"
    );

    /** 应答确认：用户对上一轮回答表示收到，不是在提问 */
    private static final Set<String> ACK_WORDS = Set.of(
            "好", "行", "嗯", "哦", "噢", "可以", "成", "中",
            "ok", "okay", "收到", "知道", "明白", "懂", "了解", "清楚"
    );

    private static final Set<String> THANKS_WORDS = Set.of(
            "谢谢", "感谢", "多谢", "辛苦", "麻烦你"
    );

    private static final Set<String> BYE_WORDS = Set.of(
            "拜", "再见", "晚安", "早安", "早"
    );

    /** 尾部语气词与标点，逐层剥离后再比对基础词 */
    private static final Pattern TAIL_NOISE =
            Pattern.compile("[啊呀吧咯啦嘞哦噢哈嘛呢了的么呗哇哟喔!！。.~～?？,，、\\s]+$");

    private static final String GREETING_REPLY =
            "您好，我是知了客服。产品、订单、售后这些都可以问我。";

    private static final String ACK_REPLY =
            "好的，还有其他问题随时找我～";

    private static final String THANKS_REPLY =
            "不客气，应该的～";

    private static final String BYE_REPLY =
            "再见，祝您购物愉快～";

    /**
     * 检索未命中的固定拒答文案：仅在兜底模型也调用失败时使用
     */
    private static final String NO_ANSWER_REPLY = "抱歉，该问题超出我的服务范围，请您转人工咨询";

    /**
     * 检索未命中时的兜底提示词。
     *
     * 原来的做法是不管三七二十一直接回「超出服务范围」，
     * 导致「好咯」这种收尾寒暄也被拒，非常蠢。
     * 这里把判断权交回模型：它自己清楚「好咯」该怎么接、天气该怎么说、
     * 什么才是真正需要转人工的业务问题。
     */
    private static final String FALLBACK_PROMPT =
            "你是「云栖优选」的官方智能客服，名字叫知了。\n" +
            "知识库中【没有】检索到与用户这句话直接相关的内容。请自行判断类型，直接写出回复正文" +
            "（不要写类别名，不要解释，不要加前缀）：\n\n" +
            "A 类｜寒暄、应答、确认、感谢、道别、闲聊（「好咯」「行吧」「知道了」「拜拜」）\n" +
            "     做法：自然简短回应，可顺带邀请继续提问。不要说「超出范围」。\n\n" +
            "B 类｜通用常识问题（天气、写诗、写代码、算术、科普、电影推荐、翻译、总结等）\n" +
            "     做法：正常、认真地回答。你完全有这个能力，不要推脱。\n" +
            "     答完可以补一句「另外，产品、订单、售后方面的问题我也随时可以帮您」。\n" +
            "     只有确实需要实时数据（如当下天气、股价、新闻）才说明拿不到实时数据并给建议。\n\n" +
            "C 类｜本公司业务问题（价格、库存、订单、退款、发票、保修等），但知识库没收录\n" +
            "     做法：说明这个需要转人工确认，回复里必须出现「转人工」三个字。\n\n" +
            "【硬性要求】\n" +
            "1. C 类绝对不许编造价格、政策、时效、电话、优惠、库存等任何具体业务信息\n" +
            "2. 绝对不许提到「知识库」「检索」「上下文」「资料库」这类内部机制，" +
            "也别说「查不到」「没有收录」——就说「这个我需要帮您转人工确认」\n" +
            "3. 口语化，像真人客服说话；B 类可以详细一些，A 类简短即可\n" +
            "4. 直接输出回复正文\n\n" +
            "【最近对话】\n{history}\n\n" +
            "【用户这句话】\n{question}\n\n" +
            "回复：";

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
        /** 命中的章节标题，前端用来显示溯源 */
        private List<String> headings;
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

        // 实时信息类问题（天气/新闻/股价）优先联网。
        // 不能等「检索未命中」才联网——「北京今天天气怎么样」因为含「今天」
        // 会在向量库里蹭到「服务时间」那一块，检索判为命中，结果永远走不到联网分支。
        if (webSearchService.shouldSearch(question)) {
            String web = tryWebAnswer(question, historyText, sessionId, startTime);
            if (web != null) {
                return web;
            }
        }

        String searchQuestion = rewriteQuestionIfNeeded(question, historyText, sessionId);
        RetrievalCache retrieval = retrieve(searchQuestion);
        System.out.println("[客服问答] 检索命中=" + retrieval.isHit() + " 来源=" + retrieval.getDocNames());

        // 检索未命中：交给兜底模型判断是寒暄、无关问题还是真需要转人工
        if (!retrieval.isHit()) {
            System.out.println("[客服问答] 检索未命中，走兜底模型");
            String fallback = fallbackAnswer(question, historyText);
            saveHistoryAndLog(sessionId, question, fallback, false, startTime);
            return fallback;
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
        answer = scrubInternalTerms(answer);
        finalAnswer = appendSources(answer, retrieval.getDocNames(), retrieval.getContext());

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

            // 实时信息类问题优先联网，理由同非流式路径
            if (webSearchService.shouldSearch(question)) {
                String web = tryWebAnswer(question, historyText, sessionId, startTime);
                if (web != null) {
                    onToken.accept(web);
                    onComplete.run();
                    return;
                }
            }

            String searchQuestion = rewriteQuestionIfNeeded(question, historyText, sessionId);
            RetrievalCache retrieval = retrieve(searchQuestion);
            System.out.println("[客服问答] 检索命中=" + retrieval.isHit() + " 来源=" + retrieval.getDocNames());

            // 检索未命中：交给兜底模型判断
            if (!retrieval.isHit()) {
                System.out.println("[客服问答] 检索未命中，走兜底模型");
                String fallback = fallbackAnswer(question, historyText);
                saveHistoryAndLog(sessionId, question, fallback, false, startTime);
                onToken.accept(fallback);
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
                        String cleaned = scrubInternalTerms(answerBuilder.toString());
                        String finalAnswer = appendSources(cleaned, retrieval.getDocNames(), retrieval.getContext());
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
     * 寒暄/应答/感谢/道别秒回。
     *
     * 只处理最基础的几句，命中就返回固定文案；其余全部交给兜底模型判断，
     * 不再用词表去卡「好咯」「行吧」这种带语气词的变体。
     */
    private String quickCasualReply(String question) {
        String raw = question == null ? "" : question.trim();
        if (raw.isEmpty()) {
            return null;
        }

        // 剥掉尾部语气词和标点："好咯～" -> "好"，"谢谢啦！" -> "谢谢"
        String base = TAIL_NOISE.matcher(raw).replaceAll("").toLowerCase();
        if (base.isEmpty()) {
            return null;
        }

        if (GREETING_WORDS.contains(base)) {
            return GREETING_REPLY;
        }
        if (ACK_WORDS.contains(base)) {
            return ACK_REPLY;
        }
        if (THANKS_WORDS.contains(base)) {
            return THANKS_REPLY;
        }
        if (BYE_WORDS.contains(base)) {
            return BYE_REPLY;
        }
        return null;
    }

    /**
     * 尝试联网回答实时类问题。
     *
     * 触发条件：知识库没检索到 + 问题带实时意图（天气/新闻/股价等）。
     * 搜索失败返回 null，调用方继续走兜底模型，不会因为联网挂了就答不出来。
     */
    private String tryWebAnswer(String question, String historyText, String sessionId, long startTime) {
        if (!webSearchService.shouldSearch(question)) {
            return null;
        }

        String cacheKey = WEB_CACHE_PREFIX + question;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof String cachedAnswer && !cachedAnswer.isEmpty()) {
            System.out.println("[客服问答] 联网结果缓存命中: " + question);
            saveHistoryAndLog(sessionId, question, cachedAnswer, false, startTime);
            return cachedAnswer;
        }

        String material = webSearchService.search(question);
        if (material == null || material.isBlank()) {
            System.out.println("[客服问答] 联网无结果: " + question);
            return null;
        }
        System.out.println("[客服问答] 联网取到资料 " + material.length() + " 字");

        try {
            String prompt = "你是「云栖优选」的官方智能客服，名字叫知了。\n" +
                    "用户问的是需要实时信息的问题，下面是从网上查到的最新资料。\n" +
                    "请基于这些资料，用自然口语回答问题。\n\n" +
                    "【硬性要求】\n" +
                    "1. 只使用资料里的信息，不许编造数据\n" +
                    "2. 如果资料里没有用户问的具体内容，直接说明暂时查不到，不要说「超出服务范围」\n" +
                    "3. 不要提到「联网」「搜索」「资料」这类字眼，就像你自己知道一样自然说出来\n" +
                    "4. 简洁准确，60 字以内，可补充一句「产品售后问题也可以问我」\n" +
                    "5. 直接输出回答正文\n\n" +
                    "【网上资料】\n" + material + "\n\n" +
                    "【最近对话】\n" + (historyText.isEmpty() ? "（无）" : historyText) + "\n\n" +
                    "【用户问题】\n" + question + "\n\n" +
                    "回答：";

            String answer = chatLanguageModel.generate(prompt);
            if (answer == null || answer.isBlank()) {
                return null;
            }
            answer = answer.trim()
                    .replaceFirst("^(回复|回答)[:：]\\s*", "");
            answer = scrubInternalTerms(answer);

            redisTemplate.opsForValue().set(cacheKey, answer, WEB_CACHE_TTL);
            saveHistoryAndLog(sessionId, question, answer, false, startTime);
            return answer;
        } catch (Exception e) {
            System.out.println("[客服问答] 联网回答生成失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 检索未命中时的兜底：把判断权交给大模型。
     *
     * 关键点：不再无脑拒答。模型自己知道「好咯」该接什么、
     * 「今天天气」该礼貌回绝、什么才是真需要转人工的业务问题。
     * 模型调用失败才退回固定文案。
     */
    private String fallbackAnswer(String question, String historyText) {
        String cacheKey = FALLBACK_CACHE_PREFIX + question;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof String cachedAnswer && !cachedAnswer.isEmpty()) {
            System.out.println("[客服问答] 兜底缓存命中: " + question);
            return cachedAnswer;
        }

        try {
            String prompt = FALLBACK_PROMPT
                    .replace("{history}", historyText.isEmpty() ? "（无）" : historyText)
                    .replace("{question}", question);
            String answer = chatLanguageModel.generate(prompt);
            if (answer == null || answer.isBlank()) {
                return NO_ANSWER_REPLY;
            }
            answer = answer.trim();
            // 模型偶尔会加前缀，剥掉
            answer = answer.replaceFirst("^(回复|回答)[:：]\\s*", "");
            answer = scrubInternalTerms(answer);
            redisTemplate.opsForValue().set(cacheKey, answer, FALLBACK_CACHE_TTL);
            return answer;
        } catch (Exception e) {
            System.out.println("[客服问答] 兜底生成失败，使用固定文案: " + e.getMessage());
            return NO_ANSWER_REPLY;
        }
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

        // 分块后每个块只讲一件事，相似度分布更集中。
        // minScore 0.45 时「今天天气怎么样」这种短问句也能蹭过线（和「服务时间」在向量空间里挨着），
        // 结果模型拿到一堆不相关上下文，只能按规则拒答。提到 0.55 让无关问题落到兜底分支，
        // 由兜底模型自己判断该寒暄还是该回绝，而不是统一回「超出范围」。
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(5)
                .minScore(0.55)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(searchRequest).matches();
        boolean hit = !matches.isEmpty();

        String context;
        List<String> docNames = new ArrayList<>();
        List<String> headings = new ArrayList<>();
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
            // 命中章节：前端显示「命中：会员权益」比只显示文件名有用得多
            headings = matches.stream()
                    .map(match -> match.embedded().metadata().getString("heading"))
                    .filter(h -> h != null && !h.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());
        }

        RetrievalCache result = new RetrievalCache(context, docNames, hit, headings);
        redisTemplate.opsForValue().set(cacheKey, result, RETRIEVAL_CACHE_TTL);
        return result;
    }

    /**
     * 构造提示词。
     *
     * 比原来放宽了两点：
     * 1. 允许模型把知识库里的信息做归纳、对比、换算，不必逐字照抄
     * 2. 只有「知识库确实没有相关业务信息」才拒答，而不是「答案不是原文」就拒答
     */
    private String buildPrompt(String historyText, String context, String question) {
        return "你是「云栖优选」的官方智能客服，名字叫知了。请遵守以下规则：\n" +
                "1. 结合【历史对话】理解用户问题，遇到「那它呢」「电话呢」这类省略或指代，先补全再回答\n" +
                "2. 业务信息（价格、政策、时效、电话、库存、优惠）只能取自【知识库内容】，禁止编造\n" +
                "3. 允许对知识库内容做归纳、对比、换算和举例说明，不必逐字照抄\n" +
                "4. 如果用户问的是通用常识（天气、写诗、写代码、算术、科普、翻译等），正常认真回答，不要推脱；\n" +
                "   答完可补一句产品售后也能问。只有确实需要实时数据才说明拿不到实时数据\n" +
                "5. 只有当用户问的确实是本公司业务、但【知识库内容】里查不到对应信息时，\n" +
                "   才回复：「抱歉，该问题超出我的服务范围，请您转人工咨询」\n" +
                "6. 如果用户只是在寒暄、道谢、应答（例如「好咯」「谢谢」「知道了」），自然回应即可，不要拒答\n" +
                "7. 回答简洁准确，口语化\n\n" +
                "【历史对话】\n" + (historyText.isEmpty() ? "（无）" : historyText) + "\n\n" +
                "【知识库内容】\n" + context + "\n\n" +
                "【用户当前问题】\n" + question + "\n\n" +
                "【回答】";
    }

    /**
     * 拼接溯源信息。
     *
     * 挂来源的条件从「检索命中」改成「答案真的用到了知识库里的业务信息」。
     * 因为检索阈值对「今天天气怎么样」「帮我写首诗」这类问题也会误命中，
     * 结果一首诗下面挂着「来源：企业客服常见问题手册.txt」，明显不对。
     *
     * 判断办法：从知识库原文里抽出业务数字短语（48小时、7天、9折、400-888-8888 等），
     * 看答案里是否真的出现了。业务答案必然带这些，写诗、聊天气不会。
     */
    private String appendSources(String answer, List<String> docNames, String context) {
        if (answer == null || docNames == null || docNames.isEmpty() || context == null) {
            return answer;
        }
        if (isDeclined(answer) || !usesKnowledge(answer, context)) {
            return answer;
        }
        StringBuilder source = new StringBuilder("\n\n—— 信息来源：\n");
        for (int i = 0; i < docNames.size(); i++) {
            source.append(i + 1).append(". ").append(docNames.get(i)).append("\n");
        }
        return answer + source.toString();
    }

    /**
     * 答案里是否真的用到了知识库内容。
     *
     * 之前只看业务数字（48小时、9折），漏了「钻石会员免运费」这种纯定性回答——
     * 它不含任何数字，但确实是知识库里的业务事实。
     * 所以改成两个条件满足其一即可：
     *   1. 答案含知识库原文的业务数字短语（精度高，业务答案必然带）
     *   2. 答案与知识库原文有足够多的中文双字词重合（覆盖定性回答）
     */
    private boolean usesKnowledge(String answer, String context) {
        // 先剥掉 Markdown 记号再比：模型会把电话写成 **400-888-8888**，
        // 带星号就和知识库原文的 400-888-8888 对不上了
        String flatAnswer = stripMarkdown(answer);

        // 条件 1：业务数字短语
        for (String token : extractBusinessTokens(context)) {
            if (flatAnswer.contains(stripMarkdown(token))) {
                return true;
            }
        }

        // 条件 2：中文双字词重合度
        // 把知识库原文和答案都切成相邻两字，看答案里有多少落在原文中。
        // 写诗、聊天气、写代码这类内容，词汇和售后手册几乎不重叠。
        Set<String> ctxBigrams = bigrams(context);
        Set<String> ansBigrams = bigrams(flatAnswer);
        if (ansBigrams.isEmpty()) {
            return false;
        }
        long overlap = ansBigrams.stream().filter(ctxBigrams::contains).count();
        double ratio = (double) overlap / ansBigrams.size();

        // 阈值 0.22：实测业务答案普遍 0.3 以上，通用问答基本在 0.1 以下
        return ratio >= 0.22;
    }

    /** 去掉 Markdown 记号与所有空白，只留正文。
     *
     * 注意别把连字符和括号也剥掉——400-888-8888 会变成 4008888888，
     * 和知识库原文就对不上了。
     */
    private String stripMarkdown(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[*`_#>~\\[\\]]", "")
                .replaceAll("\\s+", "");
    }

    /** 抽取中文相邻双字组合，用于粗略衡量文本相似度 */
    private Set<String> bigrams(String text) {
        Set<String> set = new java.util.HashSet<>();
        if (text == null) {
            return set;
        }
        // 只保留中文，避免数字、标点干扰
        String cn = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i + 1 < cn.length(); i++) {
            set.add(cn.substring(i, i + 2));
        }
        return set;
    }

    /**
     * 从知识库原文里抽业务数字短语。
     *
     * 只抽「数字 + 单位」「电话」「邮箱」这几类，
     * 因为这类信息是业务答案绕不开的，而且通用问答不会碰巧出现。
     */
    private static final Pattern BIZ_TOKEN_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:个)?(?:工作日|天|小时|分钟|元|折|%|期|次|年|个月|积分)" +
                    "|400-\\d{3}-\\d{4}" +
                    "|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );

    private List<String> extractBusinessTokens(String context) {
        List<String> tokens = new ArrayList<>();
        Matcher m = BIZ_TOKEN_PATTERN.matcher(context.replaceAll("\\s+", ""));
        while (m.find()) {
            String t = m.group();
            // 太短的（如「1元」）区分度不够，容易误判
            if (t.length() >= 3) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /**
     * 答案是否属于「我答不了，需要转人工」。
     *
     * 不能用 contains("转人工") 判断——正常答案里也会提到转人工这个业务动作，
     * 例如「App 内点我的-联系客服可以实时转人工」，
     * 那是答案内容，不是拒答。只看固定的拒答措辞。
     */
    private boolean isDeclined(String answer) {
        if (answer == null) {
            return false;
        }
        return answer.contains("超出我的服务范围")
                || answer.contains("超出我的能力范围")
                || answer.contains("帮您转人工确认")
                || answer.contains("需要转人工确认");
    }

    /**
     * 洗掉泄漏内部机制的措辞。
     *
     * 提示词里已经要求模型别提「知识库」，但它偶尔还是会说
     * 「在现有知识库里查不到」。用户看到这种话会觉得系统很粗糙，
     * 所以代码层再兜一道，把这类表述换成正常的客服说法。
     */
    private String scrubInternalTerms(String answer) {
        if (answer == null || answer.isEmpty()) {
            return answer;
        }
        return answer
                .replaceAll("[^。！？\\n]*?(知识库|检索结果|向量库|上下文|资料库)[^。！？\\n]*?(查不到|没有|未收录|未找到)[^。！？\\n]*?[。！？]", "这个我需要帮您转人工确认一下。")
                .replaceAll("在?现有?知识库[中里]?[^。！？\\n]*?[，,]", "")
                .replaceAll("[^。！？\\n]*?(知识库|检索结果|向量库|上下文|资料库)[^。！？\\n]*?[。！？]", "")
                .trim();
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