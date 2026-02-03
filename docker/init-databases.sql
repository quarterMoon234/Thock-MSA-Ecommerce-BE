-- Create all databases for microservices
CREATE DATABASE IF NOT EXISTS member_db;
CREATE DATABASE IF NOT EXISTS product_db;
CREATE DATABASE IF NOT EXISTS market_db;
CREATE DATABASE IF NOT EXISTS payment_db;
CREATE DATABASE IF NOT EXISTS settlement_db;

-- Create users for each service
CREATE USER IF NOT EXISTS 'member_user'@'%' IDENTIFIED BY 'member_pass';
CREATE USER IF NOT EXISTS 'product_user'@'%' IDENTIFIED BY 'product_pass';
CREATE USER IF NOT EXISTS 'market_user'@'%' IDENTIFIED BY 'market_pass';
CREATE USER IF NOT EXISTS 'payment_user'@'%' IDENTIFIED BY 'payment_pass';
CREATE USER IF NOT EXISTS 'settlement_user'@'%' IDENTIFIED BY 'settlement_pass';

-- Grant privileges (each user can only access their own database)
GRANT ALL PRIVILEGES ON member_db.* TO 'member_user'@'%';
GRANT ALL PRIVILEGES ON product_db.* TO 'product_user'@'%';
GRANT ALL PRIVILEGES ON market_db.* TO 'market_user'@'%';
GRANT ALL PRIVILEGES ON payment_db.* TO 'payment_user'@'%';
GRANT ALL PRIVILEGES ON settlement_db.* TO 'settlement_user'@'%';

FLUSH PRIVILEGES;
