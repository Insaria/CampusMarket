package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.OrderReadDtos.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface OrderReadMapper {
    String FROM = """
            FROM merchant_orders o JOIN checkout_batches b ON b.id = o.batch_id
            JOIN merchants m ON m.id = o.merchant_id JOIN users u ON u.id = b.buyer_id
            """;
    String ORDERS = """
            SELECT o.id AS order_id, o.batch_id, o.order_no, o.order_status, o.merchant_id AS seller_id,
                   m.shop_name, b.buyer_id, u.login_name AS buyer_login_name, o.payable_amount,
                   o.wallet_debit_amount AS cash_paid, o.points_discount_amount, o.points_used,
                   o.created_at, o.scheduled_trade_at, o.trade_location, o.delivered_at,
                   o.buyer_confirmed_at, o.auto_confirmed_at, m.user_id AS seller_user_id
            """ + FROM;
    String OWNER = """
            <choose>
                <when test="seller">WHERE m.user_id = #{userId}</when>
                <otherwise>WHERE b.buyer_id = #{userId}</otherwise>
            </choose>
            """;
    String FILTER = """
            <choose>
                <when test="filter != null and filter.name() == 'RETURNS'">
                    AND EXISTS (
                        SELECT 1 FROM return_requests r LEFT JOIN refunds f ON f.return_request_id = r.id
                        WHERE r.order_id = o.id AND r.is_active = 1
                            AND r.request_status IN ('PENDING', 'APPROVED', 'RETURNED')
                            AND (r.request_status &lt;&gt; 'RETURNED' OR f.completed_at IS NULL)
                    )
                </when>
                <when test="filter != null">AND o.order_status = #{filter}</when>
            </choose>
            """;

    @Select("<script>SELECT COUNT(*) " + FROM + OWNER + FILTER + "</script>")
    long count(@Param("userId") long userId, @Param("seller") boolean seller, @Param("filter") Filter filter);

    @Select("<script>" + ORDERS + OWNER + FILTER
            + " ORDER BY o.created_at DESC, o.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Order> page(@Param("userId") long userId, @Param("seller") boolean seller, @Param("filter") Filter filter,
            @Param("size") int size, @Param("offset") long offset);

    @Select("<script>" + ORDERS + OWNER + " AND o.id = #{orderId}</script>")
    Order find(@Param("userId") long userId, @Param("seller") boolean seller, @Param("orderId") long orderId);

    @Select("""
            <script>
            SELECT id AS order_item_id, order_id, product_id, product_name_snapshot AS product_name,
                   unit_price, quantity, unit_price * quantity AS line_amount
            FROM order_items WHERE order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY order_id, id
            </script>
            """)
    List<Line> lines(@Param("orderIds") List<Long> orderIds);

    @Select("""
            SELECT event_seq, from_status, to_status, actor_type, system_source, created_at
            FROM order_status_events WHERE order_id = #{orderId} ORDER BY event_seq
            """)
    List<Event> events(@Param("orderId") long orderId);

    @Select("""
            SELECT r.id AS review_id, i.id AS order_item_id, r.rating, r.content, r.created_at
            FROM order_items i JOIN product_reviews r ON r.order_item_id = i.id
            WHERE i.order_id = #{orderId} ORDER BY i.id
            """)
    List<ItemReview> itemReviews(@Param("orderId") long orderId);

    @Select("""
            SELECT r.id AS request_id, r.request_status, r.is_active AS active, r.reason, r.applied_at,
                   r.review_reason, r.reviewed_at, r.returned_at, f.id AS refund_id, f.refund_status,
                   f.cash_refund_amount, f.points_restored, f.points_reversed, f.completed_at AS refund_completed_at,
                   CASE WHEN f.original_settlement_id IS NOT NULL THEN 'SELLER_WALLET'
                        WHEN f.escrow_id IS NOT NULL THEN 'ESCROW'
                        WHEN f.id IS NOT NULL THEN 'POINTS_ONLY' END AS funding_path
            FROM return_requests r LEFT JOIN refunds f ON f.return_request_id = r.id
            WHERE r.order_id = #{orderId} ORDER BY r.applied_at DESC, r.id DESC
            """)
    List<ReturnProgress> returns(@Param("orderId") long orderId);

    @Select("""
            SELECT r.id AS request_id, i.id AS order_item_id, i.product_id, e.available_delta AS quantity
            FROM return_requests r JOIN order_items i ON i.order_id = r.order_id
            JOIN inventory_events e ON e.order_item_id = i.id AND e.product_id = i.product_id
                AND e.action_code = 'RESTOCK' AND e.operation_key = CONCAT('RETURN_RESTOCK:', r.id, ':', i.id)
            WHERE r.order_id = #{orderId} AND r.request_status = 'RETURNED'
            ORDER BY r.id, i.id
            """)
    List<Restock> restocks(@Param("orderId") long orderId);

    @Select("""
            SELECT COALESCE(SUM(e.quantity), 0) FROM points_entries e
            JOIN points_accounts p ON p.id = e.account_id
            WHERE e.order_id = #{orderId} AND p.user_id = #{buyerId} AND e.refund_id IS NULL
                AND e.entry_kind = 'ORDER_POINTS_REWARD' AND e.direction = 'CREDIT'
            """)
    long reward(@Param("orderId") long orderId, @Param("buyerId") long buyerId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM checkout_batches b
                WHERE b.id = #{batchId} AND b.total_wallet_debit_amount = 0
                    AND NOT EXISTS (SELECT 1 FROM merchant_orders x
                                    WHERE x.batch_id = b.id AND x.order_status <> 'PENDING_PAYMENT')
            )
            """)
    boolean batchCancellable(@Param("batchId") long batchId);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM escrows e JOIN settlements s ON s.escrow_id = e.id
                           WHERE e.order_id = #{orderId})
            """)
    boolean settled(@Param("orderId") long orderId);

    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime utcNow();
}
