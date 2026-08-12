-- ============================================================
-- 智能客服 MVP - 数据库初始化脚本（兼容 JPA ddl-auto）
-- 说明：去掉强 FK，便于任意 userId 开会话；Docker 首次初始化可用
-- ============================================================

CREATE TABLE IF NOT EXISTS cs_user (
    user_id         VARCHAR(64)    PRIMARY KEY,
    username        VARCHAR(128)   NOT NULL,
    nickname        VARCHAR(128),
    phone           VARCHAR(20),
    email           VARCHAR(128),
    avatar_url      VARCHAR(512),
    vip_level       SMALLINT       DEFAULT 0,
    status          VARCHAR(20)    DEFAULT 'active',
    created_at      TIMESTAMPTZ    DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS cs_session (
    session_id      VARCHAR(64)    PRIMARY KEY,
    tenant_id       VARCHAR(64)    DEFAULT 'default',
    user_id         VARCHAR(64)    NOT NULL,
    channel         VARCHAR(32)    NOT NULL DEFAULT 'web',
    active_agent    VARCHAR(64),
    status          VARCHAR(32)    DEFAULT 'ACTIVE',
    intent_history  JSONB          DEFAULT '[]'::JSONB,
    context         JSONB          DEFAULT '{}'::JSONB,
    langfuse_trace_id VARCHAR(128),
    created_at      TIMESTAMPTZ    DEFAULT NOW(),
    last_active_at  TIMESTAMPTZ    DEFAULT NOW(),
    closed_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_cs_session_user ON cs_session(user_id);
CREATE INDEX IF NOT EXISTS idx_cs_session_status ON cs_session(status);

CREATE TABLE IF NOT EXISTS cs_message (
    message_id      VARCHAR(64)    PRIMARY KEY,
    session_id      VARCHAR(64)    NOT NULL,
    role            VARCHAR(20)    NOT NULL,
    content         TEXT           NOT NULL,
    agent_name      VARCHAR(64),
    tool_name       VARCHAR(64),
    tool_params     JSONB,
    metadata        JSONB          DEFAULT '{}'::JSONB,
    langfuse_span_id VARCHAR(128),
    token_usage     JSONB,
    created_at      TIMESTAMPTZ    DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cs_message_session ON cs_message(session_id, created_at);

CREATE TABLE IF NOT EXISTS cs_tool_call_log (
    id              VARCHAR(64)    PRIMARY KEY,
    session_id      VARCHAR(64)    NOT NULL,
    user_id         VARCHAR(64),
    tenant_id       VARCHAR(64),
    tool_name       VARCHAR(128)   NOT NULL,
    confirmation_id VARCHAR(64),
    idempotency_key VARCHAR(128),
    input_params    JSONB,
    output_result   TEXT,
    status          VARCHAR(32),
    created_at      TIMESTAMPTZ    DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cs_tool_call_session ON cs_tool_call_log(session_id);

CREATE TABLE IF NOT EXISTS cs_handoff_record (
    id              VARCHAR(64)    PRIMARY KEY,
    session_id      VARCHAR(64)    NOT NULL,
    tenant_id       VARCHAR(64),
    user_id         VARCHAR(64),
    reason          VARCHAR(256),
    skill_group     VARCHAR(64),
    summary         TEXT,
    entities        JSONB,
    status          VARCHAR(32),
    agent_id        VARCHAR(64),
    queued_at       TIMESTAMPTZ,
    accepted_at     TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_cs_handoff_status ON cs_handoff_record(status);

CREATE TABLE IF NOT EXISTS cs_tool_invocation (
    invocation_id   VARCHAR(64)    PRIMARY KEY,
    session_id      VARCHAR(64)    NOT NULL,
    message_id      VARCHAR(64),
    tool_name       VARCHAR(128)   NOT NULL,
    agent_name      VARCHAR(64),
    input_params    JSONB          NOT NULL,
    output_result   JSONB,
    status          VARCHAR(20)    DEFAULT 'running',
    error_message   TEXT,
    duration_ms     INTEGER,
    langfuse_span_id VARCHAR(128),
    created_at      TIMESTAMPTZ    DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS cs_order (
    order_id        VARCHAR(64)    PRIMARY KEY,
    user_id         VARCHAR(64)    NOT NULL,
    order_no        VARCHAR(64)    NOT NULL UNIQUE,
    product_name    VARCHAR(256)   NOT NULL,
    product_sku     VARCHAR(64),
    quantity        INTEGER        NOT NULL DEFAULT 1,
    unit_price      DECIMAL(12,2)  NOT NULL,
    total_amount    DECIMAL(12,2)  NOT NULL,
    currency        VARCHAR(3)     DEFAULT 'CNY',
    status          VARCHAR(32)    DEFAULT 'pending',
    shipping_addr   JSONB,
    tracking_no     VARCHAR(128),
    paid_at         TIMESTAMPTZ,
    shipped_at      TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS cs_after_sales_ticket (
    ticket_id       VARCHAR(64)    PRIMARY KEY,
    session_id      VARCHAR(64),
    order_id        VARCHAR(64),
    user_id         VARCHAR(64)    NOT NULL,
    type            VARCHAR(32)    NOT NULL,
    reason          TEXT,
    status          VARCHAR(32)    DEFAULT 'submitted',
    refund_amount   DECIMAL(12,2),
    risk_level      VARCHAR(16)    DEFAULT 'low',
    approved_by     VARCHAR(64),
    created_at      TIMESTAMPTZ    DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS cs_complaint_ticket (
    ticket_id       VARCHAR(64)    PRIMARY KEY,
    session_id      VARCHAR(64),
    order_id        VARCHAR(64),
    user_id         VARCHAR(64)    NOT NULL,
    category        VARCHAR(64)    NOT NULL,
    severity        VARCHAR(16)    DEFAULT 'normal',
    description     TEXT           NOT NULL,
    status          VARCHAR(32)    DEFAULT 'submitted',
    resolution      TEXT,
    handler_id      VARCHAR(64),
    created_at      TIMESTAMPTZ    DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS cs_product (
    product_id      VARCHAR(64)    PRIMARY KEY,
    name            VARCHAR(256)   NOT NULL,
    category        VARCHAR(128),
    sku             VARCHAR(64)    UNIQUE,
    price           DECIMAL(12,2),
    description     TEXT,
    specifications  JSONB,
    stock           INTEGER        DEFAULT 0,
    status          VARCHAR(20)    DEFAULT 'on_sale',
    created_at      TIMESTAMPTZ    DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS cs_knowledge_doc (
    doc_id          VARCHAR(64)    PRIMARY KEY,
    title           VARCHAR(512)   NOT NULL,
    category        VARCHAR(128),
    content         TEXT           NOT NULL,
    source          VARCHAR(256),
    tags            VARCHAR(512)[],
    version         INTEGER        DEFAULT 1,
    is_active       BOOLEAN        DEFAULT TRUE,
    created_at      TIMESTAMPTZ    DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS cs_trace_snapshot (
    id              BIGSERIAL      PRIMARY KEY,
    trace_id        VARCHAR(128)   NOT NULL,
    session_id      VARCHAR(64),
    name            VARCHAR(256),
    input           JSONB,
    output          JSONB,
    status          VARCHAR(20),
    total_tokens    INTEGER,
    prompt_tokens   INTEGER,
    completion_tokens INTEGER,
    duration_ms     BIGINT,
    span_count      INTEGER,
    metadata        JSONB,
    flushed_at      TIMESTAMPTZ    DEFAULT NOW()
);
