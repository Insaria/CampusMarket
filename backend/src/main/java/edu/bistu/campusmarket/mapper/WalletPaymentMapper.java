package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.AccountFinanceDtos.WalletEntry;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WalletPaymentMapper {
    @Select("SELECT id, balance FROM wallets WHERE user_id = #{userId}")
    Wallet findWallet(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM wallet_entries WHERE wallet_id = #{walletId}")
    long countEntries(@Param("walletId") long walletId);

    @Select("""
            SELECT e.id, e.order_id, o.order_no, e.entry_kind, e.direction, e.amount, e.created_at
            FROM wallet_entries e LEFT JOIN merchant_orders o ON o.id = e.order_id
            WHERE e.wallet_id = #{walletId}
            ORDER BY e.created_at DESC, e.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<WalletEntry> entries(@Param("walletId") long walletId, @Param("size") int size, @Param("offset") long offset);

    @Select("""
            SELECT id, total_wallet_debit_amount, total_points_used, total_points_discount_amount FROM checkout_batches
            WHERE id = #{batchId} AND buyer_id = #{buyerId} FOR UPDATE
            """)
    Batch lockBatch(@Param("batchId") long batchId, @Param("buyerId") long buyerId);

    @Select("""
            SELECT id, merchant_id, order_status FROM merchant_orders
            WHERE batch_id = #{batchId} ORDER BY merchant_id FOR UPDATE
            """)
    List<Order> lockOrders(@Param("batchId") long batchId);

    @Select("""
            SELECT event_seq FROM order_status_events
            WHERE order_id = #{orderId} AND operation_key = #{key} FOR UPDATE
            """)
    Integer paymentEvent(@Param("orderId") long orderId, @Param("key") String key);

    @Select("""
            SELECT m.id FROM merchants m JOIN users u ON u.id = m.user_id
            LEFT JOIN merchant_levels l ON l.id = m.level_id
            WHERE m.id = #{sellerId} AND u.role_code = 'USER'
                AND u.registration_status = 'APPROVED' AND u.account_status = 'ENABLED'
                AND m.review_status = 'APPROVED' AND m.operation_status = 'ACTIVE'
                AND (m.seller_type = 'PERSONAL' OR (m.seller_type = 'BUSINESS' AND l.is_enabled = 1))
            FOR UPDATE
            """)
    Long lockEligibleSeller(@Param("sellerId") long sellerId);

    @Select("SELECT unit_price, quantity FROM order_items WHERE order_id = #{orderId} ORDER BY id FOR UPDATE")
    List<Line> lockLines(@Param("orderId") long orderId);

    @Select("SELECT id, balance FROM wallets WHERE user_id = #{buyerId} FOR UPDATE")
    Wallet lockWallet(@Param("buyerId") long buyerId);

    @Update("UPDATE wallets SET balance = balance - #{amount} WHERE id = #{id} AND balance >= #{amount}")
    int debit(@Param("id") long id, @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO wallet_entries (wallet_id, order_id, entry_kind, direction, amount, operation_key)
            VALUES (#{walletId}, #{orderId}, 'ORDER_PAYMENT', 'DEBIT', #{amount}, #{operationKey})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int recordDebit(@Param("walletId") long walletId, @Param("orderId") long orderId,
            @Param("amount") BigDecimal amount, @Param("operationKey") String operationKey, @Param("key") GeneratedId key);

    @Insert("""
            INSERT INTO escrows (order_id, funding_entry_id, principal_amount)
            VALUES (#{orderId}, #{entryId}, #{amount})
            """)
    int escrow(@Param("orderId") long orderId, @Param("entryId") long entryId, @Param("amount") BigDecimal amount);

    @Update("""
            UPDATE merchant_orders SET order_status = 'PAID', payable_amount = #{payable},
                points_used = #{points}, points_discount_amount = #{discount},
                wallet_debit_amount = #{cash}, initial_escrow_amount = #{cash}
            WHERE id = #{orderId} AND order_status = 'PENDING_PAYMENT'
                AND payable_amount = 0 AND wallet_debit_amount = 0 AND initial_escrow_amount = 0
                AND points_used = 0 AND points_discount_amount = 0
            """)
    int markPaid(@Param("orderId") long orderId, @Param("payable") BigDecimal payable, @Param("cash") BigDecimal cash,
            @Param("points") long points, @Param("discount") BigDecimal discount);

    @Select("""
            SELECT event_seq FROM order_status_events WHERE order_id = #{orderId}
            ORDER BY event_seq DESC LIMIT 1 FOR UPDATE
            """)
    Integer lastEventSequence(@Param("orderId") long orderId);

    @Insert("""
            INSERT INTO order_status_events (order_id, event_seq, operation_key, from_status, to_status, actor_type, actor_user_id)
            VALUES (#{orderId}, #{sequence}, #{key}, 'PENDING_PAYMENT', 'PAID', 'USER', #{buyerId})
            """)
    int recordPayment(@Param("orderId") long orderId, @Param("sequence") int sequence,
            @Param("key") String key, @Param("buyerId") long buyerId);

    @Update("""
            UPDATE checkout_batches SET total_payable_amount = #{payable}, total_wallet_debit_amount = #{cash},
                total_points_used = #{points}, total_points_discount_amount = #{discount}
            WHERE id = #{batchId} AND total_payable_amount = 0 AND total_wallet_debit_amount = 0
                AND total_points_used = 0 AND total_points_discount_amount = 0
            """)
    int updateBatch(@Param("batchId") long batchId, @Param("payable") BigDecimal payable, @Param("cash") BigDecimal cash,
            @Param("points") long points, @Param("discount") BigDecimal discount);

    record Batch(Long id, BigDecimal totalWalletDebitAmount, Long totalPointsUsed, BigDecimal totalPointsDiscountAmount) {
    }

    record Order(Long id, Long merchantId, String orderStatus) {
    }

    record Line(BigDecimal unitPrice, Integer quantity) {
    }

    record Wallet(Long id, BigDecimal balance) {
    }
}
