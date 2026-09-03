-- =====================================================================
-- AI Helpdesk · V1 初始表结构
-- 数据库: ai_helpdesk (utf8mb4)
-- 变更原则: 本文件只增不改；后续变更新增 V2__xxx.sql、V3__xxx.sql ...
-- =====================================================================

CREATE DATABASE IF NOT EXISTS ai_helpdesk DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_helpdesk;

-- 坐席/管理员
CREATE TABLE sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(64) NOT NULL,
  nickname VARCHAR(32),
  role VARCHAR(10) NOT NULL DEFAULT 'AGENT' COMMENT 'ADMIN/AGENT',
  status TINYINT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '坐席/管理员';

-- 访客（匿名）
CREATE TABLE visitor (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  nickname VARCHAR(32),
  last_ip VARCHAR(45),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '访客';

-- 会话
CREATE TABLE conversation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  visitor_id BIGINT NOT NULL,
  agent_id BIGINT DEFAULT NULL COMMENT '接管坐席, NULL=AI处理中',
  status VARCHAR(20) NOT NULL DEFAULT 'AI' COMMENT 'AI/AGENT/CLOSED',
  resolved TINYINT COMMENT '1已解决 0未解决 NULL未评价',
  last_message_at DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_visitor (visitor_id),
  KEY idx_status_last (status, last_message_at)
) COMMENT '会话';

-- 聊天消息（大表：游标分页走 (conversation_id, id) 联合索引）
CREATE TABLE message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  sender_type VARCHAR(10) NOT NULL COMMENT 'VISITOR/AI/AGENT/SYSTEM',
  content TEXT NOT NULL,
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_conv (conversation_id, id)
) COMMENT '聊天消息';

-- 工单
CREATE TABLE ticket (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_no VARCHAR(32) NOT NULL UNIQUE COMMENT '对外单号',
  conversation_id BIGINT COMMENT '来源会话',
  title VARCHAR(128) NOT NULL,
  description TEXT,
  category VARCHAR(32) COMMENT '退款/物流/账号/其他',
  priority TINYINT DEFAULT 1 COMMENT '1低2中3高',
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PROCESSING/RESOLVED/CLOSED',
  assignee_id BIGINT COMMENT '处理人',
  sla_deadline DATETIME,
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status_pri (status, priority),
  KEY idx_assignee (assignee_id, status)
) COMMENT '工单';

-- 工单流转日志
CREATE TABLE ticket_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  operator_id BIGINT,
  action VARCHAR(32) COMMENT 'CREATE/ASSIGN/TRANSFER/RESOLVE/CLOSE',
  from_status VARCHAR(20), to_status VARCHAR(20),
  remark VARCHAR(255),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ticket (ticket_id)
) COMMENT '工单流转日志';

-- 知识库文档
CREATE TABLE kb_document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(128) NOT NULL,
  source_type VARCHAR(10) COMMENT 'FILE/TEXT',
  status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/READY/FAILED',
  chunk_num INT DEFAULT 0,
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '知识库文档';

-- 知识库分块（内容同步到 ES）
CREATE TABLE kb_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  content TEXT NOT NULL,
  es_id VARCHAR(64),
  KEY idx_doc (document_id)
) COMMENT '知识库分块';

-- LLM 调用日志（成本/延迟统计）
CREATE TABLE llm_call_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scene VARCHAR(20) COMMENT 'CHAT/RAG/EVAL/JUDGE',
  model VARCHAR(32),
  prompt_tokens INT, completion_tokens INT,
  latency_ms INT,
  success TINYINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_scene_time (scene, created_at)
) COMMENT 'LLM调用日志';

-- 评测题目
CREATE TABLE eval_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  question VARCHAR(255) NOT NULL,
  expected_answer TEXT,
  tags VARCHAR(64),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '评测题目';

-- 评测运行记录
CREATE TABLE eval_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_count INT,
  retrieval_hit INT COMMENT '命中条数',
  avg_score DECIMAL(4,2) COMMENT 'judge 均分',
  model VARCHAR(32),
  prompt_version VARCHAR(32),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '评测运行记录';

-- 冒烟数据：一个管理员账号，密码 bcrypt 加密后替换占位符
INSERT INTO sys_user (username, password_hash, nickname, role)
VALUES ('admin', '$2a$10$PLACEHOLDER_REPLACE_AT_FIRST_RUN', '管理员', 'ADMIN');
