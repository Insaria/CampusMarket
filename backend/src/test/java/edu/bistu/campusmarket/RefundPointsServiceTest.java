package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.ReturnRequestDtos.Item;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.RefundMapper;
import edu.bistu.campusmarket.mapper.RefundPointsMapper;
import edu.bistu.campusmarket.mapper.RefundPointsMapper.Account;
import edu.bistu.campusmarket.mapper.RefundPointsMapper.Entry;
import edu.bistu.campusmarket.mapper.RefundPointsMapper.Refund;
import edu.bistu.campusmarket.mapper.RefundPointsMapper.Target;
import edu.bistu.campusmarket.mapper.ReturnRequestMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import edu.bistu.campusmarket.service.RefundPointsService;
import edu.bistu.campusmarket.service.NotificationService;
import edu.bistu.campusmarket.service.RefundPointsJob;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class RefundPointsServiceTest {
    private static final BigDecimal CASH = new BigDecimal("100.00");
    private static final LocalDateTime CONFIRMED = LocalDateTime.parse("2026-11-20T09:00:00.123");
    private static final Entry REDEMPTION = new Entry(20L, 8L, 2L, null, null,
            "ORDER_POINTS_REDEMPTION", "DEBIT", 15L, "ORDER_POINTS_REDEMPTION:2");
    private static final Entry REWARD = new Entry(21L, 8L, 2L, null, null,
            "ORDER_POINTS_REWARD", "CREDIT", 30L, "ORDER_POINTS_REWARD:2");
    private static final Entry RESTORE = new Entry(null, 8L, 2L, 1L, 20L,
            "REFUND_POINTS_RESTORE", "CREDIT", 15L, "REFUND_POINTS:20");
    private static final Entry REVERSE = new Entry(null, 8L, 2L, 1L, 21L,
            "REFUND_POINTS_REVERSE", "DEBIT", 30L, "REFUND_POINTS:21");
    private final RefundPointsMapper mapper = mock(RefundPointsMapper.class);
    private final SettlementMapper orders = mock(SettlementMapper.class);
    private final ReturnRequestMapper requests = mock(ReturnRequestMapper.class);
    private final RefundMapper cashRefunds = mock(RefundMapper.class);
    private final OrderFulfillmentMapper ownership = mock(OrderFulfillmentMapper.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final RefundPointsService service = new RefundPointsService(
            mapper, orders, requests, cashRefunds, ownership, notifications);

    @BeforeEach
    void cashRefund() {
        when(mapper.findTarget(1L)).thenReturn(new Target(2L, 3L));
        when(orders.lockOrder(2L)).thenReturn(order(CASH));
        when(requests.lockRequest(2L, 3L)).thenReturn(new Item(3L, 2L, "order-2", "COMPLETED", CASH,
                "APPROVED", true, "Damaged", CONFIRMED, null, CONFIRMED, null));
        when(mapper.lockRefund(1L, 3L)).thenReturn(cashRefund("CASH_REFUNDED", 0L, 0L, null, null));
        when(ownership.findBuyerId(4L)).thenReturn(6L);
        when(mapper.lockAccount(6L)).thenReturn(new Account(8L, 50L));
    }

    @Test
    void restoresActualRedemptionAndCompletesWithoutInventingRewards() {
        prepareRestore();
        when(mapper.recordProgress(1L, 15L, 0L, null, true)).thenReturn(1);

        service.process(1L);

        var writes = inOrder(orders, requests, mapper);
        writes.verify(orders).lockOrder(2L);
        writes.verify(requests).lockRequest(2L, 3L);
        writes.verify(mapper).lockRefund(1L, 3L);
        writes.verify(mapper).lockAccount(6L);
        writes.verify(mapper).restoreBalance(8L, 15L);
        writes.verify(mapper).recordAdjustment(RESTORE);
        writes.verify(mapper).recordProgress(1L, 15L, 0L, null, true);
        verify(notifications).emit(NotificationService.Event.REFUND_COMPLETED, 2L, 1L);
        verify(mapper, never()).reverseBalance(anyLong(), anyLong());
    }

    @Test
    void receivedReturnCanStillCompletePointsRefund() {
        when(requests.lockRequest(2L, 3L)).thenReturn(new Item(3L, 2L, "order-2", "COMPLETED", CASH,
                "RETURNED", true, "Damaged", CONFIRMED, null, CONFIRMED, CONFIRMED.plusHours(1)));
        prepareRestore();
        when(mapper.recordProgress(1L, 15L, 0L, null, true)).thenReturn(1);

        service.process(1L);

        verify(mapper).recordProgress(1L, 15L, 0L, null, true);
    }

    @Test
    void restoresPointsForAllPointsOrder() {
        when(orders.lockOrder(2L)).thenReturn(order(BigDecimal.ZERO));
        when(mapper.lockRefund(1L, 3L)).thenReturn(
                new Refund("PENDING_POINTS", BigDecimal.ZERO, null, 0L, 0L, null, null));
        when(cashRefunds.hasPointsOnlyFunding(2L)).thenReturn(true);
        prepareRestore();
        when(mapper.recordProgress(1L, 15L, 0L, null, true)).thenReturn(1);

        service.process(1L);

        verify(mapper).recordAdjustment(RESTORE);
        verify(mapper).recordProgress(1L, 15L, 0L, null, true);
    }

    @Test
    void reversesOnlyActuallyGrantedRewards() {
        when(mapper.findOriginals(2L)).thenReturn(List.of(REWARD));
        when(mapper.reverseBalance(8L, 30L)).thenReturn(1);
        when(mapper.recordAdjustment(REVERSE)).thenReturn(1);
        when(mapper.recordProgress(1L, 0L, 30L, null, true)).thenReturn(1);

        service.process(1L);

        verify(mapper).recordAdjustment(REVERSE);
        verify(mapper).recordProgress(1L, 0L, 30L, null, true);
        verify(mapper, never()).restoreBalance(anyLong(), anyLong());
    }

    @Test
    void insufficientBalanceRestoresRedemptionButLeavesRewardAndCompletionPending() {
        prepareRestore();
        when(mapper.findOriginals(2L)).thenReturn(List.of(REDEMPTION, REWARD));
        when(mapper.lockAccount(6L)).thenReturn(new Account(8L, 1L));
        String failure = "POINTS_REWARD_BALANCE_INSUFFICIENT;remaining=30";
        when(mapper.recordProgress(1L, 15L, 0L, failure, false)).thenReturn(1);

        service.process(1L);

        verify(mapper).recordAdjustment(RESTORE);
        verify(mapper).recordProgress(1L, 15L, 0L, failure, false);
        verify(mapper, never()).reverseBalance(anyLong(), anyLong());
        verify(mapper, never()).recordAdjustment(REVERSE);
        org.mockito.Mockito.verifyNoInteractions(notifications);
    }

    @Test
    void fundedRetryReversesRewardWithoutRestoringTheSameRedemptionAgain() {
        when(mapper.findOriginals(2L)).thenReturn(List.of(REDEMPTION, REWARD));
        when(mapper.pointsUsed(2L)).thenReturn(15L);
        when(mapper.findAdjustments(2L, 1L)).thenReturn(List.of(persisted(RESTORE, 31L)));
        when(mapper.lockRefund(1L, 3L)).thenReturn(cashRefund("CASH_REFUNDED", 15L, 0L,
                "POINTS_REWARD_BALANCE_INSUFFICIENT;remaining=30", null));
        when(mapper.reverseBalance(8L, 30L)).thenReturn(1);
        when(mapper.recordAdjustment(REVERSE)).thenReturn(1);
        when(mapper.recordProgress(1L, 15L, 30L, null, true)).thenReturn(1);

        service.process(1L);

        verify(mapper, never()).restoreBalance(anyLong(), anyLong());
        verify(mapper, never()).recordAdjustment(RESTORE);
        verify(mapper).recordAdjustment(REVERSE);
        verify(mapper).recordProgress(1L, 15L, 30L, null, true);
    }

    @Test
    void persistedRewardReversalIsNotDebitedTwice() {
        when(mapper.findOriginals(2L)).thenReturn(List.of(REWARD));
        when(mapper.findAdjustments(2L, 1L)).thenReturn(List.of(persisted(REVERSE, 32L)));
        when(mapper.lockRefund(1L, 3L)).thenReturn(cashRefund("CASH_REFUNDED", 0L, 30L, null, null));
        when(mapper.recordProgress(1L, 0L, 30L, null, true)).thenReturn(1);

        service.process(1L);

        verify(mapper, never()).reverseBalance(anyLong(), anyLong());
        verify(mapper, never()).recordAdjustment(any());
        verify(mapper).recordProgress(1L, 0L, 30L, null, true);
    }

    @Test
    void pendingCashNeverAccessesPointsAccount() {
        when(mapper.lockRefund(1L, 3L)).thenReturn(
                new Refund("PENDING_CASH", BigDecimal.ZERO, null, 0L, 0L, null, null));

        service.process(1L);

        verifyNoInteractions(ownership, cashRefunds);
        verify(mapper, never()).lockAccount(anyLong());
    }

    @Test
    void orderWithoutAnyPointsFinalizesWithoutAccountOrLedger() {
        when(mapper.lockAccount(6L)).thenReturn(null);
        when(mapper.recordProgress(1L, 0L, 0L, null, true)).thenReturn(1);

        service.process(1L);

        verify(mapper, never()).recordAdjustment(any());
        verify(mapper).recordProgress(1L, 0L, 0L, null, true);
    }

    @Test
    void snapshotWithoutOriginalEntryCannotComplete() {
        when(mapper.pointsUsed(2L)).thenReturn(15L);
        assertThrows(IllegalStateException.class, () -> service.process(1L));
        verify(mapper, never()).recordProgress(anyLong(), anyLong(), anyLong(), any(), anyBoolean());
    }

    @Test
    void refundCannotUseAdjustmentFromAnotherOrder() {
        prepareRestore();
        when(mapper.findAdjustments(2L, 1L)).thenReturn(List.of(new Entry(35L, 8L, 99L, 1L, 20L,
                RESTORE.entryKind(), RESTORE.direction(), RESTORE.quantity(), RESTORE.operationKey())));

        assertThrows(IllegalStateException.class, () -> service.process(1L));
        verify(mapper, never()).restoreBalance(anyLong(), anyLong());
    }

    @Test
    void failedPointsLedgerWriteRollsBackBalanceAndRefundProgress() {
        prepareRestore();
        when(mapper.recordAdjustment(RESTORE)).thenReturn(0);
        var status = mock(TransactionStatus.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        var transactional = (RefundPointsService) factory.getProxy();

        assertThrows(IllegalStateException.class, () -> transactional.process(1L));

        verify(mapper).restoreBalance(8L, 15L);
        verify(manager).rollback(status);
        verify(manager, never()).commit(status);
        verify(mapper, never()).recordProgress(1L, 15L, 0L, null, true);
    }

    @Test
    void concurrentRetriesOnlyApplyOneAdjustmentAndCompletion() throws Exception {
        prepareRestore();
        var lock = new ReentrantLock();
        var attempts = new CountDownLatch(2);
        var refund = new AtomicReference<>(cashRefund("CASH_REFUNDED", 0L, 0L, null, null));
        when(orders.lockOrder(2L)).thenAnswer(invocation -> {
            attempts.countDown();
            if (!attempts.await(5, TimeUnit.SECONDS) || !lock.tryLock(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test could not acquire simulated order lock");
            }
            return order(CASH);
        });
        when(mapper.lockRefund(1L, 3L)).thenAnswer(invocation -> refund.get());
        when(mapper.recordProgress(1L, 15L, 0L, null, true)).thenAnswer(invocation -> {
            refund.set(cashRefund("COMPLETED", 15L, 0L, null, CONFIRMED.plusDays(1)));
            return 1;
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> processWithSimulatedOrderLock(lock));
            var second = executor.submit(() -> processWithSimulatedOrderLock(lock));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        verify(mapper).restoreBalance(8L, 15L);
        verify(mapper).recordAdjustment(RESTORE);
        verify(mapper).recordProgress(1L, 15L, 0L, null, true);
    }

    @Test
    void scheduledScanContinuesPastFailedRefundAndMovesToNextPage() {
        var worker = mock(RefundPointsService.class);
        when(mapper.findCandidates(0L, 100)).thenReturn(LongStream.rangeClosed(1, 100).boxed().toList());
        when(mapper.findCandidates(100L, 100)).thenReturn(List.of(101L));
        doThrow(new IllegalStateException("Test refund write failed")).when(worker).process(1L);

        new RefundPointsJob(mapper, worker).processDueRefunds();

        var scan = inOrder(mapper, worker);
        scan.verify(mapper).findCandidates(0L, 100);
        scan.verify(worker).process(1L);
        scan.verify(worker).process(100L);
        scan.verify(mapper).findCandidates(100L, 100);
        scan.verify(worker).process(101L);
    }

    private void prepareRestore() {
        when(mapper.findOriginals(2L)).thenReturn(List.of(REDEMPTION));
        when(mapper.pointsUsed(2L)).thenReturn(15L);
        when(mapper.restoreBalance(8L, 15L)).thenReturn(1);
        when(mapper.recordAdjustment(RESTORE)).thenReturn(1);
    }

    private void processWithSimulatedOrderLock(ReentrantLock lock) {
        try {
            service.process(1L);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Entry persisted(Entry entry, long id) {
        return new Entry(id, entry.accountId(), entry.orderId(), entry.refundId(), entry.originalEntryId(),
                entry.entryKind(), entry.direction(), entry.quantity(), entry.operationKey());
    }

    private Order order(BigDecimal cash) {
        return new Order(2L, 4L, 5L, "COMPLETED", CONFIRMED, null, cash, cash);
    }

    private Refund cashRefund(String status, long restored, long reversed, String reason, LocalDateTime completedAt) {
        return new Refund(status, CASH, 9L, restored, reversed, reason, completedAt);
    }
}
