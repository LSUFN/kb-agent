-- =====================================================================
-- V1：初始化表结构（对应 docs/ARCHITECTURE.md §5，v0.2）
-- 种子数据放在后续迁移脚本中：V2 业务演示数据（M4）、V3 演示账号（M1）。
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------- 用户 ----------
CREATE TABLE sys_user (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username      VARCHAR(64)   NOT NULL UNIQUE,
  password_hash VARCHAR(100)  NOT NULL,
  display_name  VARCHAR(64)   NOT NULL,
  role          VARCHAR(16)   NOT NULL CHECK (role IN ('USER','ANALYST','ADMIN')),
  region_scope  VARCHAR(16)[] NOT NULL DEFAULT '{}',   -- ANALYST 的数据范围；ADMIN 忽略此字段（可访问全部）
  enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ---------- 知识库 ----------
CREATE TABLE knowledge_base (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  owner_id    BIGINT       NOT NULL REFERENCES sys_user(id),
  visibility  VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','PUBLIC')),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE kb_member (
  kb_id      BIGINT      NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
  user_id    BIGINT      NOT NULL REFERENCES sys_user(id),
  permission VARCHAR(16) NOT NULL CHECK (permission IN ('READER','EDITOR')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (kb_id, user_id)
);
CREATE INDEX idx_kb_member_user ON kb_member(user_id);

-- ---------- 文档与向量 ----------
CREATE TABLE document (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kb_id           BIGINT       NOT NULL REFERENCES knowledge_base(id),
  filename        VARCHAR(255) NOT NULL,
  content_hash    CHAR(64)     NOT NULL,
  storage_path    VARCHAR(512) NOT NULL UNIQUE,         -- 对账时按路径反查
  size_bytes      BIGINT       NOT NULL,
  page_count      INT,
  chunk_count     INT          NOT NULL DEFAULT 0,
  status          VARCHAR(16)  NOT NULL
                  CHECK (status IN ('PENDING','PROCESSING','READY','FAILED','DELETING')),
  error_code      VARCHAR(64),
  error_message   VARCHAR(1000),
  embedding_model VARCHAR(64),
  uploaded_by     BIGINT       NOT NULL REFERENCES sys_user(id),
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 部分唯一索引：正在删除的文档不参与去重，用户可以立即重新上传同一文件
CREATE UNIQUE INDEX uk_document_kb_hash ON document(kb_id, content_hash) WHERE status <> 'DELETING';
CREATE INDEX idx_document_kb_status ON document(kb_id, status);

CREATE TABLE doc_chunk (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id BIGINT       NOT NULL REFERENCES document(id) ON DELETE CASCADE,
  kb_id       BIGINT       NOT NULL,                    -- 冗余字段，用于检索时按知识库过滤
  chunk_index INT          NOT NULL,
  page_number INT          NOT NULL,
  content     TEXT         NOT NULL,
  char_count  INT          NOT NULL,
  embedding   vector(1024) NOT NULL,                    -- 维度与 Embedding 模型一致，启动时校验（M2）
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (document_id, chunk_index)
);
CREATE INDEX idx_chunk_kb ON doc_chunk(kb_id);
CREATE INDEX idx_chunk_embedding ON doc_chunk USING hnsw (embedding vector_cosine_ops);

-- ---------- 通用异步任务 ----------
CREATE TABLE async_task (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_type    VARCHAR(32)  NOT NULL CHECK (task_type IN ('INGEST_DOCUMENT','DELETE_FILE')),
  biz_id       BIGINT       NOT NULL,                   -- document_id
  payload      JSONB,                                   -- 例如 DELETE_FILE 的 storage_path
  status       VARCHAR(16)  NOT NULL
               CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','DEAD','CANCELLED')),
  attempt      INT          NOT NULL DEFAULT 0,
  max_attempts INT          NOT NULL DEFAULT 5,
  next_run_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  locked_by    VARCHAR(64),
  lease_until  TIMESTAMPTZ,
  last_error   VARCHAR(1000),
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_claim ON async_task(status, next_run_at);
-- 同一业务对象同时最多一个活跃任务
CREATE UNIQUE INDEX uk_task_active ON async_task(task_type, biz_id) WHERE status IN ('PENDING','RUNNING');

-- ---------- 会话与消息 ----------
CREATE TABLE conversation (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id    BIGINT       NOT NULL REFERENCES sys_user(id),
  title      VARCHAR(128) NOT NULL DEFAULT '新会话',
  mode       VARCHAR(16)  NOT NULL CHECK (mode IN ('KB_QA','AGENT')),
  kb_id      BIGINT       REFERENCES knowledge_base(id) ON DELETE SET NULL,
  deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_conv_user ON conversation(user_id, updated_at DESC) WHERE deleted = FALSE;

CREATE TABLE chat_message (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  conversation_id BIGINT      NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
  role            VARCHAR(16) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
  content         TEXT        NOT NULL DEFAULT '',
  citations       JSONB,                                -- [{index, documentId, filename, page, snippet, score}]
  citation_mode   VARCHAR(16) CHECK (citation_mode IN ('NORMAL','FALLBACK','NONE')),
  tool_steps      JSONB,                                -- [{callId, tool, argsMasked, decision, ok, latencyMs, summary}]
  status          VARCHAR(16) NOT NULL DEFAULT 'OK'
                  CHECK (status IN ('STREAMING','OK','FAILED','INTERRUPTED')),
  error_code      VARCHAR(64),
  trace_id        VARCHAR(64),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_msg_conv ON chat_message(conversation_id, id);
CREATE INDEX idx_msg_streaming ON chat_message(updated_at) WHERE status = 'STREAMING';

-- ---------- 报告 ----------
CREATE TABLE report (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         BIGINT       NOT NULL REFERENCES sys_user(id),
  conversation_id BIGINT       REFERENCES conversation(id) ON DELETE SET NULL,
  report_type     VARCHAR(32)  NOT NULL,
  title           VARCHAR(200) NOT NULL,
  params          JSONB        NOT NULL,                -- 收敛之后实际生效的参数（含区域）
  content_md      TEXT         NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_user ON report(user_id, created_at DESC);

-- ---------- 可观测性（写入前已脱敏，见 ARCHITECTURE.md §4.6） ----------
CREATE TABLE llm_call_log (
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  trace_id             VARCHAR(64),
  user_id              BIGINT,
  conversation_id      BIGINT,
  call_type            VARCHAR(16)  NOT NULL CHECK (call_type IN ('CHAT','EMBEDDING')),
  chat_mode            VARCHAR(16),
  model                VARCHAR(64)  NOT NULL,
  streamed             BOOLEAN      NOT NULL DEFAULT FALSE,
  prompt_tokens        INT,
  completion_tokens    INT,
  usage_estimated      BOOLEAN      NOT NULL DEFAULT FALSE,
  tool_call_count      INT          NOT NULL DEFAULT 0,
  input_preview_masked VARCHAR(256),
  input_sha256         CHAR(64),
  output_chars         INT,
  first_token_ms       INT,
  latency_ms           INT          NOT NULL,
  success              BOOLEAN      NOT NULL,
  error_code           VARCHAR(64),
  error_message_masked VARCHAR(1000),
  created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_llm_log_time ON llm_call_log(created_at);
CREATE INDEX idx_llm_log_trace ON llm_call_log(trace_id);

CREATE TABLE tool_call_log (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  trace_id        VARCHAR(64),
  tool_call_id    VARCHAR(64),
  user_id         BIGINT        NOT NULL,
  user_role       VARCHAR(16)   NOT NULL,
  conversation_id BIGINT,
  tool_name       VARCHAR(64)   NOT NULL,
  args_masked     JSONB,
  effective_scope VARCHAR(16)[],
  decision        VARCHAR(16)   NOT NULL
                  CHECK (decision IN ('ALLOWED','DENIED','INVALID','BUDGET_EXCEEDED')),
  success         BOOLEAN       NOT NULL,
  result_summary  VARCHAR(500),
  error_code      VARCHAR(64),
  latency_ms      INT           NOT NULL,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_tool_log_time ON tool_call_log(created_at);
CREATE INDEX idx_tool_log_trace ON tool_call_log(trace_id);

-- ---------- 演示业务数据（区域枚举：EAST/NORTH/SOUTH/WEST/CENTRAL） ----------
CREATE TABLE biz_order (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_no   VARCHAR(32)   NOT NULL UNIQUE,
  customer   VARCHAR(128)  NOT NULL,
  region     VARCHAR(16)   NOT NULL CHECK (region IN ('EAST','NORTH','SOUTH','WEST','CENTRAL')),
  category   VARCHAR(32)   NOT NULL,
  amount     NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
  status     VARCHAR(16)   NOT NULL CHECK (status IN ('CREATED','PAID','SHIPPED','CANCELLED','REFUNDED')),
  created_at TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_order_time_region ON biz_order(created_at, region);

CREATE TABLE biz_anomaly_event (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_type    VARCHAR(32)  NOT NULL
                CHECK (event_type IN ('PAYMENT_TIMEOUT','INVENTORY_MISMATCH','API_ERROR','LOGISTICS_DELAY')),
  severity      SMALLINT     NOT NULL CHECK (severity BETWEEN 1 AND 4),
  source_system VARCHAR(32)  NOT NULL,
  region        VARCHAR(16)  NOT NULL CHECK (region IN ('EAST','NORTH','SOUTH','WEST','CENTRAL')),
  description   VARCHAR(500),
  occurred_at   TIMESTAMPTZ  NOT NULL,
  resolved_at   TIMESTAMPTZ
);
CREATE INDEX idx_anomaly_time_type ON biz_anomaly_event(occurred_at, event_type);
