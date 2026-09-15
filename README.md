# AI Helpdesk · AI 智能客服工单平台

以 AI agent 为一线客服的客服系统：agent 基于知识库回答常见问题、查单、建工单，低置信度自动转人工；人工坐席在控制台监控、接管会话并沉淀知识库。面向养不起客服团队的小团队（独立开发者、小店、开源项目）。

## 技术栈

- 后端：JDK 17 · SpringBoot 3.x · MyBatis-Plus · MySQL 8.0 · Redis
- 检索：Elasticsearch 8（BM25 + 向量混合检索）
- 中间件：RocketMQ 5（异步/延迟/死信）· Nacos · Sentinel（第二阶段）
- AI：Spring AI · GLM（RAG + function calling + 评测回归）
- 前端：Vue 3 · Element Plus · Pinia（SSE 流式聊天）
- 部署：Docker Compose

## 文档

- [项目规划书](docs/01-项目规划书.md)：定位 / 架构 / 库表 / 接口 / 16 周里程碑 / 面试考点映射
- [决策日志](docs/decision-log.md)：技术选型与取舍记录
- [建表脚本](docs/sql/)：按版本号迭代（V1__init.sql 起）

## 本地运行

```bash
# 后端
mvn spring-boot:run
# 前端
cd helpdesk-web && npm run dev
```

## 进度

- [x] 项目规划、库表与接口设计
- [x] W1：骨架搭建（统一响应 / 全局异常 / JWT 登录 + 登录拦截器）
- [x] W2：会话与消息（游标分页；前后端联调通过）
- [x] W3：LLM 接入 DeepSeek + SSE 流式打字机（多轮对话记忆）
- [x] W4：Elasticsearch 知识库（IK 中文分词 + kNN 向量字段）——上传 → 分块 → 向量化 → MySQL/ES 双写 → 状态机
- [ ] **W5（进行中）**：RAG 检索——kNN 向量检索 / 混合检索（BM25+向量+RRF）/ 回答带引用
- [ ] W6：工单模块 + function calling → MVP 闭环
- [ ] W7-12：MQ / Redis 深度 / 微服务 / 评测
- [ ] W13-16：Agent 升级 / 部署上线

> 详细进度与交接说明见 [docs/02-开发日志.md](docs/02-开发日志.md)
