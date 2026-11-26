package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.WalletPaymentRequest;
import edu.bistu.campusmarket.dto.WalletPaymentResult;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.CheckoutMapper;
import edu.bistu.campusmarket.mapper.OrderPointsMapper;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Order;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class WalletPaymentService {
    private static final String PAYMENT_EVENT = "WALLET_PAYMENT";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    private static final long POINTS_PER_YUAN = 100;
    private final CheckoutMapper checkout;
    private final WalletPaymentMapper mapper;
    private final OrderPointsMapper points;
    private final NotificationService notifications;

    public WalletPaymentService(CheckoutMapper checkout, WalletPaymentMapper mapper, OrderPointsMapper points,
            NotificationService notifications) {
        this.checkout = checkout;
        this.mapper = mapper;
        this.points = points;
        this.notifications = notifications;
    }

    @Transactional
    public WalletPaymentResult pay(long buyerId, long batchId, WalletPaymentRequest input) {
        if (checkout.lockBuyer(buyerId) == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PAYMENT_FORBIDDEN", "当前账号不可支付");
        }
        var batch = mapper.lockBatch(batchId, buyerId);
        if (batch == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "CHECKOUT_BATCH_NOT_FOUND", "结账批次不存在");
        }
        List<Order> orders = mapper.lockOrders(batchId);
        if (orders.isEmpty()) {
            throw conflict("BATCH_NOT_PAYABLE", "批次没有可支付的订单");
        }
        List<Long> orderIds = orders.stream().map(Order::id).toList();
        long paidCount = orders.stream().filter(order -> mapper.paymentEvent(order.id(), PAYMENT_EVENT) != null).count();
        if (paidCount == orders.size() && batch.totalWalletDebitAmount().signum() > 0) {
            return new WalletPaymentResult(batchId, batch.totalWalletDebitAmount(), orderIds,
                    batch.totalPointsUsed(), batch.totalPointsDiscountAmount());
        }
        if (paidCount != 0 || batch.totalWalletDebitAmount().signum() != 0
                || orders.stream().anyMatch(order -> !"PENDING_PAYMENT".equals(order.orderStatus()))) {
            throw conflict("BATCH_NOT_PAYABLE", "该批次不处于待支付状态");
        }

        Map<Long, Long> redemptions = requestedRedemptions(input, orderIds);
        Map<Long, Payment> payments = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        long totalPoints = 0;
        for (Order order : orders) {
            if (mapper.lockEligibleSeller(order.merchantId()) == null) {
                throw conflict("SELLER_UNAVAILABLE", "批次中有卖家当前不可经营");
            }
            BigDecimal amount = mapper.lockLines(order.id()).stream()
                    .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (amount.signum() <= 0) {
                throw conflict("ORDER_AMOUNT_NOT_SUPPORTED", "钱包托管暂不支持零金额订单");
            }
            long quantity = redemptions.getOrDefault(order.id(), 0L);
            BigDecimal discount = discountFor(quantity);
            BigDecimal cash = amount.subtract(discount);
            if (cash.signum() <= 0) {
                throw conflict("POINTS_DISCOUNT_EXCEEDS_LIMIT", "积分抵扣后每笔订单须保留正数现金金额");
            }
            payments.put(order.id(), new Payment(amount, cash, quantity, discount));
            total = total.add(amount);
            totalPoints = Math.addExact(totalPoints, quantity);
        }
        if (total.compareTo(MAX_AMOUNT) > 0) {
            throw conflict("PAYMENT_AMOUNT_TOO_LARGE", "支付金额超出允许范围");
        }
        BigDecimal totalDiscount = discountFor(totalPoints);
        BigDecimal cash = total.subtract(totalDiscount);
        var wallet = mapper.lockWallet(buyerId);
        if (wallet == null || wallet.balance().compareTo(cash) < 0) {
            throw insufficientBalance();
        }
        boolean earnsRewards = payments.values().stream().anyMatch(payment -> payment.reward() > 0);
        Long pointsAccountId = totalPoints == 0 && !earnsRewards ? null : preparePointsAccount(buyerId, totalPoints);
        if (mapper.debit(wallet.id(), cash) != 1) {
            throw insufficientBalance();
        }
        for (var entry : payments.entrySet()) {
            fundOrder(buyerId, wallet.id(), entry.getKey(), entry.getValue(), pointsAccountId);
        }
        requireOne(mapper.updateBatch(batchId, total, cash, totalPoints, totalDiscount));
        for (long orderId : orderIds) {
            notifications.emit(NotificationService.Event.ORDER_PAID, orderId, orderId);
            notifications.emit(NotificationService.Event.BUYER_ORDER_PAID, orderId, orderId);
        }
        return new WalletPaymentResult(batchId, cash, orderIds, totalPoints, totalDiscount);
    }

    private Map<Long, Long> requestedRedemptions(WalletPaymentRequest input, List<Long> orderIds) {
        if (input == null || input.redemptions() == null || input.redemptions().isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> quantities = new LinkedHashMap<>();
        for (var redemption : input.redemptions()) {
            long quantity = redemption.points();
            if (!orderIds.contains(redemption.orderId()) || quantity <= 0 || quantity % POINTS_PER_YUAN != 0
                    || quantities.putIfAbsent(redemption.orderId(), quantity) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_POINTS_REDEMPTION",
                        "请为批次内每笔订单最多指定一次抵扣，积分须为 100 的正整数倍");
            }
        }
        return quantities;
    }

    private BigDecimal discountFor(long quantity) {
        return BigDecimal.valueOf(quantity / POINTS_PER_YUAN).setScale(2);
    }

    private long preparePointsAccount(long buyerId, long quantity) {
        var account = points.lockAccount(buyerId);
        if (quantity > 0) {
            if (account == null || account.balance() < quantity || points.debit(account.id(), quantity) != 1) {
                throw conflict("INSUFFICIENT_POINTS_BALANCE", "积分余额不足");
            }
            return account.id();
        }
        if (account == null) {
            // The buyer row was locked before checking or creating the account.
            requireOne(points.createAccount(buyerId));
            account = points.lockAccount(buyerId);
            if (account == null) {
                throw new IllegalStateException("Created points account could not be read");
            }
        }
        return account.id();
    }

    private void fundOrder(long buyerId, long walletId, long orderId, Payment payment, Long pointsAccountId) {
        GeneratedId key = new GeneratedId();
        requireOne(mapper.recordDebit(walletId, orderId, payment.cash(), "ORDER_PAYMENT:" + orderId, key));
        if (key.getId() == null) {
            throw new IllegalStateException("Wallet debit did not generate a funding entry ID");
        }
        requireOne(mapper.escrow(orderId, key.getId(), payment.cash()));
        if (payment.points() > 0) {
            requireOne(points.recordRedemption(pointsAccountId, orderId, payment.points()));
        }
        requireOne(mapper.markPaid(orderId, payment.payable(), payment.cash(), payment.points(), payment.discount()));
        Integer lastSequence = mapper.lastEventSequence(orderId);
        if (lastSequence == null) {
            throw new IllegalStateException("Order is missing its initial event");
        }
        requireOne(mapper.recordPayment(orderId, Math.incrementExact(lastSequence), PAYMENT_EVENT, buyerId));
        long reward = payment.reward();
        if (reward > 0) {
            requireOne(points.creditReward(pointsAccountId, reward));
            requireOne(points.recordReward(pointsAccountId, orderId, reward));
        }
    }

    private void requireOne(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Wallet payment write did not affect one row");
        }
    }

    private BusinessException insufficientBalance() {
        return conflict("INSUFFICIENT_WALLET_BALANCE", "钱包余额不足");
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private record Payment(BigDecimal payable, BigDecimal cash, long points, BigDecimal discount) {
        long reward() {
            return cash.setScale(0, RoundingMode.DOWN).longValueExact();
        }
    }
}
