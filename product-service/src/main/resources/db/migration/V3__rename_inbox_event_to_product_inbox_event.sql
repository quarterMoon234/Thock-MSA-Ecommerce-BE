RENAME TABLE inbox_event TO product_inbox_event;

ALTER TABLE product_inbox_event
    RENAME INDEX uk_inbox_idempotency_key_consumer_group
    TO uk_product_inbox_event_idempotency_key_consumer_group;

ALTER TABLE product_inbox_event
    RENAME INDEX idx_inbox_topic_consumer_group
    TO idx_product_inbox_event_topic_consumer_group;

ALTER TABLE product_inbox_event
    RENAME INDEX idx_inbox_created_at
    TO idx_product_inbox_event_created_at;