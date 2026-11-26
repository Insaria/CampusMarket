package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.WalletPaymentRequest;
import edu.bistu.campusmarket.dto.WalletPaymentRequest.Redemption;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.CheckoutMapper;
import edu.bistu.campusmarket.mapper.OrderPointsMapper;
import edu.bistu.campusmarket.mapper.OrderPointsMapper.Account;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Batch;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Line;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Order;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Wallet;
import edu.bistu.campusmarket.service.WalletPaymentService;
import edu.bistu.campusmarket.service.NotificationService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class WalletPaymentTest {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal ONE = new BigDecimal("1.00");
    private static final BigDecimal TWO = new BigDecimal("2.00");
    private static final WalletPaymentRequest MIXED = new WalletPaymentRequest(
            List.of(new Redemption(3L, 100L), new Redemption(4L, 200L)));
    private final CheckoutMapper checkout = mock(CheckoutMapper.class);
    private final WalletPaymentMapper mapper = mock(WalletPaymentMapper.class);
    private final OrderPointsMapper points = mock(OrderPointsMapper.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final WalletPaymentService service = new WalletPaymentService(checkout, mapper, points, notifications);

    @BeforeEach
    void pendingBatch() {
        when(checkout.lockBuyer(1L)).thenReturn(1L);
        when(mapper.lockBatch(2L, 1L)).thenReturn(new Batch(2L, ZERO, 0L, ZERO));
        when(mapper.lockOrders(2L)).thenReturn(List.of(
                new Order(3L, 5L, "PENDING_PAYMENT"), new Order(4L, 6L, "PENDING_PAYMENT")));
    }

    @Test
    void debitsOnceAndFundsEachOrderFromItsOwnEntry() {
        BigDecimal total = prepareAmounts();
        when(mapper.lockWallet(1L)).thenReturn(new Wallet(7L, total));
        when(mapper.debit(7L, total)).thenReturn(1);
        when(points.lockAccount(1L)).thenReturn(null, new Account(10L, 0L));
        when(points.createAccount(1L)).thenReturn(1);
        prepareFunding(3L, 8L, new BigDecimal("20.00"));
        prepareFunding(4L, 9L, new BigDecimal("5.00"));
        prepareReward(3L, 20L);
        prepareReward(4L, 5L);
        when(mapper.updateBatch(2L, total, total, 0, ZERO)).thenReturn(1);

        var result = service.pay(1L, 2L, null);

        assertEquals(total, result.paidAmount());
        assertEquals(List.of(3L, 4L), result.orderIds());
        verify(mapper).debit(7L, total);
        verify(mapper).escrow(3L, 8L, new BigDecimal("20.00"));
        verify(mapper).escrow(4L, 9L, new BigDecimal("5.00"));
        verify(mapper).recordPayment(3L, 2, "WALLET_PAYMENT", 1L);
        verify(mapper).recordPayment(4L, 2, "WALLET_PAYMENT", 1L);
        verify(points).createAccount(1L);
        verify(points).recordReward(10L, 3L, 20L);
        verify(points).recordReward(10L, 4L, 5L);
        verify(notifications).emit(NotificationService.Event.ORDER_PAID, 3L, 3L);
        verify(notifications).emit(NotificationService.Event.ORDER_PAID, 4L, 4L);
        verify(notifications).emit(NotificationService.Event.BUYER_ORDER_PAID, 3L, 3L);
        verify(notifications).emit(NotificationService.Event.BUYER_ORDER_PAID, 4L, 4L);
    }

    @Test
    void insufficientBalancePreventsDebit() {
        BigDecimal total = prepareAmounts();
        when(mapper.lockWallet(1L)).thenReturn(new Wallet(7L, new BigDecimal("24.99")));

        assertThrows(BusinessException.class, () -> service.pay(1L, 2L, null));

        verify(mapper, never()).debit(7L, total);
        verify(mapper, never()).updateBatch(2L, total, total, 0, ZERO);
    }

    @Test
    void returnsOriginalResultWhenBatchWasAlreadyPaid() {
        BigDecimal paid = new BigDecimal("25.00");
        when(mapper.lockBatch(2L, 1L)).thenReturn(new Batch(2L, paid, 0L, ZERO));
        when(mapper.lockOrders(2L)).thenReturn(List.of(
                new Order(3L, 5L, "PAID"), new Order(4L, 6L, "PAID")));
        when(mapper.paymentEvent(3L, "WALLET_PAYMENT")).thenReturn(2);
        when(mapper.paymentEvent(4L, "WALLET_PAYMENT")).thenReturn(2);

        assertEquals(paid, service.pay(1L, 2L, null).paidAmount());

        verify(mapper, never()).lockWallet(1L);
        verifyNoInteractions(points);
        verifyNoInteractions(notifications);
    }

    @Test
    void cancelledBatchCannotReachWalletDebit() {
        when(mapper.lockOrders(2L)).thenReturn(List.of(
                new Order(3L, 5L, "CANCELLED"), new Order(4L, 6L, "CANCELLED")));
        when(mapper.paymentEvent(3L, "WALLET_PAYMENT")).thenReturn(null);
        when(mapper.paymentEvent(4L, "WALLET_PAYMENT")).thenReturn(null);

        assertEquals("BATCH_NOT_PAYABLE",
                assertThrows(BusinessException.class, () -> service.pay(1L, 2L, null)).code());

        verify(mapper, never()).lockWallet(1L);
    }

    @Test
    void discountReducesOnlyCashAndCreatesOneRedemptionPerOrder() {
        BigDecimal total = prepareAmounts();
        BigDecimal cash = new BigDecimal("22.00");
        BigDecimal discount = new BigDecimal("3.00");
        when(mapper.lockWallet(1L)).thenReturn(new Wallet(7L, cash));
        when(mapper.debit(7L, cash)).thenReturn(1);
        when(points.lockAccount(1L)).thenReturn(new Account(10L, 300L));
        when(points.debit(10L, 300L)).thenReturn(1);
        when(points.recordRedemption(10L, 3L, 100L)).thenReturn(1);
        when(points.recordRedemption(10L, 4L, 200L)).thenReturn(1);
        prepareFunding(3L, 8L, new BigDecimal("20.00"), new BigDecimal("19.00"), 100L, ONE);
        prepareFunding(4L, 9L, new BigDecimal("5.00"), new BigDecimal("3.00"), 200L, TWO);
        prepareReward(3L, 19L);
        prepareReward(4L, 3L);
        when(mapper.updateBatch(2L, total, cash, 300, discount)).thenReturn(1);

        var result = service.pay(1L, 2L, MIXED);

        assertEquals(cash, result.paidAmount());
        assertEquals(300, result.pointsUsed());
        assertEquals(discount, result.pointsDiscountAmount());
        verify(points).debit(10L, 300L);
        verify(points).recordRedemption(10L, 3L, 100L);
        verify(points).recordRedemption(10L, 4L, 200L);
        var entries = inOrder(points, mapper);
        entries.verify(points).debit(10L, 300L);
        entries.verify(mapper).debit(7L, cash);
        entries.verify(points).recordReward(10L, 3L, 19L);
        entries.verify(points).recordReward(10L, 4L, 3L);
        verify(mapper).escrow(3L, 8L, new BigDecimal("19.00"));
        verify(mapper).escrow(4L, 9L, new BigDecimal("3.00"));
    }

    @Test
    void insufficientPointsLeavesBothBalancesUnchanged() {
        prepareAmounts();
        var cash = new BigDecimal("22.00");
        when(mapper.lockWallet(1L)).thenReturn(new Wallet(7L, cash));
        when(points.lockAccount(1L)).thenReturn(new Account(10L, 299L));

        assertEquals("INSUFFICIENT_POINTS_BALANCE",
                assertThrows(BusinessException.class, () -> service.pay(1L, 2L, MIXED)).code());

        verify(points, never()).debit(10L, 300L);
        verify(mapper, never()).debit(7L, cash);
    }

    @Test
    void cashDebitFailureRollsBackPointsDebit() {
        prepareAmounts();
        when(mapper.lockWallet(1L)).thenReturn(new Wallet(7L, new BigDecimal("22.00")));
        when(points.lockAccount(1L)).thenReturn(new Account(10L, 300L));
        when(points.debit(10L, 300L)).thenReturn(1);
        var manager = mock(PlatformTransactionManager.class);
        var tx = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(tx);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        var transactional = (WalletPaymentService) factory.getProxy();

        assertEquals("INSUFFICIENT_WALLET_BALANCE",
                assertThrows(BusinessException.class, () -> transactional.pay(1L, 2L, MIXED)).code());

        verify(points).debit(10L, 300L);
        verify(manager).rollback(tx);
        verify(manager, never()).commit(tx);
    }

    @Test
    void mixedPaymentReplayReturnsPersistedPointsWithoutAnotherDebit() {
        var cash = new BigDecimal("22.00");
        var discount = new BigDecimal("3.00");
        when(mapper.lockBatch(2L, 1L)).thenReturn(new Batch(2L, cash, 300L, discount));
        when(mapper.paymentEvent(3L, "WALLET_PAYMENT")).thenReturn(2);
        when(mapper.paymentEvent(4L, "WALLET_PAYMENT")).thenReturn(2);

        var result = service.pay(1L, 2L, MIXED);

        assertEquals(300, result.pointsUsed());
        assertEquals(discount, result.pointsDiscountAmount());
        verify(mapper, never()).lockWallet(1L);
        verifyNoInteractions(points);
    }

    @Test
    void fullPointsPaymentIsRejectedBeforeLockingFunds() {
        prepareAmounts();
        var fullPoints = new WalletPaymentRequest(List.of(new Redemption(4L, 500L)));

        assertEquals("POINTS_DISCOUNT_EXCEEDS_LIMIT",
                assertThrows(BusinessException.class, () -> service.pay(1L, 2L, fullPoints)).code());
        verify(mapper, never()).lockWallet(1L);
        verifyNoInteractions(points);
    }

    @Test
    void rewardsRoundDownPerOrderAndSkipZeroPointEntries() {
        prepareAmounts();
        var first = new BigDecimal("0.90");
        var second = new BigDecimal("1.25");
        var total = first.add(second);
        when(mapper.lockLines(3L)).thenReturn(List.of(new Line(first, 1)));
        when(mapper.lockLines(4L)).thenReturn(List.of(new Line(second, 1)));
        when(mapper.lockWallet(1L)).thenReturn(new Wallet(7L, total));
        when(mapper.debit(7L, total)).thenReturn(1);
        when(points.lockAccount(1L)).thenReturn(new Account(10L, 4L));
        prepareFunding(3L, 8L, first);
        prepareFunding(4L, 9L, second);
        prepareReward(4L, 1L);
        when(mapper.updateBatch(2L, total, total, 0, ZERO)).thenReturn(1);

        assertEquals(total, service.pay(1L, 2L, null).paidAmount());

        verify(points).creditReward(10L, 1L);
        verify(points).recordReward(10L, 4L, 1L);
        verify(points, never()).recordReward(eq(10L), eq(3L), anyLong());
    }

    private BigDecimal prepareAmounts() {
        when(mapper.paymentEvent(3L, "WALLET_PAYMENT")).thenReturn(null);
        when(mapper.paymentEvent(4L, "WALLET_PAYMENT")).thenReturn(null);
        when(mapper.lockEligibleSeller(5L)).thenReturn(5L);
        when(mapper.lockEligibleSeller(6L)).thenReturn(6L);
        when(mapper.lockLines(3L)).thenReturn(List.of(new Line(new BigDecimal("10.00"), 2)));
        when(mapper.lockLines(4L)).thenReturn(List.of(new Line(new BigDecimal("5.00"), 1)));
        return new BigDecimal("25.00");
    }

    private void prepareFunding(long orderId, long entryId, BigDecimal amount) {
        prepareFunding(orderId, entryId, amount, amount, 0, ZERO);
    }

    private void prepareReward(long orderId, long reward) {
        when(points.creditReward(10L, reward)).thenReturn(1);
        when(points.recordReward(10L, orderId, reward)).thenReturn(1);
    }

    private void prepareFunding(long orderId, long entryId, BigDecimal payable, BigDecimal cash,
            long pointsUsed, BigDecimal discount) {
        when(mapper.recordDebit(eq(7L), eq(orderId), eq(cash), eq("ORDER_PAYMENT:" + orderId), any()))
                .thenAnswer(invocation -> {
                    GeneratedId key = invocation.getArgument(4);
                    key.setId(entryId);
                    return 1;
                });
        when(mapper.escrow(orderId, entryId, cash)).thenReturn(1);
        when(mapper.markPaid(orderId, payable, cash, pointsUsed, discount)).thenReturn(1);
        when(mapper.lastEventSequence(orderId)).thenReturn(1);
        when(mapper.recordPayment(orderId, 2, "WALLET_PAYMENT", 1L)).thenReturn(1);
    }
}
