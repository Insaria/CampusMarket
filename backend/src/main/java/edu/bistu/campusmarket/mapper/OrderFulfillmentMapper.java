package edu.bistu.campusmarket.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OrderFulfillmentMapper {
    @Select("""
            SELECT id, batch_id, merchant_id, order_status, delivered_at, buyer_confirmed_at, auto_confirmed_at
            FROM merchant_orders WHERE id = #{orderId} FOR UPDATE
            """)
    Order lockOrder(@Param("orderId") long orderId);

    @Select("SELECT user_id FROM merchants WHERE id = #{merchantId}")
    Long findSellerUserId(@Param("merchantId") long merchantId);

    @Select("SELECT buyer_id FROM checkout_batches WHERE id = #{batchId}")
    Long findBuyerId(@Param("batchId") long batchId);

    @Select("""
            SELECT id, product_id, reserved_quantity - consumed_quantity - released_quantity AS remaining_quantity
            FROM order_items WHERE order_id = #{orderId} ORDER BY product_id FOR UPDATE
            """)
    List<Reservation> lockReservations(@Param("orderId") long orderId);

    @Update("""
            UPDATE products SET reserved_quantity = reserved_quantity - #{quantity}
            WHERE id = #{productId} AND reserved_quantity >= #{quantity}
            """)
    int consumeReservedStock(@Param("productId") long productId, @Param("quantity") int quantity);

    @Update("""
            UPDATE order_items SET consumed_quantity = consumed_quantity + #{quantity}
            WHERE id = #{itemId} AND reserved_quantity - consumed_quantity - released_quantity = #{quantity}
            """)
    int recordConsumedQuantity(@Param("itemId") long itemId, @Param("quantity") int quantity);

    @Insert("""
            INSERT INTO inventory_events (product_id, order_item_id, action_code, operation_key,
                available_delta, reserved_delta, source_code, actor_user_id)
            VALUES (#{productId}, #{itemId}, 'CONSUME', CONCAT('ORDER_CONSUME:', #{itemId}),
                0, -#{quantity}, 'SELLER_DELIVERY', #{sellerUserId})
            """)
    int recordStockConsumption(@Param("productId") long productId, @Param("itemId") long itemId,
            @Param("quantity") int quantity, @Param("sellerUserId") long sellerUserId);

    @Update("""
            UPDATE merchant_orders SET order_status = 'DELIVERED', delivered_at = UTC_TIMESTAMP(3)
            WHERE id = #{orderId} AND order_status = 'PAID' AND delivered_at IS NULL
                AND buyer_confirmed_at IS NULL AND auto_confirmed_at IS NULL
            """)
    int markDelivered(@Param("orderId") long orderId);

    @Update("""
            UPDATE merchant_orders SET order_status = 'COMPLETED', buyer_confirmed_at = UTC_TIMESTAMP(3)
            WHERE id = #{orderId} AND order_status = 'DELIVERED' AND delivered_at IS NOT NULL
                AND buyer_confirmed_at IS NULL AND auto_confirmed_at IS NULL
            """)
    int markBuyerConfirmed(@Param("orderId") long orderId);

    @Select("SELECT UTC_TIMESTAMP(3) - INTERVAL 168 HOUR")
    LocalDateTime autoConfirmationCutoff();

    @Select("""
            SELECT id FROM merchant_orders
            WHERE id > #{afterId} AND order_status = 'DELIVERED' AND delivered_at <= #{cutoff}
                AND buyer_confirmed_at IS NULL AND auto_confirmed_at IS NULL
            ORDER BY id LIMIT #{limit}
            """)
    List<Long> findOrdersDueForAutoConfirmation(@Param("afterId") long afterId,
            @Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Update("""
            UPDATE merchant_orders SET order_status = 'COMPLETED', auto_confirmed_at = UTC_TIMESTAMP(3)
            WHERE id = #{orderId} AND order_status = 'DELIVERED' AND delivered_at <= #{cutoff}
                AND buyer_confirmed_at IS NULL AND auto_confirmed_at IS NULL
            """)
    int markAutoConfirmed(@Param("orderId") long orderId, @Param("cutoff") LocalDateTime cutoff);

    @Insert("""
            INSERT INTO order_status_events (order_id, event_seq, operation_key, from_status,
                to_status, actor_type, actor_user_id, system_source)
            VALUES (#{orderId}, #{sequence}, 'AUTO_CONFIRM', 'DELIVERED', 'COMPLETED', 'SYSTEM', NULL, 'AUTO_CONFIRM')
            """)
    int recordAutoConfirmation(@Param("orderId") long orderId, @Param("sequence") int sequence);

    @Insert("""
            INSERT INTO order_status_events (order_id, event_seq, operation_key, from_status,
                to_status, actor_type, actor_user_id)
            VALUES (#{orderId}, #{sequence}, #{key}, #{fromStatus}, #{toStatus}, 'USER', #{userId})
            """)
    int recordTransition(@Param("orderId") long orderId, @Param("sequence") int sequence, @Param("key") String key,
            @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus, @Param("userId") long userId);

    record Order(Long id, Long batchId, Long merchantId, String orderStatus,
            LocalDateTime deliveredAt, LocalDateTime buyerConfirmedAt, LocalDateTime autoConfirmedAt) {
    }

    record Reservation(Long id, Long productId, Integer remainingQuantity) {
    }
}
