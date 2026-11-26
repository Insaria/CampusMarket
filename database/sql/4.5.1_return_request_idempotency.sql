USE campus_market_dev;

-- Existing requests did not have a client key and retain NULL.
ALTER TABLE return_requests
    ADD COLUMN request_key VARCHAR(128) COLLATE utf8mb4_0900_bin NULL DEFAULT NULL AFTER order_id,
    ADD UNIQUE KEY uq_return_requests_order_request_key (order_id, request_key);

SHOW CREATE TABLE return_requests;
