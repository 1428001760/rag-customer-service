package com.example.ragcustomerservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索。
 *
 * RAG 的知识库只管企业私有事实（价格、政策、库存），
 * 天气、新闻、股价这类实时信息知识库里永远不会有，
 * 与其让模型说「我查不到」，不如真的去查一下。
 *
 * 设计取舍：
 * - 不接付费搜索 API（Serper/Tavily 都要 key），用 Bing 网页版解析，够用且零成本
 * - 天气单独走 wttr.in，它的 JSON 比搜索结果靠谱得多
 * - 全部失败返回 null，让上层退回原有逻辑，绝不因为搜索挂了就答不出来
 */
@Slf4j
@Service
public class WebSearchService {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT = 6000;
    private static final int READ_TIMEOUT = 9000;
    private static final int MAX_SNIPPET = 400;

    /** 天气意图 */
    private static final Pattern WEATHER_INTENT = Pattern.compile(
            "天气|气温|下雨|降雨|下雪|台风|雾霾|空气质量|冷不冷|热不热|多少度|weather"
    );

    /** 城市提取：常见后缀，避免误抓「我想」这种 */
    private static final Pattern CITY_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,6}?)(?:市|区|县)?(?:今天|明天|后天|现在|最近|这周|本周)?(?:的)?(?:天气|气温|下雨|温度)"
    );

    /** 实时信息意图：这类问题才值得联网 */
    private static final Pattern REALTIME_INTENT = Pattern.compile(
            "天气|气温|新闻|资讯|动态|股价|股票|大盘|指数|成指|创业板|汇率|油价|金价|" +
                    "比分|赛况|票房|上映|涨停|跌了多少|涨了多少"
    );

    /**
     * 企业业务词。
     *
     * 命中这些词的问题必须走知识库，不能联网——
     * 「退货多久到账」含「多久」，「会员有优惠吗」含「优惠」，
     * 这类问题是问本公司的政策，去搜网页只会得到别家的答案。
     */
    private static final Pattern BIZ_WORDS = Pattern.compile(
            "退货|退款|换货|售后|保修|维修|发票|专票|开票|优惠券|会员|积分|折扣|" +
                    "发货|物流|快递|配送|运费|包邮|签收|订单|下单|支付|分期|花呗|白条|" +
                    "客服|人工|你们|咱们|贵司|贵公司|云栖|知了|多少钱|价格|库存|现货"
    );

    /**
     * 是否值得联网查询。
     *
     * 三个条件同时满足才联网：
     * 1. 带实时信息意图（天气/新闻/股价）
     * 2. 不含企业业务词（退货/发货/会员这类必须走知识库）
     * 3. 长度合理（太长多半是复杂业务描述）
     */
    public boolean shouldSearch(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        if (question.length() > 60) {
            return false;
        }
        // 业务词优先：问的是本公司政策，绝不能拿网上别家的答案糊弄
        if (BIZ_WORDS.matcher(question).find()) {
            return false;
        }
        return REALTIME_INTENT.matcher(question).find();
    }

    /**
     * 联网查资料，返回给模型看的纯文本。
     * 查不到返回 null，上层继续走原有逻辑。
     */
    public String search(String question) {
        // 天气优先，wttr.in 的结构化数据比搜索摘要准得多
        if (WEATHER_INTENT.matcher(question).find()) {
            String weather = queryWeather(question);
            if (weather != null && !weather.isBlank()) {
                return weather;
            }
        }

        List<Map<String, String>> results = queryWeb(question);
        if (results.isEmpty()) {
            return null;
        }
        return formatResults(question, results);
    }

    /* ==================== 天气 ==================== */

    private String queryWeather(String question) {
        String city = extractCity(question);
        if (city == null) {
            city = "北京";
        }
        try {
            String url = "https://wttr.in/" + URLEncoder.encode(city, "UTF-8") + "?format=j1&lang=zh";
            String json = httpGet(url);
            if (json == null) {
                return null;
            }
            return parseWeather(city, json);
        } catch (Exception e) {
            log.warn("天气查询失败 city={} err={}", city, e.getMessage());
            return null;
        }
    }

    /** 从「杭州今天天气怎么样」里抠出「杭州」 */
    private String extractCity(String question) {
        Matcher m = CITY_PATTERN.matcher(question);
        if (m.find()) {
            String city = m.group(1);
            // 过滤掉「今天」「明天」「这里」这类不是城市名的词
            if (!city.matches("今天|明天|后天|现在|最近|这里|那里|我们|你们|那边|这边|这个|那个")) {
                return city;
            }
        }
        return null;
    }

    /**
     * 解析 wttr.in 的 JSON。
     *
     * 用最朴素的正则抠字段，不引入 Jackson 树模型——
     * 这个结构很浅，正则够用，也免得为了一个天气接口加依赖。
     */
    private String parseWeather(String city, String json) {
        String temp = pick(json, "\"temp_C\"\\s*:\\s*\"(-?\\d+)\"");
        String feels = pick(json, "\"FeelsLikeC\"\\s*:\\s*\"(-?\\d+)\"");
        String humidity = pick(json, "\"humidity\"\\s*:\\s*\"(\\d+)\"");
        String wind = pick(json, "\"windspeedKmph\"\\s*:\\s*\"(\\d+)\"");
        String desc = pick(json, "\"weatherDesc\"\\s*:\\s*\\[\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");
        String obs = pick(json, "\"observation_time\"\\s*:\\s*\"([^\"]+)\"");
        String area = pick(json, "\"areaName\"\\s*:\\s*\\[\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");

        if (temp == null) {
            return null;
        }

        // 今日高低温
        String minT = pick(json, "\"mintempC\"\\s*:\\s*\"(-?\\d+)\"");
        String maxT = pick(json, "\"maxtempC\"\\s*:\\s*\"(-?\\d+)\"");

        StringBuilder sb = new StringBuilder();
        sb.append("【实时天气】").append(city);
        if (area != null && !area.equalsIgnoreCase(city)) {
            sb.append("（").append(area).append("）");
        }
        sb.append("\n");
        sb.append("当前气温 ").append(temp).append("℃");
        if (feels != null) {
            sb.append("，体感 ").append(feels).append("℃");
        }
        if (desc != null) {
            sb.append("，").append(translateWeather(desc));
        }
        sb.append("\n");
        if (minT != null && maxT != null) {
            sb.append("今日 ").append(minT).append("~").append(maxT).append("℃");
        }
        if (humidity != null) {
            sb.append("，湿度 ").append(humidity).append("%");
        }
        if (wind != null) {
            sb.append("，风速 ").append(wind).append("km/h");
        }
        sb.append("\n");
        if (obs != null) {
            sb.append("数据观测时间：").append(obs).append("（当地）");
        }
        sb.append("\n数据来源：wttr.in");
        return sb.toString();
    }

    /** wttr 的天气描述是英文，转成中文好读一些 */
    private String translateWeather(String en) {
        String s = en.toLowerCase();
        if (s.contains("sunny") || s.contains("clear")) return "晴";
        if (s.contains("partly cloudy")) return "多云";
        if (s.contains("cloudy") || s.contains("overcast")) return "阴";
        if (s.contains("rain") || s.contains("drizzle")) return "有雨";
        if (s.contains("snow") || s.contains("sleet")) return "有雪";
        if (s.contains("thunder")) return "雷雨";
        if (s.contains("fog") || s.contains("mist")) return "有雾";
        if (s.contains("haze") || s.contains("smoke")) return "霾";
        return en;
    }

    private String pick(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /* ==================== 搜索 ==================== */

    /**
     * 网页搜索。
     *
     * 实测过一圈：Bing 在容器里被反爬（返回空壳，b_algo 为 0），
     * DuckDuckGo 会重定向回首页，Mojeek 连不上。
     * 搜狗和 360 都稳定返回完整结果，中文场景下搜狗质量更好，所以搜狗优先、360 兜底。
     */
    private List<Map<String, String>> queryWeb(String question) {
        List<Map<String, String>> out = querySogou(question);
        if (out.isEmpty()) {
            out = querySo360(question);
        }
        return out;
    }

    /** 搜狗：结果块是 div.vrwrap，标题在 h3.vr-title 里，摘要在 div.fz-mid */
    private List<Map<String, String>> querySogou(String question) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            String url = "https://www.sogou.com/web?query=" + URLEncoder.encode(question, "UTF-8");
            String html = httpGet(url);
            if (html == null) {
                return out;
            }

            Matcher blockMatcher = Pattern.compile(
                    "<div class=\"vrwrap[^\"]*\"[^>]*>(.*?)</div>\\s*<!--STATUS",
                    Pattern.DOTALL).matcher(html);

            // 上面那个精确切法在结构变化时会失效，用更宽松的兜底切法
            if (!blockMatcher.find()) {
                blockMatcher = Pattern.compile(
                        "<h3 class=\"vr-title[^\"]*\"[^>]*>(.*?)</h3>",
                        Pattern.DOTALL).matcher(html);
                while (blockMatcher.find() && out.size() < 6) {
                    Map<String, String> item = extractSogouTitle(blockMatcher.group(1));
                    if (item != null) {
                        out.add(item);
                    }
                }
                return out;
            }

            do {
                if (out.size() >= 6) {
                    break;
                }
                String block = blockMatcher.group(1);
                Matcher tm = Pattern.compile(
                        "<h3 class=\"vr-title[^\"]*\"[^>]*>(.*?)</h3>",
                        Pattern.DOTALL).matcher(block);
                if (!tm.find()) {
                    continue;
                }
                Map<String, String> item = extractSogouTitle(tm.group(1));
                if (item == null) {
                    continue;
                }
                Matcher sm = Pattern.compile(
                        "<div class=\"[^\"]*(?:fz-mid|space-txt|str-text-info)[^\"]*\"[^>]*>(.*?)</div>",
                        Pattern.DOTALL).matcher(block);
                if (sm.find()) {
                    String snip = stripTags(sm.group(1));
                    if (!snip.isEmpty()) {
                        item.put("snippet", snip.length() > MAX_SNIPPET
                                ? snip.substring(0, MAX_SNIPPET) + "…" : snip);
                    }
                }
                out.add(item);
            } while (blockMatcher.find());

        } catch (Exception e) {
            log.warn("搜狗搜索失败 q={} err={}", question, e.getMessage());
        }
        return out;
    }

    private Map<String, String> extractSogouTitle(String h3Inner) {
        Matcher am = Pattern.compile("<a[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(h3Inner);
        if (!am.find()) {
            return null;
        }
        String title = stripTags(am.group(1));
        if (title.isEmpty()) {
            return null;
        }
        Map<String, String> item = new LinkedHashMap<>();
        item.put("title", title);
        item.put("snippet", "");
        return item;
    }

    /** 360：结果块是 li.res-list */
    private List<Map<String, String>> querySo360(String question) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            String url = "https://www.so.com/s?q=" + URLEncoder.encode(question, "UTF-8");
            String html = httpGet(url);
            if (html == null) {
                return out;
            }
            Matcher bm = Pattern.compile(
                    "<li class=\"res-list[^\"]*\"[^>]*>(.*?)</li>", Pattern.DOTALL).matcher(html);
            while (bm.find() && out.size() < 6) {
                String block = bm.group(1);
                Matcher tm = Pattern.compile("<h3[^>]*>(.*?)</h3>", Pattern.DOTALL).matcher(block);
                if (!tm.find()) {
                    continue;
                }
                String title = stripTags(tm.group(1));
                if (title.isEmpty()) {
                    continue;
                }
                Map<String, String> item = new LinkedHashMap<>();
                item.put("title", title);
                String snip = "";
                Matcher sm = Pattern.compile(
                        "<p class=\"res-desc\"[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(block);
                if (sm.find()) {
                    snip = stripTags(sm.group(1));
                }
                item.put("snippet", snip.length() > MAX_SNIPPET
                        ? snip.substring(0, MAX_SNIPPET) + "…" : snip);
                out.add(item);
            }
        } catch (Exception e) {
            log.warn("360 搜索失败 q={} err={}", question, e.getMessage());
        }
        return out;
    }

    private String formatResults(String question, List<Map<String, String>> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("【联网搜索结果】关键词：").append(question).append("\n");
        for (int i = 0; i < results.size(); i++) {
            Map<String, String> r = results.get(i);
            sb.append(i + 1).append(". ").append(r.get("title")).append("\n");
            if (!r.get("snippet").isEmpty()) {
                sb.append("   ").append(r.get("snippet")).append("\n");
            }
        }
        sb.append("数据来源：搜狗搜索");
        return sb.toString();
    }

    /* ==================== HTTP ==================== */

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("Accept", "text/html,application/json,*/*");
            conn.setInstanceFollowRedirects(true);

            // 部分站点证书链在容器里校验不过，这里放宽
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                javax.net.ssl.HttpsURLConnection https = (javax.net.ssl.HttpsURLConnection) conn;
                https.setSSLSocketFactory(trustAllContext().getSocketFactory());
                https.setHostnameVerifier((h, s) -> true);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                log.warn("HTTP {} for {}", code, urlStr);
                return null;
            }

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("HTTP 请求失败 {} : {}", urlStr, e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private SSLContext trustAllContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String stripTags(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("<[^>]+>", "")
                .replace("&ensp;", " ")
                .replace("&nbsp;", " ")
                .replace("&#0183;", "·")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
