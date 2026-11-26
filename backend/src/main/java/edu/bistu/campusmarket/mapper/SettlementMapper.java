package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SettlementMapper {
    @Select("""
            SELECT o.id FROM merchant_orders o
            WHERE o.id > #{afterId} AND o.order_status = 'COMPLETED'
                AND COALESCE(o.buyer_confirmed_at, o.auto_confirmed_at) <= UTC_TIMESTAMP(3) - INTERVAL 24 HOUR
                AND NOT EXISTS (
                    SELECT 1 FROM escrows e JOIN settlements s ON s.escrow_id = e.id WHERE e.order_id = o.id
                )
            ORDER BY o.id LIMIT #{limit}
            """)
    List<Long> findDueOrders(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select("""
            SELECT id, batch_id, merchant_id, order_status, buyer_confirmed_at, auto_confirmed_at,
                   wallet_debit_amount, initial_escrow_amount
            FROM merchant_orders WHERE id = #{orderId} FOR UPDATE
            """)
    Order lockOrder(@Param("orderId") long orderId);

    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime utcNow();

    @Select("SELECT UTC_TIMESTAMP(3) - INTERVAL 24 HOUR")
    LocalDateTime settlementCutoff();

    @Select("""
            SELECT id, funding_entry_id, principal_amount, released_amount, returned_amount
            FROM escrows WHERE order_id = #{orderId} FOR UPDATE
            """)
    Escrow lockEscrow(@Param("orderId") long orderId);

    @Select("""
            SELECT id, level_id_snapshot, level_code_snapshot, fee_rate_snapshot, fee_rate_applied_at,
                   fee_base_amount, released_amount, fee_amount, seller_net_amount, settled_at
            FROM settlements WHERE escrow_id = #{escrowId} FOR UPDATE
            """)
    Settlement findSettlement(@Param("escrowId") long escrowId);

    @Select("SELECT id FROM return_requests WHERE active_order_key = #{orderId} FOR UPDATE")
    Long lockActiveReturnId(@Param("orderId") long orderId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM return_requests rr JOIN refunds r ON r.return_request_id = rr.id
                WHERE rr.order_id = #{orderId} AND r.completed_at IS NULL
            )
            """)
    boolean hasUnfinishedRefund(@Param("orderId") long orderId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM wallet_entries w JOIN wallets bw ON bw.id = w.wallet_id
                JOIN merchant_orders o ON o.id = w.order_id
                JOIN checkout_batches b ON b.id = o.batch_id AND b.buyer_id = bw.user_id
                WHERE w.id = #{entryId} AND w.order_id = #{orderId}
                    AND w.entry_kind = 'ORDER_PAYMENT' AND w.direction = 'DEBIT' AND w.amount = #{principal}
            )
            """)
    boolean hasMatchingBuyerDebit(@Param("entryId") long entryId, @Param("orderId") long orderId,
            @Param("principal") BigDecimal principal);

    @Select("SELECT user_id, seller_type, level_id FROM merchants WHERE id = #{merchantId} FOR UPDATE")
    Seller lockSeller(@Param("merchantId") long merchantId);

    @Select("SELECT id, level_code, fee_rate FROM merchant_levels WHERE id = #{levelId} AND is_enabled = 1 FOR UPDATE")
    Level lockEnabledLevel(@Param("levelId") long levelId);

    @Update("""
            UPDATE wallets SET balance = balance + #{amount}
            WHERE id = #{walletId} AND balance <= 999999999999.99 - #{amount}
            """)
    int creditWallet(@Param("walletId") long walletId, @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO wallet_entries (wallet_id, order_id, entry_kind, direction, amount, operation_key)
            VALUES (#{walletId}, #{orderId}, 'SELLER_SETTLEMENT', 'CREDIT', #{amount}, CONCAT('SETTLEMENT:', #{orderId}))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int recordSellerCredit(@Param("walletId") long walletId, @Param("orderId") long orderId,
            @Param("amount") BigDecimal amount, @Param("key") GeneratedId key);

    @Insert("""
            INSERT INTO settlements (escrow_id, seller_credit_entry_id, level_id_snapshot, level_code_snapshot,
                fee_rate_snapshot, fee_rate_applied_at, fee_base_amount, released_amount,
                fee_amount, seller_net_amount, settled_at)
            VALUES (#{escrowId}, #{creditEntryId}, #{terms.levelId,jdbcType=BIGINT}, #{terms.levelCode,jdbcType=TINYINT},
                #{terms.rate}, #{terms.appliedAt}, #{terms.principal}, #{terms.principal},
                #{terms.fee}, #{terms.net}, UTC_TIMESTAMP(3))
            """)
    int insertSettlement(@Param("escrowId") long escrowId, @Param("creditEntryId") long creditEntryId,
            @Param("terms") Terms terms);

    @Update("""
            UPDATE escrows SET released_amount = principal_amount
            WHERE id = #{escrowId} AND released_amount = 0 AND returned_amount = 0
            """)
    int releaseEscrow(@Param("escrowId") long escrowId);

    record Order(Long id, Long batchId, Long merchantId, String orderStatus, LocalDateTime buyerConfirmedAt,
            LocalDateTime autoConfirmedAt, BigDecimal walletDebitAmount, BigDecimal initialEscrowAmount) {
    }

    record Escrow(Long id, Long fundingEntryId, BigDecimal principalAmount,
            BigDecimal releasedAmount, BigDecimal returnedAmount) {
    }

    record Seller(Long userId, String sellerType, Long levelId) {
    }

    record Level(Long id, Integer levelCode, BigDecimal feeRate) {
    }

    record Terms(Long levelId, Integer levelCode, BigDecimal rate, LocalDateTime appliedAt,
            BigDecimal principal, BigDecimal fee, BigDecimal net) {
    }

    record Settlement(Long id, Long levelIdSnapshot, Integer levelCodeSnapshot, BigDecimal feeRateSnapshot,
            LocalDateTime feeRateAppliedAt, BigDecimal feeBaseAmount, BigDecimal releasedAmount, BigDecimal feeAmount,
            BigDecimal sellerNetAmount, LocalDateTime settledAt) {
    }
}
