-- Creates the database and a dedicated MySQL account for the POS, so the app never runs as root.
-- Run once as root, after replacing CHANGE-ME with a strong password:
--   mysql -u root -p < docs/mysql-setup.sql

CREATE DATABASE IF NOT EXISTS cashier_system CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'haka_pos'@'localhost' IDENTIFIED BY 'CHANGE-ME';

-- Only this database. Flyway needs CREATE/ALTER/INDEX/REFERENCES to apply schema migrations.
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP
    ON cashier_system.* TO 'haka_pos'@'localhost';

FLUSH PRIVILEGES;
