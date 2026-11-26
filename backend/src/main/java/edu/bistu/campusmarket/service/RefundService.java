package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.RefundResult;
import edu.bistu.campusmarket.dto.RefundResult.FundingPath;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Status;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.RefundMapper;
import edu.bistu.campusmarket.mapper.RefundMapper.OriginalSettlement;
import edu.bistu.campusmarket.mapper.RefundMapper.Refund;
import edu.bistu.campusmarket.mapper.ReturnRequestMapper;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Escrow;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Wallet;
import java.math.BigDecimal;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class RefundService {
    public static final String BALANCE_INSUFFICIENT = "SELLER_REFUND_BALANCE_INSUFFICIENT";
    private static final Logger LOG = LoggerFactory.getLogger(RefundService.class);
    private static final String PENDING_CASH = "PENDING_CASH";
    private static final String CASH_REFUNDED = "CASH_REFUNDED";
    private static final String PENDING_POINTS = "PENDING_POINTS";
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final RefundMapper refunds;
    private final ReturnRequestMapper requests;
    private final SettlementMapper settlement;
    private final OrderFulfillmentMapper ownership;
    private final WalletPaymentMapper wallets;

    public RefundService(RefundMapper refunds, ReturnRequestMapper requests, SettlementMapper settlement,
            OrderFulfillmentMapper ownership, WalletPaymentMapper wallets) {
        this.refunds = refunds;
        this.requests = requests;
        this.settlement = settlement;
        this.ownership = ownership;
        this.wallets = wallets;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RefundResult refund(long sellerUserId, long requestId) {
        Long orderId = requests.findOrderIdForSeller(sellerUserId, requestId);
        if (orderId == null) {
            throw requestNotFound();
        }
        Order order = settlement.lockOrder(orderId);
        if (order == null || !Long.valueOf(sellerUserId).equals(ownership.findSellerUserId(order.merchantId()))) {
            throw requestNotFound();
        }
        var request = requests.lockRequest(orderId, requestId);
        if (request == null) {
            throw requestNotFound();
        }
        if (!Status.isApproved(request.requestStatus())) {
            throw conflict("RETURN_NOT_APPROVED", "退货申请尚未获批");
        }

        Escrow escrow = settlement.lockEscrow(orderId);
        OriginalSettlement original = escrow == null ? null : refunds.lockOriginalSettlement(escrow.id());
        Refund existing = refunds.lockRefund(requestId);
        if (existing != null && existing.buyerCreditEntryId() != null) {
            return originalResult(order, escrow, original, existing);
        }
        if (!request.active() || !"COMPLETED".equals(order.orderStatus())) {
            throw conflict("RETURN_NOT_REFUNDABLE", "该退货申请当前无法退款");
        }
        if (refunds.hasOtherCashRefund(orderId, requestId)) {
            throw conflict("ORDER_CASH_ALREADY_REFUNDED", "该订单的现金已退回");
        }
        if (order.walletDebitAmount().signum() == 0 && escrow == null) {
            return deferPointsOnly(orderId, requestId, existing);
        }

        validateCashSource(order, escrow, original);
        Long buyerId = ownership.findBuyerId(order.batchId());
        if (buyerId == null) {
            throw invalidFunding(orderId, "buyer not found");
        }
        WalletPair parties = lockWallets(buyerId, sellerUserId, original != null);
        BigDecimal amount = escrow.principalAmount();
        if (!settlement.hasMatchingBuyerDebit(escrow.fundingEntryId(), orderId, amount)) {
            throw invalidFunding(orderId, "buyer payment does not match escrow");
        }
        if (original != null && !refunds.hasMatchingSellerCredit(original.sellerCreditEntryId(),
                parties.seller().id(), orderId, original.sellerNetAmount())) {
            throw invalidFunding(orderId, "seller credit does not match original settlement");
        }

        Long escrowId = original == null ? escrow.id() : null;
        Long settlementId = original == null ? null : original.id();
        if (parties.seller() != null && parties.seller().balance().compareTo(amount) < 0) {
            long refundId = pendingRefund(requestId, existing, escrowId, settlementId, PENDING_CASH, BALANCE_INSUFFICIENT);
            return new RefundResult(refundId, PENDING_CASH, ZERO, FundingPath.SELLER_WALLET);
        }

        long refundId = pendingRefund(requestId, existing, escrowId, settlementId, PENDING_CASH, null);
        Long recoveryId = null;
        if (original != null) {
            requireOne(wallets.debit(parties.seller().id(), amount), "Seller wallet recovery failed");
            recoveryId = writeEntry(parties.seller().id(), orderId, original.sellerCreditEntryId(),
                    "SELLER_REFUND_RECOVERY", "DEBIT", amount);
        }
        requireOne(settlement.creditWallet(parties.buyer().id(), amount), "Buyer wallet refund credit failed");
        long creditId = writeEntry(parties.buyer().id(), orderId, escrow.fundingEntryId(), "BUYER_REFUND", "CREDIT", amount);
        if (original == null) {
            requireOne(refunds.returnEscrow(escrow.id()), "Escrow refund failed");
        }
        requireOne(refunds.recordCashRefund(refundId, amount, creditId, recoveryId), "Cash refund could not be recorded");
        return new RefundResult(refundId, CASH_REFUNDED, amount,
                original == null ? FundingPath.ESCROW : FundingPath.SELLER_WALLET);
    }

    private RefundResult originalResult(Order order, Escrow escrow, OriginalSettlement original, Refund existing) {
        boolean escrowPath = original == null && escrow != null && escrow.id().equals(existing.escrowId())
                && existing.originalSettlementId() == null && existing.sellerRecoveryEntryId() == null;
        boolean sellerPath = original != null && original.id().equals(existing.originalSettlementId())
                && existing.escrowId() == null && existing.sellerRecoveryEntryId() != null;
        if ((!escrowPath && !sellerPath) || PENDING_CASH.equals(existing.refundStatus())
                || PENDING_POINTS.equals(existing.refundStatus()) || existing.cashRefundAmount().signum() <= 0
                || existing.cashRefundAmount().compareTo(order.walletDebitAmount()) != 0) {
            throw invalidFunding(order.id(), "recorded refund amount or source does not match the order");
        }
        return new RefundResult(existing.id(), existing.refundStatus(), existing.cashRefundAmount(),
                escrowPath ? FundingPath.ESCROW : FundingPath.SELLER_WALLET);
    }

    private RefundResult deferPointsOnly(long orderId, long requestId, Refund existing) {
        if (!refunds.hasPointsOnlyFunding(orderId)) {
            throw invalidFunding(orderId, "neither funded escrow nor a points-only payment was found");
        }
        if (existing != null && "COMPLETED".equals(existing.refundStatus()) && existing.completedAt() != null
                && existing.cashRefundAmount().signum() == 0 && existing.escrowId() == null
                && existing.originalSettlementId() == null && existing.sellerRecoveryEntryId() == null) {
            return new RefundResult(existing.id(), existing.refundStatus(), ZERO, FundingPath.POINTS_ONLY);
        }
        long id = pendingRefund(requestId, existing, null, null, PENDING_POINTS, null);
        return new RefundResult(id, PENDING_POINTS, ZERO, FundingPath.POINTS_ONLY);
    }

    private void validateCashSource(Order order, Escrow escrow, OriginalSettlement original) {
        if (escrow == null || escrow.principalAmount().signum() <= 0
                || escrow.principalAmount().compareTo(order.walletDebitAmount()) != 0
                || escrow.principalAmount().compareTo(order.initialEscrowAmount()) != 0) {
            throw invalidFunding(order.id(), "order cash amount does not match escrow principal");
        }
        BigDecimal amount = escrow.principalAmount();
        if (escrow.returnedAmount().signum() != 0
                || escrow.releasedAmount().compareTo(original == null ? ZERO : amount) != 0) {
            throw invalidFunding(order.id(), "escrow is already returned or release amount is inconsistent");
        }
        if (original != null && (original.sellerCreditEntryId() == null || original.sellerNetAmount().signum() <= 0
                || original.feeBaseAmount().compareTo(amount) != 0 || original.releasedAmount().compareTo(amount) != 0
                || original.feeAmount().add(original.sellerNetAmount()).compareTo(amount) != 0)) {
            throw invalidFunding(order.id(), "original settlement does not reconcile with escrow");
        }
    }

    private WalletPair lockWallets(long buyerId, long sellerId, boolean sellerWalletRequired) {
        if (!sellerWalletRequired) {
            return new WalletPair(wallets.lockWallet(buyerId), null).requireBuyer();
        }
        Wallet first = wallets.lockWallet(Math.min(buyerId, sellerId));
        Wallet second = buyerId == sellerId ? first : wallets.lockWallet(Math.max(buyerId, sellerId));
        WalletPair parties = buyerId <= sellerId ? new WalletPair(first, second) : new WalletPair(second, first);
        parties.requireBuyer();
        if (parties.seller() == null) {
            throw conflict("SELLER_WALLET_NOT_FOUND", "卖家钱包不存在，退款未执行");
        }
        return parties;
    }

    private long pendingRefund(long requestId, Refund existing, Long escrowId, Long settlementId,
            String status, String failure) {
        if (existing == null) {
            GeneratedId key = new GeneratedId();
            requireOne(refunds.createRefund(requestId, escrowId, settlementId, status, failure, key),
                    "Pending refund could not be recorded");
            return generatedId(key);
        }
        if (!status.equals(existing.refundStatus()) || existing.completedAt() != null
                || existing.cashRefundAmount().signum() != 0 || existing.sellerRecoveryEntryId() != null
                || !Objects.equals(escrowId, existing.escrowId())
                || !Objects.equals(settlementId, existing.originalSettlementId())) {
            LOG.warn("Refund request {} has an inconsistent pending refund {}", requestId, existing.id());
            throw conflict("REFUND_RECORD_CONFLICT", "退款记录与当前处理路径不一致");
        }
        if (failure != null && !failure.equals(existing.failureReason())) {
            requireOne(refunds.recordInsufficientBalance(existing.id()), "Refund failure reason could not be recorded");
        }
        return existing.id();
    }

    private long writeEntry(long walletId, long orderId, long originalEntryId, String kind, String direction,
            BigDecimal amount) {
        GeneratedId key = new GeneratedId();
        requireOne(refunds.recordWalletEntry(walletId, orderId, originalEntryId, kind, direction, amount, key),
                "Refund wallet entry could not be recorded");
        return generatedId(key);
    }

    private long generatedId(GeneratedId key) {
        if (key.getId() == null) {
            throw new IllegalStateException("Refund insert did not generate an ID");
        }
        return key.getId();
    }

    private void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new IllegalStateException(message);
        }
    }

    private BusinessException invalidFunding(long orderId, String detail) {
        LOG.warn("Refund funding validation failed for order {}: {}", orderId, detail);
        return conflict("REFUND_FUNDING_CONFLICT", "原始资金记录不一致，退款未执行");
    }

    private BusinessException requestNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RETURN_REQUEST_NOT_FOUND", "退货申请不存在");
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private record WalletPair(Wallet buyer, Wallet seller) {
        WalletPair requireBuyer() {
            if (buyer == null) {
                throw conflict("BUYER_WALLET_NOT_FOUND", "买家钱包不存在，退款未执行");
            }
            return this;
        }
    }
}
