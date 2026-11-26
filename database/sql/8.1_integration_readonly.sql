-- Run in MySQL Workbench against the CampusMarket server. SELECT statements only.
SELECT VERSION() AS mysql_version,
       (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = '2024011324' AND table_type = 'BASE TABLE') AS base_tables,
       EXISTS(SELECT 1 FROM information_schema.tables
              WHERE table_schema = '2024011324' AND table_name = 'product_messages') AS message_object_exists;

SELECT t.table_name, t.table_type, t.engine, t.table_collation,
       c.column_type AS id_type, c.column_key AS id_key
FROM information_schema.tables t
LEFT JOIN information_schema.columns c
  ON c.table_schema = t.table_schema AND c.table_name = t.table_name AND c.column_name = 'id'
WHERE t.table_schema = '2024011324'
  AND t.table_name IN ('users', 'products', 'notifications', 'product_messages')
ORDER BY t.table_name;

SELECT '4.4_settlement_snapshot_fk' AS check_name, COUNT(*) AS present
FROM information_schema.table_constraints
WHERE constraint_schema = '2024011324' AND table_name = 'settlements'
  AND constraint_name = 'fk_settlements_level_snapshot' AND constraint_type = 'FOREIGN KEY'
UNION ALL
SELECT '4.5.1_return_idempotency', COUNT(*) FROM information_schema.table_constraints
WHERE constraint_schema = '2024011324' AND table_name = 'return_requests'
  AND constraint_name = 'uq_return_requests_order_request_key' AND constraint_type = 'UNIQUE'
UNION ALL
SELECT '4.6.1_review_rating_check', COUNT(*) FROM information_schema.table_constraints
WHERE constraint_schema = '2024011324' AND table_name = 'trade_reviews'
  AND constraint_name = 'ck_trade_reviews_rating' AND constraint_type = 'CHECK'
UNION ALL
SELECT 'notifications_dedup_index', COUNT(DISTINCT index_name)
FROM (SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS key_columns
      FROM information_schema.statistics WHERE table_schema = '2024011324'
        AND table_name = 'notifications' AND non_unique = 0 GROUP BY index_name) notification_keys
WHERE key_columns = 'recipient_id,notification_kind,event_key';

SELECT 'users' AS entity, COUNT(*) AS total FROM `2024011324`.users
UNION ALL SELECT 'products', COUNT(*) FROM `2024011324`.products
UNION ALL SELECT 'merchant_orders', COUNT(*) FROM `2024011324`.merchant_orders
UNION ALL SELECT 'wallets', COUNT(*) FROM `2024011324`.wallets
UNION ALL SELECT 'wallet_entries', COUNT(*) FROM `2024011324`.wallet_entries
UNION ALL SELECT 'product_images', COUNT(*) FROM `2024011324`.product_images;

SELECT
  COUNT(CASE WHEN role_code = 'ADMIN' AND registration_status = 'APPROVED' AND account_status = 'ENABLED'
             THEN 1 END) AS enabled_admins,
  COUNT(CASE WHEN role_code = 'USER' AND registration_status = 'APPROVED' AND account_status = 'ENABLED'
             THEN 1 END) AS enabled_buyer_candidates,
  COUNT(CASE WHEN role_code = 'USER' AND registration_status = 'PENDING' AND account_status = 'ENABLED'
             THEN 1 END) AS enabled_pending_registrations
FROM `2024011324`.users;

SELECT s.seller_type, COUNT(*) AS eligible_sellers, SUM(s.on_sale) AS in_stock_products,
       SUM(s.on_sale > 0) AS sellers_with_stock
FROM (
  SELECT m.id, m.seller_type, COUNT(p.id) AS on_sale
  FROM `2024011324`.merchants m
  JOIN `2024011324`.users u ON u.id = m.user_id
  LEFT JOIN `2024011324`.merchant_levels l ON l.id = m.level_id
  LEFT JOIN `2024011324`.products p ON p.merchant_id = m.id AND p.review_status = 'APPROVED'
    AND p.listing_status = 'ON_SHELF' AND p.deleted_at IS NULL AND p.available_quantity > 0
    AND EXISTS(SELECT 1 FROM `2024011324`.categories c WHERE c.id = p.category_id AND c.is_enabled = 1)
  WHERE m.review_status = 'APPROVED' AND m.operation_status = 'ACTIVE'
    AND u.role_code = 'USER' AND u.registration_status = 'APPROVED' AND u.account_status = 'ENABLED'
    AND (m.seller_type = 'PERSONAL' OR (m.seller_type = 'BUSINESS' AND l.is_enabled = 1))
  GROUP BY m.id, m.seller_type
) s
GROUP BY s.seller_type;
