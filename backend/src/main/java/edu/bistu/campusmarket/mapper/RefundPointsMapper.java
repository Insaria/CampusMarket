package edu.bistu.campusmarket.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RefundPointsMapper {
    @Select("""
            SELECT id FROM refunds
            WHERE id > #{afterId} AND completed_at IS NULL AND refund_status IN ('CASH_REFUNDED', 'PENDING_POINTS')
            ORDER BY id LIMIT #{limit}
            """)
    List<Long> findCandidates(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select("""
            SELECT rr.order_id, rr.id AS request_id
            FROM refunds r JOIN return_requests rr ON rr.id = r.return_request_id WHERE r.id = #{refundId}
            """)
    Target findTarget(@Param("refundId") long refundId);

    @Select("""
            SELECT refund_status, cash_refund_amount, buyer_credit_entry_id, points_restored, points_reversed,
                   failure_reason, completed_at
            FROM refunds WHERE id = #{refundId} AND return_request_id = #{requestId} FOR UPDATE
            """)
    Refund lockRefund(@Param("refundId") long refundId, @Param("requestId") long requestId);

    @Select("SELECT points_used FROM merchant_orders WHERE id = #{orderId}")
    long pointsUsed(@Param("orderId") long orderId);

    @Select("SELECT id, balance FROM points_accounts WHERE user_id = #{buyerId} FOR UPDATE")
    Account lockAccount(@Param("buyerId") long buyerId);

    @Select("""
            SELECT id, account_id, order_id, refund_id, original_entry_id, entry_kind, direction, quantity, operation_key
            FROM points_entries WHERE order_id = #{orderId} AND refund_id IS NULL ORDER BY id
            """)
    List<Entry> findOriginals(@Param("orderId") long orderId);

    @Select("""
            SELECT p.id, p.account_id, p.order_id, p.refund_id, p.original_entry_id,
                   p.entry_kind, p.direction, p.quantity, p.operation_key
            FROM points_entries p
            WHERE p.refund_id = #{refundId}
                OR (p.refund_id IS NOT NULL AND p.original_entry_id IN (
                    SELECT src.id FROM points_entries src WHERE src.order_id = #{orderId} AND src.refund_id IS NULL
                ))
            ORDER BY p.id
            """)
    List<Entry> findAdjustments(@Param("orderId") long orderId, @Param("refundId") long refundId);

    @Update("""
            UPDATE points_accounts SET balance = balance + #{quantity}
            WHERE id = #{accountId} AND balance <= 9223372036854775807 - #{quantity}
            """)
    int restoreBalance(@Param("accountId") long accountId, @Param("quantity") long quantity);

    @Update("""
            UPDATE points_accounts SET balance = balance - #{quantity}
            WHERE id = #{accountId} AND balance >= #{quantity}
            """)
    int reverseBalance(@Param("accountId") long accountId, @Param("quantity") long quantity);

    @Insert("""
            INSERT INTO points_entries (account_id, order_id, refund_id, original_entry_id,
                entry_kind, direction, quantity, operation_key)
            VALUES (#{entry.accountId}, #{entry.orderId}, #{entry.refundId}, #{entry.originalEntryId},
                #{entry.entryKind}, #{entry.direction}, #{entry.quantity}, #{entry.operationKey})
            """)
    int recordAdjustment(@Param("entry") Entry entry);

    @Update("""
            UPDATE refunds SET points_restored = #{restored}, points_reversed = #{reversed},
                failure_reason = #{failure,jdbcType=VARCHAR},
                refund_status = CASE WHEN #{done} THEN 'COMPLETED' ELSE refund_status END,
                completed_at = CASE WHEN #{done} THEN UTC_TIMESTAMP(3) ELSE completed_at END
            WHERE id = #{refundId} AND completed_at IS NULL AND refund_status IN ('CASH_REFUNDED', 'PENDING_POINTS')
            """)
    int recordProgress(@Param("refundId") long refundId, @Param("restored") long restored,
            @Param("reversed") long reversed, @Param("failure") String failure, @Param("done") boolean done);

    record Target(Long orderId, Long requestId) {
    }

    record Refund(String refundStatus, BigDecimal cashRefundAmount, Long buyerCreditEntryId, Long pointsRestored,
            Long pointsReversed, String failureReason, LocalDateTime completedAt) {
    }

    record Account(Long id, Long balance) {
    }

    record Entry(Long id, Long accountId, Long orderId, Long refundId, Long originalEntryId, String entryKind,
            String direction, Long quantity, String operationKey) {
    }
}
