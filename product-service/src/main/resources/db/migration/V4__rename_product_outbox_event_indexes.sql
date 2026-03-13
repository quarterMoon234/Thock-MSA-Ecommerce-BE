ALTER TABLE product_outbox_event
    RENAME INDEX idx_product_outbox_status_created
    TO idx_product_outbox_event_status_created_at;