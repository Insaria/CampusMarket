package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Escrow;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import edu.bistu.campusmarket.mapper.SettlementMapper.Settlement;
import edu.bistu.campusmarket.mapper.SettlementMapper.Terms;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class SettlementService {
    private final SettlementMapper mapper;
    private final WalletPaymentMapper wallets;
    private final NotificationService notifications;

    public SettlementService(SettlementMapper mapper, WalletPaymentMapper wallets, NotificationService notifications) {
        this.mapper = mapper;
        this.wallets = wallets;
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Settlement> settle(long orderId) {
        // Refund and return writers must acquire this same order row lock before changing eligibility.
        Order order = mapper.lockOrder(orderId);
        if (order == null) {
            return Optional.empty();
        }
        Escrow escrow = mapper.lockEscrow(orderId);
        if (escrow != null) {
            Settlement existing = mapper.findSettlement(escrow.id());
            if (existing != null) {
                return Optional.of(existing);
            }
        }
        if (!"COMPLETED".equals(order.orderStatus())) {
            return Optional.empty();
        }
        if (completionTime(order).isAfter(mapper.settlementCutoff())
                || mapper.lockActiveReturnId(orderId) != null || mapper.hasUnfinishedRefund(orderId)) {
            return Optional.empty();
        }
        validateEscrow(order, escrow);

        var seller = mapper.lockSeller(order.merchantId());
        if (seller == null) {
            throw new IllegalStateException("Order is missing its seller");
        }
        Terms terms = feeTerms(seller, escrow.principalAmount());
        var wallet = wallets.lockWallet(seller.userId());
        if (wallet == null) {
            throw new IllegalStateException("Seller wallet is missing");
        }

        requireOne(mapper.creditWallet(wallet.id(), terms.net()), "Seller wallet balance could not be increased");
        GeneratedId credit = new GeneratedId();
        requireOne(mapper.recordSellerCredit(wallet.id(), orderId, terms.net(), credit), "Seller credit was not recorded");
        if (credit.getId() == null) {
            throw new IllegalStateException("Seller credit did not generate a wallet entry ID");
        }
        requireOne(mapper.insertSettlement(escrow.id(), credit.getId(), terms), "Settlement was not recorded");
        requireOne(mapper.releaseEscrow(escrow.id()), "Escrow could not be released");
        notifications.emit(NotificationService.Event.ORDER_SETTLED, orderId, orderId);
        return Optional.of(mapper.findSettlement(escrow.id()));
    }

    private LocalDateTime completionTime(Order order) {
        LocalDateTime manual = order.buyerConfirmedAt();
        LocalDateTime automatic = order.autoConfirmedAt();
        if ((manual == null) == (automatic == null)) {
            throw new IllegalStateException("Completed order must have exactly one confirmation time");
        }
        return manual != null ? manual : automatic;
    }

    private void validateEscrow(Order order, Escrow escrow) {
        if (escrow == null || escrow.principalAmount().signum() <= 0) {
            throw new IllegalStateException("Completed order is missing funded cash escrow");
        }
        if (escrow.releasedAmount().signum() != 0 || escrow.returnedAmount().signum() != 0) {
            throw new IllegalStateException("Escrow has already been released or returned");
        }
        BigDecimal principal = escrow.principalAmount();
        if (principal.compareTo(order.initialEscrowAmount()) != 0
                || principal.compareTo(order.walletDebitAmount()) != 0
                || !mapper.hasMatchingBuyerDebit(escrow.fundingEntryId(), order.id(), principal)) {
            throw new IllegalStateException("Escrow principal does not match the order and buyer debit");
        }
    }

    private Terms feeTerms(SettlementMapper.Seller seller, BigDecimal principal) {
        if ("PERSONAL".equals(seller.sellerType())) {
            return new Terms(null, null, BigDecimal.ZERO, mapper.utcNow(), principal,
                    BigDecimal.ZERO.setScale(2), principal);
        }
        if (!"BUSINESS".equals(seller.sellerType()) || seller.levelId() == null) {
            throw new IllegalStateException("Business seller is missing a level");
        }
        var level = mapper.lockEnabledLevel(seller.levelId());
        if (level == null) {
            throw new IllegalStateException("Business seller level is unavailable or disabled");
        }
        BigDecimal fee = principal.multiply(level.feeRate()).setScale(2, RoundingMode.HALF_UP);
        return new Terms(level.id(), level.levelCode(), level.feeRate(), mapper.utcNow(), principal, fee,
                principal.subtract(fee));
    }

    private void requireOne(int count, String message) {
        if (count != 1) {
            throw new IllegalStateException(message);
        }
    }
}
