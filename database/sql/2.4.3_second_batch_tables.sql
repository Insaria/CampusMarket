USE campus_market_dev;
SET SESSION time_zone = '+00:00';

CREATE TABLE favorites (
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, product_id),
    KEY ix_favorites_product_id (product_id),
    KEY ix_favorites_user_created (user_id, created_at, product_id),
    CONSTRAINT fk_favorites_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_favorites_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE carts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_carts_user_id (user_id),
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE cart_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    cart_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_cart_items_cart_product (cart_id, product_id),
    KEY ix_cart_items_product_id (product_id),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_cart_items_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE checkout_batches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    buyer_id BIGINT NOT NULL,
    request_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    request_digest VARBINARY(32) NOT NULL,
    total_payable_amount DECIMAL(14,2) NOT NULL,
    total_points_used BIGINT NOT NULL,
    total_points_discount_amount DECIMAL(14,2) NOT NULL,
    total_wallet_debit_amount DECIMAL(14,2) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_checkout_batches_buyer_request (buyer_id, request_key),
    KEY ix_checkout_batches_buyer_created (buyer_id, created_at, id),
    CONSTRAINT fk_checkout_batches_buyer FOREIGN KEY (buyer_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_checkout_batches_request_key CHECK (CHAR_LENGTH(TRIM(request_key)) > 0),
    CONSTRAINT ck_checkout_batches_request_digest CHECK (OCTET_LENGTH(request_digest) = 32),
    CONSTRAINT ck_checkout_batches_totals CHECK (
        total_payable_amount >= 0
        AND total_points_used >= 0
        AND total_points_discount_amount >= 0
        AND total_wallet_debit_amount >= 0
        AND total_payable_amount = total_points_discount_amount + total_wallet_debit_amount
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE wallets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    balance DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_wallets_user_id (user_id),
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_wallets_balance CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE points_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_points_accounts_user_id (user_id),
    CONSTRAINT fk_points_accounts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_points_accounts_balance CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT NOT NULL,
    notification_kind VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    event_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    target_type VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    target_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    read_at DATETIME(3) NULL DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_notifications_recipient_kind_event (recipient_id, notification_kind, event_key),
    KEY ix_notifications_recipient_created (recipient_id, created_at, id),
    KEY ix_notifications_recipient_read_created (recipient_id, read_at, created_at, id),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_notifications_event CHECK (
        CHAR_LENGTH(TRIM(notification_kind)) > 0 AND CHAR_LENGTH(TRIM(event_key)) > 0
    ),
    CONSTRAINT ck_notifications_target CHECK (CHAR_LENGTH(TRIM(target_type)) > 0 AND target_id > 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_type VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    actor_user_id BIGINT NULL DEFAULT NULL,
    system_source VARCHAR(64) COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    action_code VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    target_type VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    target_id BIGINT NOT NULL,
    details JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_audit_events_actor_created (actor_user_id, created_at, id),
    KEY ix_audit_events_target_created (target_type, target_id, created_at, id),
    CONSTRAINT fk_audit_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_audit_events_actor CHECK (
        (actor_type = 'USER' AND actor_user_id IS NOT NULL AND system_source IS NULL)
        OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL AND system_source IS NOT NULL
            AND CHAR_LENGTH(TRIM(system_source)) > 0)
    ),
    CONSTRAINT ck_audit_events_action CHECK (CHAR_LENGTH(TRIM(action_code)) > 0),
    CONSTRAINT ck_audit_events_target CHECK (CHAR_LENGTH(TRIM(target_type)) > 0 AND target_id > 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
