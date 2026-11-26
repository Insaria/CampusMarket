USE campus_market_dev;
SET SESSION time_zone = '+00:00';

CREATE TABLE return_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    request_status VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    is_active TINYINT NOT NULL,
    active_order_key BIGINT GENERATED ALWAYS AS (CASE WHEN is_active = 1 THEN order_id ELSE NULL END) VIRTUAL,
    reason VARCHAR(500) NOT NULL,
    reviewer_user_id BIGINT NULL DEFAULT NULL,
    review_reason VARCHAR(500) NULL DEFAULT NULL,
    applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_at DATETIME(3) NULL DEFAULT NULL,
    returned_at DATETIME(3) NULL DEFAULT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_return_requests_active_order (active_order_key),
    KEY ix_return_requests_order_applied (order_id, applied_at, id),
    KEY ix_return_requests_reviewer_user_id (reviewer_user_id),
    CONSTRAINT fk_return_requests_order FOREIGN KEY (order_id) REFERENCES merchant_orders (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_return_requests_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_return_requests_active CHECK (is_active IN (0, 1)),
    CONSTRAINT ck_return_requests_content CHECK (
        CHAR_LENGTH(TRIM(request_status)) > 0 AND CHAR_LENGTH(TRIM(reason)) > 0
    ),
    CONSTRAINT ck_return_requests_review CHECK (
        (reviewer_user_id IS NULL AND reviewed_at IS NULL)
        OR (reviewer_user_id IS NOT NULL AND reviewed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE escrows (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    funding_entry_id BIGINT NOT NULL,
    principal_amount DECIMAL(14,2) NOT NULL,
    released_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    returned_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_escrows_order_id (order_id),
    UNIQUE KEY uq_escrows_funding_entry_id (funding_entry_id),
    CONSTRAINT fk_escrows_order FOREIGN KEY (order_id) REFERENCES merchant_orders (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_escrows_funding FOREIGN KEY (funding_entry_id) REFERENCES wallet_entries (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_escrows_amounts CHECK (
        principal_amount > 0
        AND released_amount >= 0
        AND returned_amount >= 0
        AND released_amount + returned_amount <= principal_amount
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE product_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_item_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_product_reviews_order_item (order_item_id),
    CONSTRAINT fk_product_reviews_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_product_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_product_reviews_content CHECK (CHAR_LENGTH(TRIM(content)) > 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE settlements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    escrow_id BIGINT NOT NULL,
    seller_credit_entry_id BIGINT NULL DEFAULT NULL,
    level_code_snapshot TINYINT NOT NULL,
    fee_rate_snapshot DECIMAL(7,6) NOT NULL,
    fee_rate_applied_at DATETIME(3) NOT NULL,
    fee_base_amount DECIMAL(14,2) NOT NULL,
    released_amount DECIMAL(14,2) NOT NULL,
    fee_amount DECIMAL(14,2) NOT NULL,
    seller_net_amount DECIMAL(14,2) NOT NULL,
    settled_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_settlements_escrow_id (escrow_id),
    UNIQUE KEY uq_settlements_seller_credit (seller_credit_entry_id),
    CONSTRAINT fk_settlements_escrow FOREIGN KEY (escrow_id) REFERENCES escrows (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_settlements_seller_credit FOREIGN KEY (seller_credit_entry_id) REFERENCES wallet_entries (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_settlements_fee_snapshot CHECK (
        level_code_snapshot BETWEEN 1 AND 5 AND fee_rate_snapshot BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_settlements_amounts CHECK (
        fee_base_amount >= 0
        AND released_amount > 0
        AND fee_amount >= 0
        AND seller_net_amount >= 0
        AND released_amount = fee_amount + seller_net_amount
    ),
    CONSTRAINT ck_settlements_seller_credit CHECK (
        (seller_net_amount > 0 AND seller_credit_entry_id IS NOT NULL)
        OR (seller_net_amount = 0 AND seller_credit_entry_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE refunds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_request_id BIGINT NOT NULL,
    escrow_id BIGINT NULL DEFAULT NULL,
    original_settlement_id BIGINT NULL DEFAULT NULL,
    buyer_credit_entry_id BIGINT NULL DEFAULT NULL,
    seller_recovery_entry_id BIGINT NULL DEFAULT NULL,
    refund_status VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    cash_refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    fee_reversal_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    points_restored BIGINT NOT NULL DEFAULT 0,
    points_reversed BIGINT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(500) NULL DEFAULT NULL,
    completed_at DATETIME(3) NULL DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_refunds_return_request (return_request_id),
    UNIQUE KEY uq_refunds_buyer_credit (buyer_credit_entry_id),
    UNIQUE KEY uq_refunds_seller_recovery (seller_recovery_entry_id),
    KEY ix_refunds_escrow_id (escrow_id),
    KEY ix_refunds_original_settlement_id (original_settlement_id),
    CONSTRAINT fk_refunds_return_request FOREIGN KEY (return_request_id) REFERENCES return_requests (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_refunds_escrow FOREIGN KEY (escrow_id) REFERENCES escrows (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_refunds_original_settlement FOREIGN KEY (original_settlement_id) REFERENCES settlements (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_refunds_buyer_credit FOREIGN KEY (buyer_credit_entry_id) REFERENCES wallet_entries (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_refunds_seller_recovery FOREIGN KEY (seller_recovery_entry_id) REFERENCES wallet_entries (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_refunds_status CHECK (CHAR_LENGTH(TRIM(refund_status)) > 0),
    CONSTRAINT ck_refunds_amounts CHECK (
        cash_refund_amount >= 0 AND fee_reversal_amount >= 0 AND points_restored >= 0 AND points_reversed >= 0
    ),
    CONSTRAINT ck_refunds_cash_source CHECK (
        (escrow_id IS NULL OR original_settlement_id IS NULL)
        AND (cash_refund_amount = 0 OR escrow_id IS NOT NULL OR original_settlement_id IS NOT NULL)
        AND (fee_reversal_amount = 0 OR original_settlement_id IS NOT NULL)
        AND (seller_recovery_entry_id IS NULL OR original_settlement_id IS NOT NULL)
    ),
    CONSTRAINT ck_refunds_buyer_credit CHECK (
        (cash_refund_amount = 0 AND buyer_credit_entry_id IS NULL)
        OR (cash_refund_amount > 0 AND buyer_credit_entry_id IS NOT NULL)
    ),
    CONSTRAINT ck_refunds_wallet_roles CHECK (
        buyer_credit_entry_id IS NULL OR seller_recovery_entry_id IS NULL OR buyer_credit_entry_id <> seller_recovery_entry_id
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE trade_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    reviewee_id BIGINT NOT NULL,
    return_request_id BIGINT NULL DEFAULT NULL,
    scene_key VARCHAR(32) COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (
        CASE WHEN return_request_id IS NULL THEN 'PURCHASE' ELSE CONCAT('RETURN:', return_request_id) END
    ) VIRTUAL NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_trade_reviews_order_parties_scene (order_id, reviewer_id, reviewee_id, scene_key),
    KEY ix_trade_reviews_reviewer_id (reviewer_id),
    KEY ix_trade_reviews_reviewee_created (reviewee_id, created_at, id),
    KEY ix_trade_reviews_return_request (return_request_id),
    CONSTRAINT fk_trade_reviews_order FOREIGN KEY (order_id) REFERENCES merchant_orders (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_reviews_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_reviews_reviewee FOREIGN KEY (reviewee_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_reviews_return_request FOREIGN KEY (return_request_id) REFERENCES return_requests (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_trade_reviews_parties CHECK (reviewer_id <> reviewee_id),
    CONSTRAINT ck_trade_reviews_content CHECK (CHAR_LENGTH(TRIM(content)) > 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE points_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    refund_id BIGINT NULL DEFAULT NULL,
    original_entry_id BIGINT NULL DEFAULT NULL,
    entry_kind VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    direction VARCHAR(8) COLLATE utf8mb4_0900_bin NOT NULL,
    quantity BIGINT NOT NULL,
    operation_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_points_entries_account_kind_operation (account_id, entry_kind, operation_key),
    KEY ix_points_entries_account_created (account_id, created_at, id),
    KEY ix_points_entries_order_id (order_id),
    KEY ix_points_entries_refund_id (refund_id),
    KEY ix_points_entries_original_entry_id (original_entry_id),
    CONSTRAINT fk_points_entries_account FOREIGN KEY (account_id) REFERENCES points_accounts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_points_entries_order FOREIGN KEY (order_id) REFERENCES merchant_orders (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_points_entries_refund FOREIGN KEY (refund_id) REFERENCES refunds (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_points_entries_original FOREIGN KEY (original_entry_id) REFERENCES points_entries (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_points_entries_quantity CHECK (quantity > 0),
    CONSTRAINT ck_points_entries_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_points_entries_operation CHECK (
        CHAR_LENGTH(TRIM(entry_kind)) > 0 AND CHAR_LENGTH(TRIM(operation_key)) > 0
    ),
    CONSTRAINT ck_points_entries_refund_original CHECK (
        refund_id IS NULL OR original_entry_id IS NOT NULL
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SELECT TABLE_NAME, ENGINE, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'campus_market_dev'
  AND TABLE_NAME IN ('return_requests', 'escrows', 'product_reviews', 'settlements', 'refunds', 'trade_reviews', 'points_entries')
ORDER BY TABLE_NAME;

SHOW CREATE TABLE return_requests;
SHOW CREATE TABLE escrows;
SHOW CREATE TABLE product_reviews;
SHOW CREATE TABLE settlements;
SHOW CREATE TABLE refunds;
SHOW CREATE TABLE trade_reviews;
SHOW CREATE TABLE points_entries;
