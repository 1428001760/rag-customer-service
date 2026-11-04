# 企业知识库智能客服系统（RAG）

> 基于 LangChain4j 的检索增强问答系统 · 从文档入库到流式应答的完整链路

把企业内部散落的 PDF / Word / TXT / Markdown 文档，变成一个**能对话、会溯源、答不上来会转人工**的客服知识库。

不做"套壳 ChatGPT"——重点解决 RAG 工程落地时的三个真问题：**文档怎么切才不丢语义**、**检索不准时怎么不胡说**、**大模型调用太贵怎么办**。

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

---

## 整体链路

```
上传文档
   ↓ Tika 解析
   ↓ DocumentSplitters.recursive(500, 50) 递归切片
   ↓ Embedding
   ↓ 写入 Chroma（按 file_name 覆盖旧向量）
        ────────────────────────────────
用户提问
   ↓ 寒暄词表命中？→ 直接返回
   ↓ 含指代词？→ 模型改写为独立问题
   ↓ Chroma 检索 Top3（相似度阈值 0.3）
   ↓ 命中？→ 未命中走兜底话术，不调模型
   ↓ 拼装结构化 Prompt（对话历史 + 命中片段 + 禁止编造）
   ↓ SSE 流式生成 + 附来源文件名
   ↓ 答不上来 → 转人工工单
```

---

## 核心设计

### 1. 文档治理：切片策略决定检索上限

**切片为什么是 500 / 50**：切片太大，一个 chunk 里混多个主题，向量语义被稀释，检索不准；切片太小，一句话被拦腰截断，模型拿到残缺上下文。500 字是中文技术文档的常见语义段落长度，50 字重叠用来保住跨段落的指代关系（"该方案"、"上述流程"这类词的上文）。

**同名文档增量更新**：重复上传同一份文件不能产生重复向量。做法是入库前用 `metadataKey("file_name").isEqualTo(originalName)` 查出旧向量并 `removeAll`，再写入新向量。这样文档迭代时知识库不会越滚越脏。

**Tika 统一解析**：不同格式（PDF / Word / TXT / MD）统一走 `tika.parseToString()`，屏蔽格式差异，新增格式零改动。

### 2. 检索增强：四道闸门防胡说

RAG 最大的风险是**检索没命中却硬答**。这里设了四道闸：

1. **寒暄短路**：命中寒暄词表（"你好"、"在吗"）直接返回固定话术，不浪费一次模型调用
2. **指代消解**：检测到"它"、"这个"等指代词，先让模型把问题改写成独立完整的问题，否则向量检索会因缺少主语而失准（改写结果长度 > 100 时判定为异常并回退原问题）
3. **相似度阈值**：`maxResults(3)` + `minScore(0.3)`，低于阈值视为未命中，直接返回兜底话术
4. **Prompt 约束**：结构化 Prompt 中明确"仅依据以下片段回答，信息不足请说明无法回答"，从指令层面抑制编造

### 3. 三级缓存：把大模型调用成本压下来

大模型调用是 RAG 系统最贵的部分。分三层缓存，各设不同 TTL：

| 缓存 | TTL | 为什么是这个时长 |
| --- | --- | --- |
| 问题改写结果 | 30 min | 改写结果依赖上下文，时效性要求最高 |
| 检索结果 | 1 h | 知识库更新不频繁，向量检索可复用 |
| 最终答案 | 1 h | 相同问题跨会话复用，省掉整条链路 |

**效果**：高频重复问题（客服场景占比很高）直接命中答案缓存，不触发 embedding + 检索 + 生成，成本与延迟同时下降。

### 4. 流式输出与双通道推送

- **SSE**：`@GetMapping(produces = TEXT_EVENT_STREAM_VALUE)` + `SseEmitter(120_000L)`，逐字推送生成内容，首字延迟从数秒降到几百毫秒
- **WebSocket**：人工客服回复走 WebSocket 直推
- **可靠性**：直推失败自动重试 3 次，仍失败则写入 Redis 补偿队列，客户端重连后补发。消息不会因为一次网络抖动就丢

### 5. 工单状态机与鉴权

- 工单按「待处理 → 处理中 → 已完成」流转，**认领时校验当前状态**，防止两个客服同时抢同一工单（乐观锁思路）
- **JWT + 拦截器**统一鉴权，token 有效期 2 小时
- 登录验证码与失败次数计数存 Redis，防暴力破解
- 游客会话在登录时自动合并到用户会话，避免切换身份丢上下文

---

## 快速开始

### 1. 启动依赖

```bash
docker compose up -d
```

启动 MySQL 8.0、Redis 7、Chroma 三个服务。

### 2. 配置环境变量

所有密钥已外部化，**不要把 api-key 写进代码或配置文件**：

```bash
export MYSQL_PASSWORD=your-mysql-password
export REDIS_PASSWORD=your-redis-password
export VOLCENGINE_API_KEY=your-volcengine-api-key
export VOLCENGINE_CHAT_ENDPOINT=your-chat-endpoint-id
export VOLCENGINE_EMBEDDING_ENDPOINT=your-embedding-endpoint-id
export CHROMA_URL=http://localhost:8000
export JWT_SECRET=your-jwt-secret-at-least-32-chars
```

> 火山引擎的 `endpoint-id` 需要在[方舟控制台](https://console.volcengine.com/ark)创建推理接入点后获取，Chat 和 Embedding 是两个独立的 endpoint。

### 3. 启动

```bash
mvn spring-boot:run
```

访问 `http://localhost:8080`。

---

## 目录结构

```
src/main/java/com/rag/
├── config/          # LangChain4j、Chroma、WebSocket、拦截器配置
├── controller/      # 对话（含 SSE 流式）、文档上传、工单、登录
├── service/
│   ├── KnowledgeService.java   # Tika 解析 + 切片 + 向量入库/覆盖
│   └── ChatService.java        # 改写 + 检索 + 缓存 + 生成
├── util/            # JWT 工具
└── resources/
    └── application.yml
```

---

## 踩过的坑

- **Chroma 集合"消失"**：docker-compose 挂载路径和实际数据目录不一致，容器重建后读不到旧数据。排查时不要只看 REST 接口返回的 500，直接进容器用 `chromadb.HttpClient` 查 `list_collections()` 更可靠。
- **Chroma `/query` 返回 400**：不是服务坏了，而是 embedding 维度与集合创建时不一致。换 embedding 模型必须重建集合。
- **SSE 连接被网关掐断**：Nginx 默认 `proxy_buffering on` 会攒够缓冲区才下发，流式效果消失。需要 `proxy_buffering off` 并调大 `proxy_read_timeout`。
- **中文切片错位**：按字符数切片对中文是合理的（一个汉字一个字符），但对英文单词会切断。生产环境建议按 token 切。

---

## License

MIT
