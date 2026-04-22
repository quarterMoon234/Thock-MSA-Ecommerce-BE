CREATE TABLE IF NOT EXISTS `market_cart_product_views`
(
    `id`             bigint       NOT NULL AUTO_INCREMENT,
    `product_id`     bigint       NOT NULL,
    `seller_id`      bigint DEFAULT NULL,
    `name`           varchar(255) DEFAULT NULL,
    `image_url`      varchar(255) DEFAULT NULL,
    `price`          bigint DEFAULT NULL,
    `sale_price`     bigint DEFAULT NULL,
    `stock`          int DEFAULT NULL,
    `reserved_stock` int DEFAULT NULL,
    `product_state`  varchar(255) DEFAULT NULL,
    `deleted`        bit(1)       NOT NULL,
    `created_at`     datetime(6) DEFAULT NULL,
    `updated_at`     datetime(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_market_cart_product_views_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
