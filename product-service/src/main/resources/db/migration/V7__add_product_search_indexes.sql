CREATE INDEX idx_products_category_state_price_id
    ON products (category, state, price, id);
