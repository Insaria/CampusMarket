package edu.bistu.campusmarket.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OrderCancellationMapper {
    @Select("""
            SELECT id, product_id, reserved_quantity, consumed_quantity, released_quantity
            FROM order_items WHERE order_id = #{orderId} ORDER BY id FOR UPDATE
            """)
    List<Item> lockItems(@Param("orderId") long orderId);

    @Update("""
            UPDATE products SET available_quantity = available_quantity + #{quantity},
                                reserved_quantity = reserved_quantity - #{quantity}
            WHERE id = #{productId} AND reserved_quantity >= #{quantity}
                AND available_quantity <= 2147483647 - #{quantity}
            """)
    int releaseStock(@Param("productId") long productId, @Param("quantity") int quantity);

    @Update("""
            UPDATE order_items SET released_quantity = released_quantity + #{quantity}
            WHERE id = #{itemId} AND consumed_quantity = 0
                AND reserved_quantity - released_quantity = #{quantity}
            """)
    int recordReleasedQuantity(@Param("itemId") long itemId, @Param("quantity") int quantity);

    @Insert("""
            INSERT INTO inventory_events (product_id, order_item_id, action_code, operation_key,
                available_delta, reserved_delta, source_code, actor_user_id)
            VALUES (#{productId}, #{itemId}, 'RELEASE', CONCAT('ORDER_RELEASE:', #{itemId}),
                #{quantity}, -#{quantity}, 'BUYER_CANCEL', #{buyerId})
            """)
    int recordStockRelease(@Param("productId") long productId, @Param("itemId") long itemId,
            @Param("quantity") int quantity, @Param("buyerId") long buyerId);

    @Update("""
            UPDATE merchant_orders SET order_status = #{cancelledStatus}
            WHERE id = #{orderId} AND order_status = 'PENDING_PAYMENT'
                AND payable_amount = 0 AND wallet_debit_amount = 0 AND initial_escrow_amount = 0
                AND points_used = 0 AND points_discount_amount = 0
            """)
    int markCancelled(@Param("orderId") long orderId, @Param("cancelledStatus") String cancelledStatus);

    @Insert("""
            INSERT INTO order_status_events (order_id, event_seq, operation_key, from_status,
                to_status, actor_type, actor_user_id)
            VALUES (#{orderId}, #{sequence}, 'BUYER_CANCEL', 'PENDING_PAYMENT',
                #{cancelledStatus}, 'USER', #{buyerId})
            """)
    int recordCancellation(@Param("orderId") long orderId, @Param("sequence") int sequence,
            @Param("cancelledStatus") String cancelledStatus, @Param("buyerId") long buyerId);

    record Item(Long id, Long productId, Integer reservedQuantity, Integer consumedQuantity, Integer releasedQuantity) {
    }
}
