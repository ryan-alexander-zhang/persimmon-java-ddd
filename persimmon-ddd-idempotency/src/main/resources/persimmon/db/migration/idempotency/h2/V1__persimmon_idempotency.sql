-- persimmon-ddd idempotency store (h2). One row per (tenant, principal, key); expired rows are purged.
CREATE TABLE IF NOT EXISTS persimmon_idempotency (
    tenant_id        VARCHAR(32)  NOT NULL,
    principal        VARCHAR(255) NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    fingerprint      VARCHAR(64)  NOT NULL,
    state            VARCHAR(16)  NOT NULL,
    response_status  INT,
    response_body    BLOB,
    response_headers TEXT,
    created_at       TIMESTAMP    NOT NULL,
    expires_at       TIMESTAMP    NOT NULL,
    PRIMARY KEY (tenant_id, principal, idempotency_key)
);
