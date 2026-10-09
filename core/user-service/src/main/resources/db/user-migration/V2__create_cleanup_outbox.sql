CREATE TABLE cleanup_outbox (
    event_id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_cleanup_outbox_created ON cleanup_outbox(created_at, event_id);
