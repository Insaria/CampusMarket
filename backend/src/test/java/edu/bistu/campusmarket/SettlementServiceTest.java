package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Escrow;
import edu.bistu.campusmarket.mapper.SettlementMapper.Level;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import edu.bistu.campusmarket.mapper.SettlementMapper.Seller;
import edu.bistu.campusmarket.mapper.SettlementMapper.Settlement;
import edu.bistu.campusmarket.mapper.SettlementMapper.Terms;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Wallet;
import edu.bistu.campusmarket.service.SettlementService;
import edu.bistu.campusmarket.service.NotificationService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class SettlementServiceTest {
    private static final LocalDateTime COMPLETED_AT = LocalDateTime.parse("2026-11-19T08:30:00.125");
    private static final LocalDateTime SETTLEMENT_TIME = COMPLETED_AT.plusHours(24);
    private static final BigDecimal PRINCIPAL = new BigDecimal("100.00");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final SettlementMapper mapper = mock(SettlementMapper.class);
    private final WalletPaymentMapper wallets = mock(WalletPaymentMapper.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final SettlementService service = new SettlementService(mapper, wallets, notifications);

    @BeforeEach
    void paidEscrow() {
        when(mapper.lockOrder(1L)).thenReturn(order(PRINCIPAL, COMPLETED_AT, null));
        when(mapper.lockEscrow(1L)).thenReturn(new Escrow(3L, 4L, PRINCIPAL, ZERO, ZERO));
        when(mapper.lockActiveReturnId(1L)).thenReturn(null);
        when(mapper.settlementCutoff()).thenReturn(COMPLETED_AT);
        when(mapper.utcNow()).thenReturn(SETTLEMENT_TIME);
        when(mapper.hasMatchingBuyerDebit(4L, 1L, PRINCIPAL)).thenReturn(true);
        when(mapper.lockSeller(2L)).thenReturn(new Seller(5L, "PERSONAL", null));
        when(wallets.lockWallet(5L)).thenReturn(new Wallet(6L, ZERO));
    }

    @Test
    void personalSellerReceivesEntirePrincipalWithoutLevel() {
        Terms terms = personalTerms();
        Settlement expected = preparePayout(terms);

        assertEquals(expected, service.settle(1L).orElseThrow());

        verify(mapper, never()).lockEnabledLevel(anyLong());
        verify(mapper).creditWallet(6L, PRINCIPAL);
        verify(mapper).insertSettlement(3L, 7L, terms);
        verify(mapper).releaseEscrow(3L);
        verify(notifications).emit(NotificationService.Event.ORDER_SETTLED, 1, 1);
    }

    @ParameterizedTest
    @CsvSource({"1,0.001000,0.10,99.90", "2,0.002000,0.20,99.80", "3,0.005000,0.50,99.50",
            "4,0.007500,0.75,99.25", "5,0.010000,1.00,99.00"})
    void usesCurrentlyEnabledLevelAndPersistsRateSnapshot(int code, String rate, String fee, String net) {
        var terms = new Terms(9L, code, new BigDecimal(rate), SETTLEMENT_TIME,
                PRINCIPAL, new BigDecimal(fee), new BigDecimal(net));
        when(mapper.lockSeller(2L)).thenReturn(new Seller(5L, "BUSINESS", 9L));
        when(mapper.lockEnabledLevel(9L)).thenReturn(new Level(9L, code, terms.rate()));
        Settlement expected = preparePayout(terms);

        assertEquals(expected, service.settle(1L).orElseThrow());
        verify(mapper).insertSettlement(3L, 7L, terms);
    }

    @Test
    void roundsHalfCentUpForAutomaticallyConfirmedOrder() {
        BigDecimal principal = new BigDecimal("1.00");
        when(mapper.lockOrder(1L)).thenReturn(order(principal, null, COMPLETED_AT));
        when(mapper.lockEscrow(1L)).thenReturn(new Escrow(3L, 4L, principal, ZERO, ZERO));
        when(mapper.hasMatchingBuyerDebit(4L, 1L, principal)).thenReturn(true);
        var terms = new Terms(9L, 3, new BigDecimal("0.005000"), SETTLEMENT_TIME,
                principal, new BigDecimal("0.01"), new BigDecimal("0.99"));
        when(mapper.lockSeller(2L)).thenReturn(new Seller(5L, "BUSINESS", 9L));
        when(mapper.lockEnabledLevel(9L)).thenReturn(new Level(9L, 3, terms.rate()));

        assertEquals(preparePayout(terms), service.settle(1L).orElseThrow());
    }

    @Test
    void oneMillisecondBeforeTwentyFourHoursDoesNotSettle() {
        when(mapper.settlementCutoff()).thenReturn(COMPLETED_AT.minusNanos(1_000_000));

        assertTrue(service.settle(1L).isEmpty());
        verifyNoInteractions(wallets);
    }

    @Test
    void activeReturnBlocksSettlement() {
        when(mapper.lockActiveReturnId(1L)).thenReturn(10L);

        assertTrue(service.settle(1L).isEmpty());
        verifyNoInteractions(wallets);
    }

    @Test
    void unfinishedRefundBlocksSettlementEvenWhenReturnIsInactive() {
        when(mapper.hasUnfinishedRefund(1L)).thenReturn(true);

        assertTrue(service.settle(1L).isEmpty());
        verifyNoInteractions(wallets);
    }

    @Test
    void duplicateReturnsExistingSnapshotWithoutNewCredit() {
        Settlement existing = snapshot(personalTerms());
        when(mapper.findSettlement(3L)).thenReturn(existing);

        assertEquals(existing, service.settle(1L).orElseThrow());
        verifyNoInteractions(wallets);
        verifyNoInteractions(notifications);
    }

    @Test
    void disabledLevelLeavesBusinessSettlementPending() {
        when(mapper.lockSeller(2L)).thenReturn(new Seller(5L, "BUSINESS", 9L));

        assertThrows(IllegalStateException.class, () -> service.settle(1L));
        verifyNoInteractions(wallets);
    }

    @Test
    void missingSellerWalletDoesNotReleaseEscrow() {
        when(wallets.lockWallet(5L)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.settle(1L));
        verify(mapper, never()).releaseEscrow(3L);
    }

    @Test
    void partiallyReturnedEscrowDoesNotCreditSeller() {
        when(mapper.lockEscrow(1L)).thenReturn(new Escrow(3L, 4L, PRINCIPAL, ZERO, new BigDecimal("1.00")));

        assertThrows(IllegalStateException.class, () -> service.settle(1L));
        verifyNoInteractions(wallets);
    }

    @Test
    void escrowMismatchDoesNotCreditSeller() {
        when(mapper.hasMatchingBuyerDebit(4L, 1L, PRINCIPAL)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.settle(1L));
        verifyNoInteractions(wallets);
    }

    @Test
    void walletEntryFailureRollsBackBalanceAndLeavesEscrowUnreleased() {
        var manager = mock(PlatformTransactionManager.class);
        var transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        when(mapper.creditWallet(6L, PRINCIPAL)).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> transactionalService(manager).settle(1L));

        verify(mapper).creditWallet(6L, PRINCIPAL);
        verify(manager).rollback(transaction);
        verify(manager, never()).commit(transaction);
        verify(mapper, never()).releaseEscrow(3L);
    }

    @Test
    void concurrentAttemptsShareOnePayoutWhenOrderLockSerializesTransactions() throws Exception {
        Terms terms = personalTerms();
        Settlement expected = preparePayout(terms);
        var committed = new AtomicReference<Settlement>();
        var lock = new ReentrantLock();
        var attempts = new CountDownLatch(2);
        when(mapper.lockOrder(1L)).thenAnswer(invocation -> {
            attempts.countDown();
            if (!attempts.await(5, TimeUnit.SECONDS) || !lock.tryLock(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test could not acquire simulated order lock");
            }
            return order(PRINCIPAL, COMPLETED_AT, null);
        });
        var pending = new ThreadLocal<Settlement>();
        when(mapper.insertSettlement(3L, 7L, terms)).thenAnswer(invocation -> {
            pending.set(expected);
            return 1;
        });
        when(mapper.findSettlement(3L)).thenAnswer(invocation ->
                pending.get() == null ? committed.get() : pending.get());
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(i -> mock(TransactionStatus.class));
        doAnswer(invocation -> {
            if (pending.get() != null) {
                committed.set(pending.get());
                pending.remove();
            }
            lock.unlock();
            return null;
        }).when(manager).commit(any(TransactionStatus.class));
        var proxy = transactionalService(manager);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> proxy.settle(1L));
            var second = executor.submit(() -> proxy.settle(1L));
            assertEquals(expected, first.get(10, TimeUnit.SECONDS).orElseThrow());
            assertEquals(expected, second.get(10, TimeUnit.SECONDS).orElseThrow());
        } finally {
            executor.shutdownNow();
        }
        verify(mapper).creditWallet(6L, PRINCIPAL);
        verify(mapper).recordSellerCredit(eq(6L), eq(1L), eq(PRINCIPAL), any());
        verify(mapper).releaseEscrow(3L);
    }

    private Settlement preparePayout(Terms terms) {
        when(mapper.creditWallet(6L, terms.net())).thenReturn(1);
        when(mapper.recordSellerCredit(eq(6L), eq(1L), eq(terms.net()), any())).thenAnswer(invocation -> {
            GeneratedId entry = invocation.getArgument(3);
            entry.setId(7L);
            return 1;
        });
        when(mapper.insertSettlement(3L, 7L, terms)).thenReturn(1);
        when(mapper.releaseEscrow(3L)).thenReturn(1);
        Settlement expected = snapshot(terms);
        when(mapper.findSettlement(3L)).thenReturn(null, expected);
        return expected;
    }

    private Settlement snapshot(Terms terms) {
        return new Settlement(8L, terms.levelId(), terms.levelCode(), terms.rate(), terms.appliedAt(),
                terms.principal(), terms.principal(), terms.fee(), terms.net(), SETTLEMENT_TIME);
    }

    private Terms personalTerms() {
        return new Terms(null, null, BigDecimal.ZERO, SETTLEMENT_TIME, PRINCIPAL, ZERO, PRINCIPAL);
    }

    private Order order(BigDecimal amount, LocalDateTime manual, LocalDateTime automatic) {
        return new Order(1L, 11L, 2L, "COMPLETED", manual, automatic, amount, amount);
    }

    private SettlementService transactionalService(PlatformTransactionManager manager) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        return (SettlementService) factory.getProxy();
    }
}
