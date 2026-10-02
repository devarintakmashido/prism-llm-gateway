-- Prism schema. One durable store (PostgreSQL + pgvector) for tenants, prices,
-- usage, request logs and semantic-cache entries. Money is stored as integer
-- nano-USD (1 USD = 1_000_000_000 nanos) so sums never drift.

CREATE EXTENSION IF NOT EXISTS vector;

-- Virtual keys (one tenant = one team = one key). Only the SHA-256 of the key
-- is stored; key_hint is a masked form that is safe to show in the console.
CREATE TABLE tenants (
    id                   BIGSERIAL PRIMARY KEY,
    team                 TEXT        NOT NULL UNIQUE,
    key_hash             CHAR(64)    NOT NULL UNIQUE,
    key_hint             TEXT        NOT NULL,
    monthly_budget_nanos BIGINT      NOT NULL CHECK (monthly_budget_nanos >= 0),
    rpm_limit            INT         NOT NULL CHECK (rpm_limit > 0),
    tpm_limit            INT,
    model_allowlist      TEXT[]      NOT NULL,
    cache_enabled        BOOLEAN     NOT NULL DEFAULT FALSE,
    cache_threshold      DOUBLE PRECISION,
    status               TEXT        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Price table (USD per 1M tokens), loaded from data/model_pricing.json.
CREATE TABLE model_prices (
    model         TEXT PRIMARY KEY,
    input_per_1m  NUMERIC(14, 6) NOT NULL CHECK (input_per_1m >= 0),
    output_per_1m NUMERIC(14, 6) NOT NULL CHECK (output_per_1m >= 0),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Per-key calendar-month aggregates used for budget admission. A new month is
-- a new row, which is the "reset". reserved_nanos holds the estimated cost of
-- requests that are admitted but not finished yet.
CREATE TABLE usage_monthly (
    tenant_id         BIGINT NOT NULL REFERENCES tenants (id),
    month             DATE   NOT NULL,
    spent_nanos       BIGINT NOT NULL DEFAULT 0,
    reserved_nanos    BIGINT NOT NULL DEFAULT 0,
    requests          BIGINT NOT NULL DEFAULT 0,
    prompt_tokens     BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, month)
);

-- One row per data-plane request, including rejected ones.
CREATE TABLE request_logs (
    request_id        TEXT PRIMARY KEY,
    tenant_id         BIGINT REFERENCES tenants (id),
    team              TEXT,
    key_hint          TEXT,
    requested_model   TEXT,
    resolved_alias    TEXT,
    resolved_provider TEXT,
    resolved_model    TEXT,
    status            TEXT        NOT NULL,
    http_status       INT         NOT NULL,
    error_type        TEXT,
    error_message     TEXT,
    stream            BOOLEAN     NOT NULL DEFAULT FALSE,
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    cost_nanos        BIGINT      NOT NULL DEFAULT 0,
    cost_estimated    BOOLEAN     NOT NULL DEFAULT FALSE,
    cache             TEXT        NOT NULL DEFAULT 'miss',
    cache_similarity  DOUBLE PRECISION,
    cache_note        TEXT,
    fallback          BOOLEAN     NOT NULL DEFAULT FALSE,
    retries           INT         NOT NULL DEFAULT 0,
    attempts          JSONB,
    route_tier        TEXT,
    route_reason      TEXT,
    latency_ms        INT         NOT NULL,
    upstream_ms       INT,
    ttft_ms           INT,
    gateway_instance  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_request_logs_tenant_time ON request_logs (tenant_id, created_at DESC);
CREATE INDEX idx_request_logs_time ON request_logs (created_at DESC);

-- Semantic cache. Always scoped by tenant; scope_key adds the model tier and a
-- hash of the conversation context and generation parameters. Entries hold the
-- prompt text and the response (both tenant-private) and expire after a TTL.
CREATE TABLE cache_entries (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES tenants (id),
    scope_key      TEXT        NOT NULL,
    prompt_hash    CHAR(64)    NOT NULL,
    prompt_text    TEXT        NOT NULL,
    guard_tokens   TEXT        NOT NULL,
    embedding      vector(384),
    response       JSONB       NOT NULL,
    served_by      TEXT        NOT NULL,
    cost_nanos     BIGINT      NOT NULL DEFAULT 0,
    hit_count      INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_hit_at    TIMESTAMPTZ,
    expires_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_cache_scope ON cache_entries (tenant_id, scope_key);
CREATE UNIQUE INDEX uq_cache_exact ON cache_entries (tenant_id, scope_key, prompt_hash);
