package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.ReturnRequestDtos.Item;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Status;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReturnRequestMapper {
    String FROM = """
            FROM return_requests r JOIN merchant_orders o ON o.id = r.order_id
            JOIN merchants m ON m.id = o.merchant_id
            """;
    String VIEW = """
            SELECT r.id, o.id AS order_id, o.order_no, o.order_status, o.payable_amount,
                   r.request_status, r.is_active AS active, r.reason, r.applied_at, r.review_reason, r.reviewed_at, r.returned_at
            """ + FROM;
    String SELLER_FILTER = " WHERE m.user_id = #{sellerUserId} ";
    String STATUS_FILTER = " AND (#{status,jdbcType=VARCHAR} IS NULL OR r.request_status = #{status,jdbcType=VARCHAR}) ";

    @Select("SELECT COUNT(*) " + FROM + SELLER_FILTER + STATUS_FILTER)
    long countForSeller(@Param("sellerUserId") long sellerUserId, @Param("status") Status status);

    @Select(VIEW + SELLER_FILTER + STATUS_FILTER + " ORDER BY r.applied_at DESC, r.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Item> pageForSeller(@Param("sellerUserId") long sellerUserId, @Param("status") Status status,
            @Param("size") int size, @Param("offset") long offset);

    @Select(VIEW + SELLER_FILTER + " AND r.id = #{requestId}")
    Item findForSeller(@Param("sellerUserId") long sellerUserId, @Param("requestId") long requestId);

    @Select("SELECT r.order_id " + FROM + SELLER_FILTER + " AND r.id = #{requestId}")
    Long findOrderIdForSeller(@Param("sellerUserId") long sellerUserId, @Param("requestId") long requestId);

    @Select(VIEW + " WHERE r.order_id = #{orderId} AND r.request_key = #{key} FOR UPDATE OF r")
    Item lockByRequestKey(@Param("orderId") long orderId, @Param("key") String key);

    @Select(VIEW + " WHERE r.order_id = #{orderId} AND r.id = #{requestId} FOR UPDATE OF r")
    Item lockRequest(@Param("orderId") long orderId, @Param("requestId") long requestId);

    @Insert("""
            INSERT INTO return_requests (order_id, request_key, request_status, is_active, reason, applied_at)
            VALUES (#{orderId}, #{requestKey}, 'PENDING', 1, #{reason}, #{appliedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int insert(@Param("orderId") long orderId, @Param("requestKey") String requestKey, @Param("reason") String reason,
            @Param("appliedAt") LocalDateTime appliedAt, @Param("key") GeneratedId key);

    @Update("""
            UPDATE return_requests SET request_status = #{status}, is_active = #{active},
                reviewer_user_id = #{sellerUserId}, review_reason = #{reason,jdbcType=VARCHAR},
                reviewed_at = UTC_TIMESTAMP(3)
            WHERE id = #{requestId} AND request_status = 'PENDING' AND is_active = 1
            """)
    int review(@Param("requestId") long requestId, @Param("sellerUserId") long sellerUserId,
            @Param("status") Status status, @Param("active") boolean active, @Param("reason") String reason);

    @Update("""
            UPDATE return_requests SET request_status = 'RETURNED', returned_at = UTC_TIMESTAMP(3)
            WHERE id = #{requestId} AND request_status = 'APPROVED' AND is_active = 1 AND returned_at IS NULL
            """)
    int markReturned(@Param("requestId") long requestId);

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, action_code, target_type, target_id)
            VALUES ('USER', #{sellerUserId}, #{action}, 'RETURN_REQUEST', #{requestId})
            """)
    int audit(@Param("sellerUserId") long sellerUserId, @Param("requestId") long requestId,
            @Param("action") String action);
}
