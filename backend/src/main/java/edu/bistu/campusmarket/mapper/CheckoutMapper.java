package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.CheckoutDtos.Order;
import edu.bistu.campusmarket.dto.CheckoutDtos.PendingBatch;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface CheckoutMapper {
    String PENDING_BUYER = """
            WHERE b.buyer_id = #{buyerId} AND b.total_wallet_debit_amount = 0
                AND EXISTS (SELECT 1 FROM merchant_orders o JOIN order_items i ON i.order_id = o.id
                            WHERE o.batch_id = b.id)
                AND NOT EXISTS (SELECT 1 FROM merchant_orders o
                                WHERE o.batch_id = b.id AND o.order_status <> 'PENDING_PAYMENT')
            """;

    @Select("""
            SELECT id, total_wallet_debit_amount, total_points_used, total_points_discount_amount
            FROM checkout_batches WHERE id = #{batchId} AND buyer_id = #{buyerId}
            """)
    BalanceSnapshot findOwnedBatch(@Param("batchId") long batchId, @Param("buyerId") long buyerId);

    @Select("SELECT COUNT(*) FROM checkout_batches b " + PENDING_BUYER)
    long countPending(@Param("buyerId") long buyerId);

    @Select("""
            SELECT b.id AS batch_id, b.created_at, COUNT(DISTINCT o.id) AS order_count,
                   SUM(i.unit_price * i.quantity) AS quoted_amount
            FROM checkout_batches b JOIN merchant_orders o ON o.batch_id = b.id
            JOIN order_items i ON i.order_id = o.id
            """ + PENDING_BUYER + """
            GROUP BY b.id, b.created_at ORDER BY b.created_at DESC, b.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<PendingBatch> pending(@Param("buyerId") long buyerId, @Param("size") int size, @Param("offset") long offset);

    @Select("""
            SELECT id FROM users WHERE id = #{buyerId} AND role_code = 'USER'
                AND registration_status = 'APPROVED' AND account_status = 'ENABLED' FOR UPDATE
            """)
    Long lockBuyer(@Param("buyerId") long buyerId);

    @Select("""
            SELECT id, request_digest FROM checkout_batches
            WHERE buyer_id = #{buyerId} AND request_key = #{requestKey} FOR UPDATE
            """)
    Batch findBatch(@Param("buyerId") long buyerId, @Param("requestKey") String requestKey);

    @Select("SELECT id FROM carts WHERE user_id = #{buyerId} FOR UPDATE")
    Long lockCart(@Param("buyerId") long buyerId);

    @Select("SELECT quantity FROM cart_items WHERE cart_id = #{cartId} AND product_id = #{productId} FOR UPDATE")
    Integer lockCartQuantity(@Param("cartId") long cartId, @Param("productId") long productId);

    @Select("""
            SELECT p.id, p.merchant_id, p.name, COALESCE(p.discount_price, p.original_price) AS price,
                   p.available_quantity, p.reserved_quantity
            """ + CatalogMapper.VISIBLE + " AND p.id = #{productId} FOR UPDATE")
    Product lockSellableProduct(@Param("productId") long productId);

    @Insert("""
            INSERT INTO checkout_batches (buyer_id, request_key, request_digest, total_payable_amount,
                total_points_used, total_points_discount_amount, total_wallet_debit_amount)
            VALUES (#{buyerId}, #{requestKey}, #{digest}, 0, 0, 0, 0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int createBatch(@Param("buyerId") long buyerId, @Param("requestKey") String requestKey,
            @Param("digest") byte[] digest, @Param("key") GeneratedId key);

    @Insert("""
            INSERT INTO merchant_orders (batch_id, merchant_id, order_no, order_status, payable_amount,
                points_used, points_discount_amount, wallet_debit_amount, initial_escrow_amount)
            VALUES (#{batchId}, #{sellerId}, #{orderNo}, #{status}, 0, 0, 0, 0, 0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int createOrder(@Param("batchId") long batchId, @Param("sellerId") long sellerId,
            @Param("orderNo") String orderNo, @Param("status") String status, @Param("key") GeneratedId key);

    @Insert("""
            INSERT INTO order_items (order_id, product_id, product_name_snapshot, unit_price, quantity, reserved_quantity)
            VALUES (#{orderId}, #{product.id}, #{product.name}, #{product.price}, #{quantity}, #{quantity})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int createItem(@Param("orderId") long orderId, @Param("product") Product product,
            @Param("quantity") int quantity, @Param("key") GeneratedId key);

    @Update("""
            UPDATE products SET available_quantity = available_quantity - #{quantity},
                                reserved_quantity = reserved_quantity + #{quantity}
            WHERE id = #{productId} AND available_quantity >= #{quantity}
                AND reserved_quantity <= 2147483647 - #{quantity}
            """)
    int reserve(@Param("productId") long productId, @Param("quantity") int quantity);

    @Insert("""
            INSERT INTO order_status_events (order_id, event_seq, operation_key, to_status, actor_type, actor_user_id)
            VALUES (#{orderId}, 1, 'CHECKOUT_CREATED', #{status}, 'USER', #{buyerId})
            """)
    int initialEvent(@Param("orderId") long orderId, @Param("status") String status, @Param("buyerId") long buyerId);

    @Insert("""
            INSERT INTO inventory_events (product_id, order_item_id, action_code, operation_key,
                available_delta, reserved_delta, source_code, actor_user_id)
            VALUES (#{productId}, #{itemId}, 'RESERVE', CONCAT('CHECKOUT:', #{batchId}),
                -#{quantity}, #{quantity}, 'CHECKOUT', #{buyerId})
            """)
    int inventoryEvent(@Param("productId") long productId, @Param("itemId") long itemId,
            @Param("batchId") long batchId, @Param("quantity") int quantity, @Param("buyerId") long buyerId);

    @Delete("DELETE FROM cart_items WHERE cart_id = #{cartId} AND product_id = #{productId}")
    int removeCheckedOutItem(@Param("cartId") long cartId, @Param("productId") long productId);

    @Select("""
            SELECT o.id AS order_id, o.merchant_id AS seller_id, o.order_no, o.order_status,
                   SUM(i.unit_price * i.quantity) AS quoted_amount
            FROM merchant_orders o JOIN order_items i ON i.order_id = o.id
            WHERE o.batch_id = #{batchId}
            GROUP BY o.id, o.merchant_id, o.order_no, o.order_status
            ORDER BY o.merchant_id
            """)
    List<Order> orders(@Param("batchId") long batchId);

    record BalanceSnapshot(Long id, BigDecimal totalWalletDebitAmount, Long totalPointsUsed,
            BigDecimal totalPointsDiscountAmount) {
    }

    record Batch(Long id, byte[] requestDigest) {
    }

    record Product(Long id, Long merchantId, String name, BigDecimal price,
            Integer availableQuantity, Integer reservedQuantity) {
    }
}
