CREATE TABLE outbox_message (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    topic           VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    headers         TEXT,
    status          VARCHAR(16)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    sent_at         TIMESTAMP
);

CREATE INDEX idx_outbox_pending ON outbox_message (status, next_attempt_at, created_at);

CREATE TABLE inbox_message (
    topic          VARCHAR(128) NOT NULL,
    transaction_id VARCHAR(128) NOT NULL,
    message_id     VARCHAR(64),
    processed_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (topic, transaction_id)
);
