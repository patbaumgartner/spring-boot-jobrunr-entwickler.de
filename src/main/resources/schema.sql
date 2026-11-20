CREATE TABLE IF NOT EXISTS invoice_run (
    id              UUID PRIMARY KEY,
    company         TEXT NOT NULL,
    billing_date    DATE NOT NULL,
    revision        INTEGER NOT NULL,
    snapshot        TEXT NOT NULL,
    input_hash      TEXT NOT NULL,
    -- NULL means: process immediately.
    run_at          TIMESTAMPTZ,
    job_id          UUID,
    package_status  TEXT NOT NULL DEFAULT 'PENDING',
    delivery_status TEXT NOT NULL DEFAULT 'PENDING',
    receipt         TEXT,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at    TIMESTAMPTZ,
    -- The business key: one run per company, billing date and revision.
    UNIQUE (company, billing_date, revision)
);

-- Demo evidence: one row per execution of a durable step.
CREATE TABLE IF NOT EXISTS step_execution (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID NOT NULL REFERENCES invoice_run (id),
    step        TEXT NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Local receiver used by the HTTP integration demo.
CREATE TABLE IF NOT EXISTS portal_receipt (
    transfer_id   UUID PRIMARY KEY,
    checksum      TEXT NOT NULL,
    package_bytes BYTEA NOT NULL,
    receipt       TEXT NOT NULL,
    attempts      INTEGER NOT NULL DEFAULT 1,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
