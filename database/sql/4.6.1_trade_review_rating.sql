USE campus_market_dev;

-- Existing text-only reviews keep NULL until an explicit rating is available.
ALTER TABLE trade_reviews
    ADD COLUMN rating TINYINT NULL DEFAULT NULL AFTER scene_key,
    ADD CONSTRAINT ck_trade_reviews_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5);

SHOW CREATE TABLE trade_reviews;
