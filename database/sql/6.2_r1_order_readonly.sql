-- Fill in the numeric ID from /orders/{id} for the known R1 test order.
SET @r1_order_id = NULL;
START TRANSACTION READ ONLY;

SELECT o.id AS order_id, o.order_status,
       o.buyer_confirmed_at, o.auto_confirmed_at,
       TIMESTAMPADD(HOUR, 24, COALESCE(o.buyer_confirmed_at, o.auto_confirmed_at)) AS eligible_at_utc,
       UTC_TIMESTAMP(3) AS checked_at_utc,
       e.id AS escrow_id, e.principal_amount, e.released_amount, e.returned_amount,
       s.id AS settlement_id, s.settled_at, s.fee_amount, s.seller_net_amount,
       EXISTS(SELECT 1 FROM `2024011324`.return_requests r
              WHERE r.active_order_key = o.id) AS active_return,
       EXISTS(SELECT 1 FROM `2024011324`.return_requests r
              JOIN `2024011324`.refunds f ON f.return_request_id = r.id
              WHERE r.order_id = o.id AND f.completed_at IS NULL) AS unfinished_refund
FROM `2024011324`.merchant_orders o
LEFT JOIN `2024011324`.escrows e ON e.order_id = o.id
LEFT JOIN `2024011324`.settlements s ON s.escrow_id = e.id
WHERE o.id = @r1_order_id;

SELECT w.id AS seller_wallet_id, le.id AS entry_id, le.entry_kind,
       le.direction, le.amount, le.created_at
FROM `2024011324`.merchant_orders o
JOIN `2024011324`.merchants m ON m.id = o.merchant_id
LEFT JOIN `2024011324`.wallets w ON w.user_id = m.user_id
LEFT JOIN `2024011324`.wallet_entries le ON le.wallet_id = w.id AND le.order_id = o.id
WHERE o.id = @r1_order_id
ORDER BY le.id;

SELECT n.id AS notification_id,
       CASE WHEN n.recipient_id = b.buyer_id THEN 'BUYER' ELSE 'SELLER' END AS recipient_side,
       n.notification_kind, n.event_key, n.target_type, n.target_id, n.created_at, n.read_at
FROM `2024011324`.merchant_orders o
JOIN `2024011324`.checkout_batches b ON b.id = o.batch_id
JOIN `2024011324`.merchants m ON m.id = o.merchant_id
JOIN `2024011324`.notifications n ON n.recipient_id IN (b.buyer_id, m.user_id)
WHERE o.id = @r1_order_id AND (
  (n.target_type = 'ORDER' AND n.target_id = o.id)
  OR (n.target_type = 'RETURN_REQUEST' AND EXISTS (
        SELECT 1 FROM `2024011324`.return_requests r WHERE r.id = n.target_id AND r.order_id = o.id))
)
ORDER BY n.created_at, n.id;

COMMIT;
