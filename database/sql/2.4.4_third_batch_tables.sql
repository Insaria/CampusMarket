USE campus_market_dev;
SET SESSION time_zone = '+00:00';

CREATE TABLE merchant_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    order_no VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    order_status VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    payable_amount DECIMAL(14,2) NOT NULL,
    points_used BIGINT NOT NULL,
    points_discount_amount DECIMAL(14,2) NOT NULL,
    wallet_debit_amount DECIMAL(14,2) NOT NULL,
    initial_escrow_amount DECIMAL(14,2) NOT NULL,
    scheduled_trade_at DATETIME(3) NULL DEFAULT NULL,
    trade_location VARCHAR(255) NULL DEFAULT NULL,
    delivered_at DATETIME(3) NULL DEFAULT NULL,
    buyer_confirmed_at DATETIME(3) NULL DEFAULT NULL,
    auto_confirmed_at DATETIME(3) NULL DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_merchant_orders_batch_merchant (batch_id, merchant_id),
    UNIQUE KEY uq_merchant_orders_order_no (order_no),
    KEY ix_merchant_orders_merchant_status_created (merchant_id, order_status, created_at, id),
    CONSTRAINT fk_merchant_orders_batch FOREIGN KEY (batch_id) REFERENCES checkout_batches (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_merchant_orders_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_merchant_orders_identifiers CHECK (
        CHAR_LENGTH(TRIM(order_no)) > 0 AND CHAR_LENGTH(TRIM(order_status)) > 0
    ),
    CONSTRAINT ck_merchant_orders_amounts CHECK (
        payable_amount >= 0
        AND points_used >= 0
        AND points_discount_amount >= 0
        AND wallet_debit_amount >= 0
        AND initial_escrow_amount = wallet_debit_amount
        AND payable_amount = points_discount_amount + wallet_debit_amount
    ),
    CONSTRAINT ck_merchant_orders_confirmation CHECK (
        buyer_confirmed_at IS NULL OR auto_confirmed_at IS NULL
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name_snapshot VARCHAR(120) NOT NULL,
    unit_price DECIMAL(14,2) NOT NULL,
    quantity INT NOT NULL,
    reserved_quantity INT NOT NULL DEFAULT 0,
    consumed_quantity INT NOT NULL DEFAULT 0,
    released_quantity INT NOT NULL DEFAULT 0,
    restocked_quantity INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_order_items_order_product (order_id, product_id),
    KEY ix_order_items_product_id (product_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES merchant_orders (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_order_items_sale CHECK (quantity > 0 AND unit_price >= 0),
    CONSTRAINT ck_order_items_stock_totals CHECK (
        reserved_quantity BETWEEN 0 AND quantity
        AND consumed_quantity >= 0
        AND released_quantity >= 0
        AND consumed_quantity + released_quantity <= reserved_quantity
        AND restocked_quantity BETWEEN 0 AND consumed_quantity
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE order_status_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    event_seq INT NOT NULL,
    operation_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    from_status VARCHAR(32) COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    to_status VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    actor_type VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    actor_user_id BIGINT NULL DEFAULT NULL,
    system_source VARCHAR(64) COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    reason VARCHAR(255) NULL DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_order_status_events_order_seq (order_id, event_seq),
    UNIQUE KEY uq_order_status_events_order_operation (order_id, operation_key),
    KEY ix_order_status_events_actor_created (actor_user_id, created_at, id),
    CONSTRAINT fk_order_status_events_order FOREIGN KEY (order_id) REFERENCES merchant_orders (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_order_status_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_order_status_events_transition CHECK (
        CHAR_LENGTH(TRIM(operation_key)) > 0
        AND CHAR_LENGTH(TRIM(to_status)) > 0
        AND ((event_seq = 1 AND from_status IS NULL)
            OR (event_seq > 1 AND from_status IS NOT NULL AND CHAR_LENGTH(TRIM(from_status)) > 0))
    ),
    CONSTRAINT ck_order_status_events_actor CHECK (
        (actor_type = 'USER' AND actor_user_id IS NOT NULL AND system_source IS NULL)
        OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL AND system_source IS NOT NULL
            AND CHAR_LENGTH(TRIM(system_source)) > 0)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE inventory_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    order_item_id BIGINT NULL DEFAULT NULL,
    action_code VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    operation_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    available_delta INT NOT NULL,
    reserved_delta INT NOT NULL,
    source_code VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    actor_user_id BIGINT NULL DEFAULT NULL,
    reason VARCHAR(255) NULL DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_inventory_events_product_action_operation (product_id, action_code, operation_key),
    KEY ix_inventory_events_product_created (product_id, created_at, id),
    KEY ix_inventory_events_order_item_id (order_item_id),
    KEY ix_inventory_events_actor_user_id (actor_user_id),
    CONSTRAINT fk_inventory_events_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inventory_events_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inventory_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_inventory_events_source CHECK (
        CHAR_LENGTH(TRIM(operation_key)) > 0 AND CHAR_LENGTH(TRIM(source_code)) > 0
    ),
    CONSTRAINT ck_inventory_events_stock_change CHECK (
        (order_item_id IS NULL AND reserved_delta = 0
            AND ((action_code = 'INITIAL' AND available_delta > 0)
                OR (action_code = 'ADJUST' AND available_delta <> 0)))
        OR (order_item_id IS NOT NULL AND (
            (action_code = 'RESERVE' AND available_delta < 0 AND reserved_delta = -available_delta)
            OR (action_code = 'CONSUME' AND available_delta = 0 AND reserved_delta < 0)
            OR (action_code = 'RELEASE' AND available_delta > 0 AND reserved_delta = -available_delta)
            OR (action_code = 'RESTOCK' AND available_delta > 0 AND reserved_delta = 0)
        ))
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE wallet_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    wallet_id BIGINT NOT NULL,
    order_id BIGINT NULL DEFAULT NULL,
    original_entry_id BIGINT NULL DEFAULT NULL,
    entry_kind VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    direction VARCHAR(8) COLLATE utf8mb4_0900_bin NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    operation_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_wallet_entries_wallet_kind_operation (wallet_id, entry_kind, operation_key),
    KEY ix_wallet_entries_wallet_created (wallet_id, created_at, id),
    KEY ix_wallet_entries_order_id (order_id),
    KEY ix_wallet_entries_original_entry_id (original_entry_id),
    CONSTRAINT fk_wallet_entries_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_wallet_entries_order FOREIGN KEY (order_id) REFERENCES merchant_orders (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_wallet_entries_original FOREIGN KEY (original_entry_id) REFERENCES wallet_entries (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_wallet_entries_amount CHECK (amount > 0),
    CONSTRAINT ck_wallet_entries_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_wallet_entries_operation CHECK (
        CHAR_LENGTH(TRIM(entry_kind)) > 0 AND CHAR_LENGTH(TRIM(operation_key)) > 0
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SELECT TABLE_NAME, ENGINE, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'campus_market_dev'
  AND TABLE_NAME IN ('merchant_orders', 'order_items', 'order_status_events', 'inventory_events', 'wallet_entries')
ORDER BY TABLE_NAME;

SHOW CREATE TABLE merchant_orders;
SHOW CREATE TABLE order_items;
SHOW CREATE TABLE order_status_events;
SHOW CREATE TABLE inventory_events;
SHOW CREATE TABLE wallet_entries;
