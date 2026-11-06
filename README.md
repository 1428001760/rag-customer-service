# 企业知识库智能客服系统（RAG）

> 基于 LangChain4j 的检索增强问答系统 · 从文档入库到流式应答的完整链路

把企业内部散落的 PDF / Word / TXT / Markdown 文档，变成一个**能对话、会溯源、答不上来会转人工**的客服知识库。

不做"套壳 ChatGPT"——重点解决 RAG 工程落地时的四个真问题：**文档怎么切才不丢语义**、**检索不准时怎么不胡说**、**知识库没有的实时信息怎么补**、**大模型调用太贵怎么办**。

---

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 2.7.18、MyBatis-Plus 3.5.5 |
| RAG 框架 | LangChain4j 0.36.2 |
| 大模型 | 火山引擎方舟（Chat + Embedding） |
| 向量库 | Chroma |
| 文档解析 | Apache Tika 2.9.2 |
| 缓存 / 鉴权 | Redis、JWT (jjwt 0.11.5) |
| 实时推送 | SSE（`SseEmitter`）+ WebSocket |
| 前端 | Vue 3.5 + Vite 6 + Element Plus + ECharts + Pinia |

---

## 整体链路

```
上传文档
   ↓
Tika 解析（PDF / Word / TXT / MD 统一转纯文本）
   ↓
三层切片：标题 → 段落 → 句子（单块上限 300 字，块间重叠 40 字）
   ↓
Embedding
   ↓
写入 Chroma（按 file_name 覆盖旧向量）
        ────────────────────────────────
用户提问
   ↓
寒暄词表命中？→ 直接返回
   ↓
实时信息意图？→ 联网检索（天气 / 行情 / 新闻）→ 生成应答
   ↓
含指代词？→ 模型改写为独立问题
   ↓
Chroma 检索 Top5（相似度阈值 0.55）
   ↓
命中？→ 拼装结构化 Prompt → SSE 流式生成 + 附来源文件名
   ↓
未命中？→ 兜底模型分级判断：寒暄 / 通用常识 / 真需转人工
```

---

## 核心设计

### 1. 文档治理：切片策略决定检索上限

**为什么是三层切片而不是一次性递归切分**：

最初用 `DocumentSplitters.recursive(500, 50)`，实测发现中文客服手册里一个 500 字块常常横跨两三个小节，向量语义被稀释——问"退款多久到账"能同时召回"退换货政策"和"支付方式"两段，检索精度掉得厉害。

改成按文档天然层级逐层切：

| 层级 | 切分依据 | 作用 |
| --- | --- | --- |
| 第一层 | Markdown 标题（`#` / `##`） | 保住章节边界，一个块不跨主题 |
| 第二层 | 空行分段 | 保住段落语义完整性 |
| 第三层 | 中文句号 / 问号 / 分号 | 超长段落按句切，单块不超过 300 字 |

块间保留 40 字重叠，用来接住跨段的指代（"该政策"、"上述流程"这类词的上文）。

**同名文档增量更新**：重复上传同一份文件不能产生重复向量。入库前用 `metadataKey("file_name").isEqualTo(originalName)` 查出旧向量并 `removeAll`，再写入新向量——文档迭代时知识库不会越滚越脏。

**Tika 统一解析**：不同格式统一走 `tika.parseToString()`，屏蔽格式差异，新增格式零改动。

### 2. 检索增强：四道闸门防胡说

RAG 最大的风险是**检索没命中却硬答**。这里设了四道闸：

1. **寒暄短路**：命中寒暄词表（"你好"、"在吗"、"好咯"）直接返回固定话术，不浪费一次模型调用
2. **指代消解**：检测到"它"、"这个"等指代词，先让模型把问题改写成独立完整的问题，否则向量检索会因缺少主语而失准（改写结果长度 > 100 时判定异常并回退原问题）
3. **相似度阈值**：`maxResults(5)` + `minScore(0.55)`。阈值从 0.45 提到 0.55 是因为实测发现 0.45 时"今天天气怎么样"这种短问句也能和"服务时间"蹭过线——宁可漏召回，也不要错召回
4. **Prompt 约束**：结构化 Prompt 明确"仅依据以下片段回答，业务信息不足请说明无法回答"

### 3. 答不上来怎么办：从机械拒答到分级兜底

早期实现是"检索未命中 → 直接返回兜底话术"，结果用户说"好咯"、"今天天气怎么样"全都回一句"超出服务范围"，体验很差。

改成分级判断：

| 问题类型 | 处理方式 |
| --- | --- |
| **A 类 · 寒暄** | 词表命中立即返回，不走模型 |
| **B 类 · 通用常识** | 交给模型正常作答（写代码、算数、解释概念都可以） |
| **C 类 · 业务问题但知识库没有** | 明确回「转人工」，不编造业务事实 |

约束是**不许伪造业务数据**——价格、时效、政策这类必须来自知识库；但"帮我写个快排"、"量子纠缠是什么"这种问题没理由拒答。

配套做了两件事：

- **内部术语清洗**：模型偶尔会说"根据知识库检索结果…"，这类暴露内部机制的句子会被 `scrubInternalTerms()` 替换掉
- **来源标注只在真用到时挂**：通过业务数字匹配 + 中文双字词重合度判断答案是否真的用到了知识库内容。写诗、聊天气这类答案不会挂上"来源：企业客服手册"这种驴唇不对马嘴的标签

### 4. 联网兜底：知识库里没有的实时信息

企业知识库只会存价格、政策、库存这类私有事实，天气、股价、新闻它永远不会有。与其让模型说"我查不到"，不如真的查一下。

**意图路由**：带实时信息意图（天气/气温/新闻/股价/指数…）且**不含企业业务词**（退货/发货/会员/发票/多少钱…）的问题才走联网。业务词优先是为了防止"退货多久到账"被搜索引擎带去答别家的政策。

**数据源**（都无需 API Key）：

| 类型 | 数据源 | 说明 |
| --- | --- | --- |
| 天气 | wttr.in | 返回结构化 JSON，比搜索摘要准得多；城市名从问句里正则提取 |
| 网页搜索 | 搜狗 → 360 兜底 | 实测 Bing 在容器内被反爬（返回空壳）、DuckDuckGo 会重定向回首页 |

**三级降级**：搜狗失败转 360 → 都失败返回 `null` 走原有兜底模型 → 模型生成失败走固定文案。绝不会出现"因为联网挂了所以客服不响了"。

### 5. 三级缓存：把大模型调用成本压下来

大模型调用是 RAG 最贵的部分。分四层缓存，各设不同 TTL：

| 缓存 | TTL | 为什么是这个时长 |
| --- | --- | --- |
| 问题改写结果 | 30 min | 依赖上下文，时效性要求最高 |
| 检索结果 | 1 h | 知识库更新不频繁，向量检索可复用 |
| 最终答案 | 1 h | 相同问题跨会话复用，省掉整条链路 |
| 联网结果 | 15 min | 天气股价变化快，过期就该重查 |

所有缓存 key 带版本前缀（`CACHE_VER`）。踩过的坑：改了提示词或阈值之后旧缓存还在返回旧答案，排查半天以为代码没生效——升版本号是最省事的解法。

### 6. 流式输出与双通道推送

- **SSE**：`produces = TEXT_EVENT_STREAM_VALUE` + `SseEmitter(120_000L)`，逐字推送，首字延迟从数秒降到几百毫秒
- **WebSocket**：人工客服回复走 WebSocket 直推
- **可靠性**：直推失败自动重试 3 次，仍失败写入 Redis 补偿队列，客户端重连后补发

### 7. 工单状态机与鉴权

- 工单按「待处理 → 处理中 → 已完成」流转，**认领时校验当前状态**，防止两个客服同时抢同一工单
- **JWT + 拦截器**统一鉴权，token 有效期 2 小时
- 登录验证码与失败次数计数存 Redis，防暴力破解
- 四类角色：系统管理员 / 知识库管理员 / 客服坐席 / 普通访客，前端按角色动态路由

### 8. 前端：Vue 3 管理后台

前端用 Vue 3 + Element Plus 独立开发，`npm run build` 直接输出到 `src/main/resources/static/`，由 Spring Boot 托管为 SPA——不需要额外 Nginx，一个 jar 起服务。

页面包含：对话窗口（含流式渲染与来源标签）、数据看板（ECharts 趋势图）、工单管理、会话记录、知识库管理。

---

## 快速开始

### 1. 启动依赖

```bash
docker compose up -d
```

启动 MySQL 8.0、Redis 7、Chroma 三个服务。

### 2. 配置环境变量

所有密钥外部化管理，**不要把 api-key 写进代码或配置文件**：

```bash
export MYSQL_HOST=localhost
export MYSQL_PASSWORD=your-mysql-password
export REDIS_HOST=localhost
export REDIS_PASSWORD=your-redis-password
export VOLCENGINE_API_KEY=your-volcengine-api-key
export VOLCENGINE_CHAT_MODEL=deepseek-v4-flash-ga-260731
export VOLCENGINE_EMBEDDING_ENDPOINT=your-embedding-endpoint-id
export CHROMA_URL=http://localhost:8000
export JWT_SECRET=your-jwt-secret-at-least-32-chars
```

> `chat-model` 可以直接填模型名（如 `deepseek-v4-flash-ga-260731`），也可以填推理接入点 ID（`ep-xxxxxxxx`），代码同一套不用改。Embedding 仍走接入点。

### 3. 构建前端（可选）

```bash
cd web && npm install && npm run build
```

产物直接落到 `src/main/resources/static/`，被 Spring Boot 托管。

### 4. 启动

```bash
mvn spring-boot:run
```

访问 `http://localhost:8080`。

---

## 目录结构

```
src/main/java/com/example/ragcustomerservice/
├── config/          # 大模型、Chroma、WebSocket、鉴权拦截器
├── controller/      # 对话（SSE 流式）、文档上传、工单、管理后台
├── service/
│   ├── KnowledgeService.java   # Tika 解析 + 三层切片 + 向量入库/覆盖
│   ├── ChatService.java        # 改写 + 检索 + 缓存 + 分级兜底 + 生成
│   └── WebSearchService.java   # 意图路由 + 天气/搜索数据源 + 三级降级
├── util/            # JWT、WebSocket handler、推送、Chroma 客户端
web/                 # Vue 3 前端源码
```

---

## 踩过的坑

- **Chroma 集合"消失"**：docker-compose 挂载路径和实际数据目录不一致，容器重建后读不到旧数据。排查时不要只看 REST 返回的 500，进容器查 `list_collections()` 更可靠
- **Chroma `/query` 返回 400**：不是服务坏了，是 embedding 维度与集合创建时不一致。换 embedding 模型必须重建集合
- **Chroma v1 REST 要传集合 UUID**：不是集合名，传名字直接 404
- **SSE 连接被网关掐断**：Nginx 默认 `proxy_buffering on` 会攒够缓冲区才下发，流式效果消失。需要 `proxy_buffering off` 并调大 `proxy_read_timeout`
- **中文切片错位**：按字符切对中文合理（一个汉字一个字符），但会切断英文单词。生产环境建议按 token 切
- **改提示词后答案没变**：缓存没清。给所有缓存 key 加版本前缀，改逻辑就升版本
- **容器内出不去网**：Docker Desktop 的代理要设成 system 模式，否则容器拿不到宿主机代理

---

## License

MIT