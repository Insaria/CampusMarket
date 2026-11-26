package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RefundMapper {
    @Select("""
            SELECT id, escrow_id, original_settlement_id, buyer_credit_entry_id, seller_recovery_entry_id,
                   refund_status, cash_refund_amount, failure_reason, completed_at
            FROM refunds WHERE return_request_id = #{requestId} FOR UPDATE
            """)
    Refund lockRefund(@Param("requestId") long requestId);

    @Select("""
            SELECT id, seller_credit_entry_id, fee_base_amount, released_amount, fee_amount, seller_net_amount
            FROM settlements WHERE escrow_id = #{escrowId} FOR UPDATE
            """)
    OriginalSettlement lockOriginalSettlement(@Param("escrowId") long escrowId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM return_requests rr JOIN refunds rf ON rf.return_request_id = rr.id
                WHERE rr.order_id = #{orderId} AND rr.id <> #{requestId} AND rf.buyer_credit_entry_id IS NOT NULL
            )
            """)
    boolean hasOtherCashRefund(@Param("orderId") long orderId, @Param("requestId") long requestId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM merchant_orders o WHERE o.id = #{orderId}
                    AND o.wallet_debit_amount = 0 AND o.initial_escrow_amount = 0
                    AND o.points_used > 0 AND o.points_discount_amount > 0 AND o.payable_amount = o.points_discount_amount
                    AND NOT EXISTS (SELECT 1 FROM wallet_entries w WHERE w.order_id = o.id AND w.entry_kind = 'ORDER_PAYMENT')
            )
            """)
    boolean hasPointsOnlyFunding(@Param("orderId") long orderId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM wallet_entries WHERE id = #{entryId} AND wallet_id = #{walletId} AND order_id = #{orderId}
                    AND entry_kind = 'SELLER_SETTLEMENT' AND direction = 'CREDIT' AND amount = #{net}
            )
            """)
    boolean hasMatchingSellerCredit(@Param("entryId") long entryId, @Param("walletId") long walletId,
            @Param("orderId") long orderId, @Param("net") BigDecimal net);

    @Insert("""
            INSERT INTO refunds (return_request_id, escrow_id, original_settlement_id, refund_status, failure_reason)
            VALUES (#{requestId}, #{escrowId,jdbcType=BIGINT}, #{settlementId,jdbcType=BIGINT},
                #{status}, #{failure,jdbcType=VARCHAR})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int createRefund(@Param("requestId") long requestId, @Param("escrowId") Long escrowId,
            @Param("settlementId") Long settlementId, @Param("status") String status,
            @Param("failure") String failure, @Param("key") GeneratedId key);

    @Update("""
            UPDATE refunds SET failure_reason = 'SELLER_REFUND_BALANCE_INSUFFICIENT'
            WHERE id = #{refundId} AND refund_status = 'PENDING_CASH'
                AND completed_at IS NULL AND buyer_credit_entry_id IS NULL
            """)
    int recordInsufficientBalance(@Param("refundId") long refundId);

    @Insert("""
            INSERT INTO wallet_entries (wallet_id, order_id, original_entry_id, entry_kind, direction, amount, operation_key)
            VALUES (#{walletId}, #{orderId}, #{originalEntryId}, #{kind}, #{direction}, #{amount},
                CONCAT('REFUND:', #{orderId}))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int recordWalletEntry(@Param("walletId") long walletId, @Param("orderId") long orderId,
            @Param("originalEntryId") long originalEntryId, @Param("kind") String kind,
            @Param("direction") String direction, @Param("amount") BigDecimal amount, @Param("key") GeneratedId key);

    @Update("""
            UPDATE escrows SET returned_amount = principal_amount
            WHERE id = #{escrowId} AND released_amount = 0 AND returned_amount = 0
            """)
    int returnEscrow(@Param("escrowId") long escrowId);

    @Update("""
            UPDATE refunds SET refund_status = 'CASH_REFUNDED', cash_refund_amount = #{amount},
                buyer_credit_entry_id = #{buyerCreditId}, seller_recovery_entry_id = #{sellerRecoveryId,jdbcType=BIGINT},
                failure_reason = NULL
            WHERE id = #{refundId} AND refund_status = 'PENDING_CASH' AND cash_refund_amount = 0
                AND buyer_credit_entry_id IS NULL AND seller_recovery_entry_id IS NULL AND completed_at IS NULL
            """)
    int recordCashRefund(@Param("refundId") long refundId, @Param("amount") BigDecimal amount,
            @Param("buyerCreditId") long buyerCreditId, @Param("sellerRecoveryId") Long sellerRecoveryId);

    record Refund(Long id, Long escrowId, Long originalSettlementId, Long buyerCreditEntryId, Long sellerRecoveryEntryId,
            String refundStatus, BigDecimal cashRefundAmount, String failureReason, LocalDateTime completedAt) {
    }

    record OriginalSettlement(Long id, Long sellerCreditEntryId, BigDecimal feeBaseAmount, BigDecimal releasedAmount,
            BigDecimal feeAmount, BigDecimal sellerNetAmount) {
    }
}
