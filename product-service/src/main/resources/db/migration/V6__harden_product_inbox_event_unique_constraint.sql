ALTER TABLE product_inbox_event
DROP INDEX uk_product_inbox_event_idempotency_key_consumer_group;

ALTER TABLE product_inbox_event
    ADD CONSTRAINT uk_product_inbox_event_topic_consumer_group_idempotency_key
        UNIQUE (topic, consumer_group, idempotency_key);