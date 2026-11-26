-- Run in MySQL Workbench on the local development connection.
-- This file reads metadata and merchant level reference data.
USE campus_market_dev;

SELECT DATABASE() AS schema_name, VERSION() AS mysql_version,
       @@session.time_zone AS workbench_session_zone, @@foreign_key_checks AS foreign_keys_enabled;

SELECT COUNT(*) AS base_table_count
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';

SELECT TABLE_NAME, ENGINE, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_NAME;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'merchants' AND COLUMN_NAME IN ('seller_type', 'business_license_ref', 'identity_document_ref'))
    OR (TABLE_NAME = 'settlements' AND COLUMN_NAME IN ('level_code_snapshot', 'level_id_snapshot'))
    OR (TABLE_NAME = 'return_requests' AND COLUMN_NAME = 'request_key')
    OR (TABLE_NAME = 'trade_reviews' AND COLUMN_NAME = 'rating')
    OR DATA_TYPE IN ('decimal', 'datetime'))
ORDER BY TABLE_NAME, ORDINAL_POSITION;

SELECT TABLE_NAME, CONSTRAINT_NAME, DELETE_RULE, UPDATE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE()
ORDER BY TABLE_NAME, CONSTRAINT_NAME;

SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, tc.ENFORCED, cc.CHECK_CLAUSE
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.CONSTRAINT_TYPE = 'CHECK'
ORDER BY tc.TABLE_NAME, tc.CONSTRAINT_NAME;

SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS indexed_columns
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0
GROUP BY TABLE_NAME, INDEX_NAME
ORDER BY TABLE_NAME, INDEX_NAME;

SELECT level_code, level_name, fee_rate, is_enabled
FROM merchant_levels ORDER BY level_code;
