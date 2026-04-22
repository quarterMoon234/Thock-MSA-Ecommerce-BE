ALTER TABLE product_outbox_event
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN next_attempt_at DATETIME(6) NULL AFTER retry_count,
    ADD COLUMN last_error VARCHAR(1000) NULL AFTER next_attempt_at,
    ADD COLUMN sent_at DATETIME(6) NULL AFTER last_error;

UPDATE product_outbox_event
SET next_attempt_at = created_at
WHERE status = 'PENDING'
  AND next_attempt_at IS NULL;

UPDATE product_outbox_event
SET sent_at = created_at
WHERE status = 'SENT'
  AND sent_at IS NULL;

CREATE INDEX idx_product_outbox_event_status_next_attempt_created_at
    ON product_outbox_event (status, next_attempt_at, created_at);

CREATE INDEX idx_product_outbox_event_status_sent_at
    ON product_outbox_event (status, sent_at);