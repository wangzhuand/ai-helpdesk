# 技术决策日志

> 每次做技术选择记一条：选了什么、放弃了什么、为什么。面试前通读一遍，这就是"坑与取舍"题库。
> 格式：日期 · 主题 / 背景 / 决策 / 放弃的方案 / 后续验证

## 2026-09-03 · 消息历史接口用游标分页而不是 offset 分页

- **背景**：聊天消息表是系统里增长最快的大表，历史消息查询 `LIMIT offset, size` 在深翻页时全表扫描，性能崩塌。
- **决策**：游标分页——`WHERE id < #{lastId} ORDER BY id DESC LIMIT 20`，配合联合索引 `(conversation_id, id)`。
- **放弃的方案**：offset 分页（实现简单但深分页慢）；历史消息存 ES（引入双写一致性复杂度，不值得）。
- **后续验证**：第 11 周压测对比两种分页在 100 万行消息下的耗时差。

## 2026-09-03 · 检索层选 Elasticsearch 而不是独立向量库

- **背景**：知识库 RAG 需要"关键词 + 语义"混合检索。
- **决策**：ES 8 的 BM25 + dense_vector 一套搞定，且是 Java 后端面试高频组件。
- **放弃的方案**：Milvus（纯向量，仍需另配关键词检索，多一个组件多一分运维成本）。
- **后续验证**：第 5 周看混合检索 recall@3 是否达标，不行再补 rerank API。

## 2026-09-03 · 消息队列选 RocketMQ 而不是 RabbitMQ/Kafka

- **背景**：项目有四个 MQ 场景（异步文档处理、事件通知、SLA 延迟提醒、失败重试死信）。
- **决策**：RocketMQ 5——延迟消息是一等公民（场景 3 刚需），Java 技术栈主流，面试匹配度高。
- **放弃的方案**：RabbitMQ（延迟要靠插件）；Kafka（为吞吐而生，业务消息场景过重）。

## 2026-09-03 · 前 6 周单体，第 10 周带理由拆微服务

- **背景**：微服务是简历关键词，但一上来就拆会让 MVP 交付速度减半。
- **决策**：先写模块清晰的单体；第 10 周拆出 ai-service 与 notification-service，理由是 LLM 延迟高且不稳定，必须与核心工单流程故障隔离（AI 挂 → 降级为直接转人工排队，核心不瘫）。
- **放弃的方案**：一步到位微服务（MVP 期间运维成本吞掉开发速度）；永远单体（丢失服务拆分/注册发现/熔断降级的面试素材）。

## 2026-09-15 · 检索层手写 ElasticsearchClient kNN + Java 手工 RRF，放弃 Spring AI VectorStore

- **背景**：W5 RAG 需要"BM25 关键词 + 向量语义"两路召回，再把两路结果融合成一个排序。
- **决策**：直接用 Spring Boot 自动配置的 `ElasticsearchClient` 手写 `knn` 查询；BM25 与向量两路的融合在 Java 里手工实现 RRF（`1/(k+rank)` 倒数加权），不依赖 ES 原生 `retriever.rrf`。
- **放弃的方案**：
  - **Spring AI `VectorStore`**：它只抽象了向量那一路，给不了 BM25，混合检索仍需手写；且它固定 `content / metadata / embedding` 三字段的 schema，与本项目已建好的 `kb_chunk`（`content` 用 IK 分词、`doc_title` 是独立字段）冲突——用它等于白瞎 IK 中文分词。手写才能把 `numCandidates`、`k`、"不同量纲的分数不能直接加权、RRF 只用排名"这些点讲清楚。
  - **ES 8.8+ 原生 `retriever.rrf`**：服务器是 8.13 其实支持，但融合过程被黑盒吃掉，面试时讲不出原理，价值低。
- **后续验证**：W5 用同一个 query 跑 keyword / vector / hybrid 三路对比；W12 在评测集上比 recall@3。

## 2026-09-15 · 引用来源走 SSE `references` 事件下发，不让模型写正文

- **背景**：RAG 回答需要让用户看到"这条答案参考了哪篇文档的哪一块"。
- **决策**：后端在 `done` 事件**之前**单独下发一个 `references` 事件，payload 结构 `[{documentId, docTitle, chunkIndex, score, snippet}]`；AI 正文里不出现任何引用标记。
- **放弃的方案**：让模型在回答末尾自己写「参考自《xxx》」——模型可能漏写、也可能编造一个不存在的文档名，且无法量化校验。
- **后续验证**：后端命中的 chunk 是确定的、可断言的，W12 可以直接量化"引用正确率"，而不是靠人肉看回答。

## 2026-09-18 · 知识库上传不加 `@Transactional`，一致性问题整体留给 W7/W11

- **背景**：`KnowledgeBaseService.upload()` 逐块写 MySQL 且无事务，中途失败会留孤儿 `kb_chunk` + 文档状态 FAILED（踩坑 17）。原计划（待办 A 第 7 项）是给它加 `@Transactional(rollbackFor = Exception.class)`，用"ES 写失败就回滚 MySQL"换一致性。
- **决策**：**不加事务**。保持现有流程（先落库置 PROCESSING → 逐块向量化 + 双写 → 成功置 READY / 失败置 FAILED），把一致性问题整体交给 **W7 的 MQ 异步处理（失败重试 + 死信）+ W11 的 ES/MySQL 对账补偿**。
- **放弃的方案**：`@Transactional`。两条理由：① `upload()` 的循环体内每块都要打一次百炼向量化 + 一次 ES 写入，**全是外部 HTTP 调用**，加事务等于把数据库连接按"几十次网络往返"的时长占住，W11 压测时是连接池打满的典型元凶；② **ES 不参与 MySQL 事务**，事务回滚时已经写进 ES 的 chunk 照样留在 ES 里——孤儿数据只是从 MySQL 挪到了 ES，一致性没有实质改善，反而多了长事务的代价。
- **后续验证**：W7 用 RocketMQ 把"分块 + 向量化 + 双写"移到 consumer（带重试与死信），W11 跑对账脚本验证最终一致。
