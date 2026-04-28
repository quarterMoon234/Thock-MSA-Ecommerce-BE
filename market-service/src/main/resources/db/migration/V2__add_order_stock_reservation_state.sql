SET @table_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'market_orders'
);

SET @column_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'market_orders'
      AND COLUMN_NAME = 'stock_reservation_state'
);

SET @sql := IF(
    @table_exists = 1 AND @column_exists = 0,
    'ALTER TABLE market_orders ADD COLUMN stock_reservation_state varchar(50) NOT NULL DEFAULT ''NOT_REQUESTED''',
    'SELECT 1'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;