-- Manual Heroku Postgres bootstrap (optional).
-- Liquibase creates DATABASECHANGELOG / DATABASECHANGELOGLOCK itself on startup.
-- If you run this first, changeset 002-app-tables is MARK_RAN (sdd_scans already exists).
-- Never put credentials in this file.

CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    type VARCHAR(16) NOT NULL,
    last4 VARCHAR(4),
    phone VARCHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sdd_scans (
    id BIGSERIAL PRIMARY KEY,
    scanned_at TIMESTAMP NOT NULL,
    broker_id VARCHAR(64) NOT NULL,
    broker_name VARCHAR(128),
    execution_enabled BOOLEAN NOT NULL,
    news_blackout BOOLEAN NOT NULL,
    error TEXT,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sdd_signals (
    id BIGSERIAL PRIMARY KEY,
    scan_id BIGINT REFERENCES sdd_scans (id),
    scanned_at TIMESTAMP NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    epic VARCHAR(64),
    direction VARCHAR(8),
    full_stack BOOLEAN NOT NULL,
    flip BOOLEAN NOT NULL,
    reason TEXT,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS broker_snapshots (
    id BIGSERIAL PRIMARY KEY,
    book VARCHAR(16) NOT NULL,
    broker VARCHAR(64),
    account_name VARCHAR(128),
    equity DOUBLE PRECISION,
    available DOUBLE PRECISION,
    day_pnl DOUBLE PRECISION,
    currency VARCHAR(16),
    connected BOOLEAN NOT NULL,
    error TEXT,
    captured_at TIMESTAMP NOT NULL,
    payload TEXT
);

CREATE INDEX IF NOT EXISTS idx_sdd_scans_scanned_at ON sdd_scans (scanned_at);
CREATE INDEX IF NOT EXISTS idx_sdd_signals_scanned_at ON sdd_signals (scanned_at);
CREATE INDEX IF NOT EXISTS idx_broker_snapshots_captured_at ON broker_snapshots (captured_at);
