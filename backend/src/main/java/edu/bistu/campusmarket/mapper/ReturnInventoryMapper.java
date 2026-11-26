package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.ReturnRequestDtos.RestockedItem;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReturnInventoryMapper {
    @Select("""
            SELECT id, product_id, consumed_quantity, restocked_quantity
            FROM order_items WHERE order_id = #{orderId} ORDER BY product_id FOR UPDATE
            """)
    List<Item> lockItems(@Param("orderId") long orderId);

    @Update("""
            UPDATE products SET available_quantity = available_quantity + #{quantity}
            WHERE id = #{productId} AND merchant_id = #{merchantId} AND deleted_at IS NULL
                AND available_quantity <= 2147483647 - #{quantity}
            """)
    int restoreAvailable(@Param("productId") long productId, @Param("merchantId") long merchantId,
            @Param("quantity") int quantity);

    @Update("""
            UPDATE order_items SET restocked_quantity = restocked_quantity + #{quantity}
            WHERE id = #{itemId} AND consumed_quantity - restocked_quantity = #{quantity}
            """)
    int recordRestockedQuantity(@Param("itemId") long itemId, @Param("quantity") int quantity);

    @Insert("""
            INSERT INTO inventory_events (product_id, order_item_id, action_code, operation_key,
                available_delta, reserved_delta, source_code, actor_user_id)
            VALUES (#{productId}, #{itemId}, 'RESTOCK', CONCAT('RETURN_RESTOCK:', #{requestId}, ':', #{itemId}),
                #{quantity}, 0, 'RETURN_RECEIVE', #{sellerUserId})
            """)
    int recordRestock(@Param("productId") long productId, @Param("itemId") long itemId,
            @Param("requestId") long requestId, @Param("quantity") int quantity, @Param("sellerUserId") long sellerUserId);

    @Select("""
            SELECT i.id AS order_item_id, i.product_id, e.available_delta AS quantity
            FROM order_items i JOIN inventory_events e ON e.order_item_id = i.id AND e.product_id = i.product_id
            WHERE i.order_id = #{orderId} AND e.action_code = 'RESTOCK'
                AND e.operation_key = CONCAT('RETURN_RESTOCK:', #{requestId}, ':', i.id)
            ORDER BY i.product_id
            """)
    List<RestockedItem> findRecordedRestocks(@Param("orderId") long orderId, @Param("requestId") long requestId);

    record Item(Long id, Long productId, Integer consumedQuantity, Integer restockedQuantity) {
    }
}
