package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.controller.RefundController;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.RefundResult;
import edu.bistu.campusmarket.dto.RefundResult.FundingPath;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Item;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.RestockedItem;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.RefundMapper;
import edu.bistu.campusmarket.mapper.RefundMapper.OriginalSettlement;
import edu.bistu.campusmarket.mapper.RefundMapper.Refund;
import edu.bistu.campusmarket.mapper.ReturnInventoryMapper;
import edu.bistu.campusmarket.mapper.ReturnRequestMapper;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Escrow;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Wallet;
import edu.bistu.campusmarket.service.RefundService;
import edu.bistu.campusmarket.service.ReturnReceiptService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class RefundServiceTest {
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal SELLER_NET = new BigDecimal("99.00");
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.parse("2026-11-20T09:00:00.123");
    private final RefundMapper refunds = mock(RefundMapper.class);
    private final ReturnRequestMapper requests = mock(ReturnRequestMapper.class);
    private final SettlementMapper settlement = mock(SettlementMapper.class);
    private final OrderFulfillmentMapper ownership = mock(OrderFulfillmentMapper.class);
    private final WalletPaymentMapper wallets = mock(WalletPaymentMapper.class);
    private final RefundService service = new RefundService(refunds, requests, settlement, ownership, wallets);

    @BeforeEach
    void originalOrder() {
        when(requests.findOrderIdForSeller(2L, 3L)).thenReturn(1L);
        when(settlement.lockOrder(1L)).thenReturn(order(AMOUNT));
        when(requests.lockRequest(1L, 3L)).thenReturn(request("APPROVED"));
        when(ownership.findSellerUserId(5L)).thenReturn(2L);
        when(ownership.findBuyerId(4L)).thenReturn(6L);
        when(settlement.lockEscrow(1L)).thenReturn(new Escrow(7L, 8L, AMOUNT, ZERO, ZERO));
        when(wallets.lockWallet(6L)).thenReturn(new Wallet(9L, ZERO));
        when(settlement.hasMatchingBuyerDebit(8L, 1L, AMOUNT)).thenReturn(true);
    }

    @Test
    void unsettledOrderRefundsBuyerFromEscrow() {
        prepareEscrowRefund();

        assertEquals(new RefundResult(12L, "CASH_REFUNDED", AMOUNT, FundingPath.ESCROW), service.refund(2L, 3L));

        var writes = inOrder(settlement, requests, wallets, refunds);
        writes.verify(settlement).lockOrder(1L);
        writes.verify(requests).lockRequest(1L, 3L);
        writes.verify(settlement).lockEscrow(1L);
        writes.verify(wallets).lockWallet(6L);
        writes.verify(settlement).creditWallet(9L, AMOUNT);
        writes.verify(refunds).returnEscrow(7L);
        writes.verify(refunds).recordCashRefund(12L, AMOUNT, 13L, null);
        verify(wallets, never()).debit(anyLong(), any());
    }

    @Test
    void settledOrderRecoversFullCashInsteadOfSellerNet() {
        settledOrder(AMOUNT);
        prepareSellerRefund();

        assertEquals(new RefundResult(12L, "CASH_REFUNDED", AMOUNT, FundingPath.SELLER_WALLET),
                service.refund(2L, 3L));

        var writes = inOrder(wallets, refunds, settlement);
        writes.verify(wallets).lockWallet(2L);
        writes.verify(wallets).lockWallet(6L);
        writes.verify(wallets).debit(10L, AMOUNT);
        writes.verify(refunds).recordWalletEntry(eq(10L), eq(1L), eq(14L),
                eq("SELLER_REFUND_RECOVERY"), eq("DEBIT"), eq(AMOUNT), any());
        writes.verify(settlement).creditWallet(9L, AMOUNT);
        writes.verify(refunds).recordCashRefund(12L, AMOUNT, 13L, 15L);
        verify(refunds, never()).returnEscrow(7L);
    }

    @Test
    void insufficientSellerBalanceReturnsConflictAndCommitsOnlyPendingRecord() {
        settledOrder(SELLER_NET);
        prepareRefundRow(null, 11L, "PENDING_CASH", RefundService.BALANCE_INSUFFICIENT);
        var status = mock(TransactionStatus.class);
        var manager = transactionManager(status);
        var controller = new RefundController(transactionalService(manager));

        var response = controller.refund(3L, new AuthenticatedUser(2L, "USER"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(RefundService.BALANCE_INSUFFICIENT, response.getBody().code());
        assertEquals(new RefundResult(12L, "PENDING_CASH", ZERO, FundingPath.SELLER_WALLET), response.getBody().data());
        verify(manager).commit(status);
        verifyNoMoneyWrites();
    }

    @Test
    void pendingRefundCanBeRetriedWithSameRefundId() {
        settledOrder(AMOUNT);
        prepareSellerRefund();
        when(refunds.lockRefund(3L)).thenReturn(new Refund(12L, null, 11L, null, null,
                "PENDING_CASH", ZERO, RefundService.BALANCE_INSUFFICIENT, null));

        assertEquals(12L, service.refund(2L, 3L).refundId());

        verify(refunds, never()).createRefund(anyLong(), any(), any(), any(), any(), any());
        verify(refunds).recordCashRefund(12L, AMOUNT, 13L, 15L);
    }

    @Test
    void pointsOnlyOrderCreatesNoCashFlow() {
        when(settlement.lockOrder(1L)).thenReturn(order(ZERO));
        when(settlement.lockEscrow(1L)).thenReturn(null);
        when(refunds.hasPointsOnlyFunding(1L)).thenReturn(true);
        prepareRefundRow(null, null, "PENDING_POINTS", null);

        var response = new RefundController(service).refund(3L, new AuthenticatedUser(2L, "USER"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(new RefundResult(12L, "PENDING_POINTS", ZERO, FundingPath.POINTS_ONLY), response.getBody().data());
        verifyNoInteractions(wallets);
        verifyNoMoneyWrites();
    }

    @Test
    void completedPointsOnlyRefundReplayReturnsFinalResultWithoutNewCashFlow() {
        when(settlement.lockOrder(1L)).thenReturn(order(ZERO));
        when(settlement.lockEscrow(1L)).thenReturn(null);
        when(refunds.hasPointsOnlyFunding(1L)).thenReturn(true);
        when(refunds.lockRefund(3L)).thenReturn(new Refund(12L, null, null, null, null,
                "COMPLETED", ZERO, null, CONFIRMED_AT.plusHours(1)));

        var response = new RefundController(service).refund(3L, new AuthenticatedUser(2L, "USER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new RefundResult(12L, "COMPLETED", ZERO, FundingPath.POINTS_ONLY), response.getBody().data());
        verify(refunds, never()).createRefund(anyLong(), any(), any(), any(), any(), any());
        verifyNoInteractions(wallets);
        verifyNoMoneyWrites();
    }

    @Test
    void successfulRefundReplayDoesNotMakeAnyMoneyWrites() {
        when(refunds.lockRefund(3L)).thenReturn(creditedEscrowRefund());

        assertEquals(new RefundResult(12L, "CASH_REFUNDED", AMOUNT, FundingPath.ESCROW), service.refund(2L, 3L));
        verifyNoInteractions(wallets);
        verifyNoMoneyWrites();
    }

    @Test
    void aSecondReturnRequestCannotRefundTheSameOrder() {
        when(refunds.hasOtherCashRefund(1L, 3L)).thenReturn(true);
        assertCode("ORDER_CASH_ALREADY_REFUNDED", 2L);
        verifyNoMoneyWrites();
    }

    @Test
    void anotherSellerCannotRefund() {
        when(requests.findOrderIdForSeller(17L, 3L)).thenReturn(null);
        assertCode("RETURN_REQUEST_NOT_FOUND", 17L);
        verifyNoInteractions(refunds, settlement, wallets);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED"})
    void onlyApprovedRequestCanBeRefunded(String status) {
        when(requests.lockRequest(1L, 3L)).thenReturn(request(status));
        assertCode("RETURN_NOT_APPROVED", 2L);
        verifyNoInteractions(refunds, wallets);
    }

    @Test
    void missingEscrowIsAnErrorForCashOrder() {
        when(settlement.lockEscrow(1L)).thenReturn(null);
        assertCode("REFUND_FUNDING_CONFLICT", 2L);
        verifyNoMoneyWrites();
    }

    @Test
    void missingBuyerPaymentPreventsRefund() {
        when(settlement.hasMatchingBuyerDebit(8L, 1L, AMOUNT)).thenReturn(false);
        assertCode("REFUND_FUNDING_CONFLICT", 2L);
        verifyNoMoneyWrites();
    }

    @Test
    void settlementAndEscrowMismatchPreventsRecovery() {
        settledOrder(AMOUNT);
        when(refunds.lockOriginalSettlement(7L)).thenReturn(
                new OriginalSettlement(11L, 14L, AMOUNT, SELLER_NET, new BigDecimal("1.00"), SELLER_NET));
        assertCode("REFUND_FUNDING_CONFLICT", 2L);
        verifyNoMoneyWrites();
    }

    @Test
    void missingWalletsCannotCreateRefundMoney() {
        when(wallets.lockWallet(6L)).thenReturn(null);
        assertCode("BUYER_WALLET_NOT_FOUND", 2L);
        settledOrder(AMOUNT);
        when(wallets.lockWallet(6L)).thenReturn(new Wallet(9L, ZERO));
        when(wallets.lockWallet(2L)).thenReturn(null);
        assertCode("SELLER_WALLET_NOT_FOUND", 2L);
        verifyNoMoneyWrites();
    }

    @Test
    void failedBuyerCreditRollsBackPendingRecord() {
        prepareRefundRow(7L, null, "PENDING_CASH", null);
        var status = mock(TransactionStatus.class);
        var manager = transactionManager(status);

        assertThrows(IllegalStateException.class, () -> transactionalService(manager).refund(2L, 3L));

        verify(manager).rollback(status);
        verify(manager, never()).commit(status);
        verify(refunds, never()).returnEscrow(7L);
    }

    @Test
    void failedBuyerLedgerWriteRollsBackSellerDebitAndBuyerBalance() {
        settledOrder(AMOUNT);
        prepareSellerRefund();
        when(refunds.recordWalletEntry(eq(9L), eq(1L), eq(8L), eq("BUYER_REFUND"), eq("CREDIT"), eq(AMOUNT), any()))
                .thenReturn(0);
        var status = mock(TransactionStatus.class);
        var manager = transactionManager(status);

        assertThrows(IllegalStateException.class, () -> transactionalService(manager).refund(2L, 3L));

        verify(wallets).debit(10L, AMOUNT);
        verify(settlement).creditWallet(9L, AMOUNT);
        verify(manager).rollback(status);
        verify(manager, never()).commit(status);
        verify(refunds, never()).recordCashRefund(eq(12L), any(), anyLong(), any());
    }

    @Test
    void concurrentRefundsWithSharedOrderLockWriteCashOnce() throws Exception {
        prepareEscrowRefund();
        var lock = new ReentrantLock();
        var attempts = new CountDownLatch(2);
        var recorded = new AtomicReference<Refund>();
        when(settlement.lockOrder(1L)).thenAnswer(invocation -> {
            attempts.countDown();
            if (!attempts.await(5, TimeUnit.SECONDS) || !lock.tryLock(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test could not acquire simulated order lock");
            }
            return order(AMOUNT);
        });
        when(refunds.lockRefund(3L)).thenAnswer(invocation -> recorded.get());
        when(refunds.recordCashRefund(12L, AMOUNT, 13L, null)).thenAnswer(invocation -> {
            recorded.set(creditedEscrowRefund());
            return 1;
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> refundWithSimulatedLock(lock));
            var second = executor.submit(() -> refundWithSimulatedLock(lock));
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        verify(settlement).creditWallet(9L, AMOUNT);
        verify(refunds).returnEscrow(7L);
        verify(refunds).recordCashRefund(12L, AMOUNT, 13L, null);
    }

    @Test
    void receiptAndRefundSerializeOnOrderAndEachWriteOnlyOnce() throws Exception {
        prepareEscrowRefund();
        var inventory = mock(ReturnInventoryMapper.class);
        var receipts = new ReturnReceiptService(requests, settlement, ownership, inventory);
        var lock = new ReentrantLock();
        var receiving = new CountDownLatch(1);
        var refundWaiting = new CountDownLatch(1);
        var savedRequest = new AtomicReference<>(request("APPROVED"));
        var savedRefund = new AtomicReference<Refund>();
        when(settlement.lockOrder(1L)).thenAnswer(i -> {
            if (lock.isLocked()) {
                refundWaiting.countDown();
            }
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test could not acquire simulated order lock");
            }
            return order(AMOUNT);
        });
        when(requests.lockRequest(1L, 3L)).thenAnswer(i -> savedRequest.get());
        when(inventory.lockItems(1L)).thenReturn(List.of(new ReturnInventoryMapper.Item(20L, 21L, 1, 0)));
        when(inventory.restoreAvailable(21L, 5L, 1)).thenReturn(1);
        when(inventory.recordRestockedQuantity(20L, 1)).thenReturn(1);
        when(inventory.recordRestock(21L, 20L, 3L, 1, 2L)).thenReturn(1);
        when(inventory.findRecordedRestocks(1L, 3L)).thenReturn(List.of(new RestockedItem(20L, 21L, 1)));
        when(requests.markReturned(3L)).thenAnswer(i -> {
            receiving.countDown();
            assertTrue(refundWaiting.await(5, TimeUnit.SECONDS));
            savedRequest.set(request("RETURNED"));
            return 1;
        });
        when(requests.audit(2L, 3L, "RETURN_REQUEST_RECEIVE")).thenReturn(1);
        when(refunds.lockRefund(3L)).thenAnswer(i -> savedRefund.get());
        when(refunds.recordCashRefund(12L, AMOUNT, 13L, null)).thenAnswer(i -> {
            savedRefund.set(creditedEscrowRefund());
            return 1;
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            var receive = executor.submit(() -> {
                try {
                    return receipts.receive(2L, 3L);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });
            assertTrue(receiving.await(5, TimeUnit.SECONDS));
            var refund = executor.submit(() -> refundWithSimulatedLock(lock));
            assertEquals("RETURNED", receive.get(10, TimeUnit.SECONDS).request().requestStatus());
            assertEquals("CASH_REFUNDED", refund.get(10, TimeUnit.SECONDS).refundStatus());
        } finally {
            executor.shutdownNow();
        }
        verify(inventory).restoreAvailable(21L, 5L, 1);
        verify(settlement).creditWallet(9L, AMOUNT);
        verify(refunds).recordCashRefund(12L, AMOUNT, 13L, null);
    }

    private void settledOrder(BigDecimal sellerBalance) {
        when(settlement.lockEscrow(1L)).thenReturn(new Escrow(7L, 8L, AMOUNT, AMOUNT, ZERO));
        when(refunds.lockOriginalSettlement(7L)).thenReturn(
                new OriginalSettlement(11L, 14L, AMOUNT, AMOUNT, new BigDecimal("1.00"), SELLER_NET));
        when(wallets.lockWallet(2L)).thenReturn(new Wallet(10L, sellerBalance));
        when(refunds.hasMatchingSellerCredit(14L, 10L, 1L, SELLER_NET)).thenReturn(true);
    }

    private void prepareEscrowRefund() {
        prepareRefundRow(7L, null, "PENDING_CASH", null);
        prepareBuyerCredit();
        when(refunds.returnEscrow(7L)).thenReturn(1);
        when(refunds.recordCashRefund(12L, AMOUNT, 13L, null)).thenReturn(1);
    }

    private void prepareSellerRefund() {
        prepareRefundRow(null, 11L, "PENDING_CASH", null);
        prepareBuyerCredit();
        when(wallets.debit(10L, AMOUNT)).thenReturn(1);
        when(refunds.recordWalletEntry(eq(10L), eq(1L), eq(14L), eq("SELLER_REFUND_RECOVERY"), eq("DEBIT"),
                eq(AMOUNT), any())).thenAnswer(i -> generate(i.getArgument(6), 15L));
        when(refunds.recordCashRefund(12L, AMOUNT, 13L, 15L)).thenReturn(1);
    }

    private void prepareBuyerCredit() {
        when(settlement.creditWallet(9L, AMOUNT)).thenReturn(1);
        when(refunds.recordWalletEntry(eq(9L), eq(1L), eq(8L), eq("BUYER_REFUND"), eq("CREDIT"), eq(AMOUNT), any()))
                .thenAnswer(i -> generate(i.getArgument(6), 13L));
    }

    private void prepareRefundRow(Long escrowId, Long settlementId, String status, String failure) {
        when(refunds.createRefund(eq(3L), eq(escrowId), eq(settlementId), eq(status), eq(failure), any()))
                .thenAnswer(i -> generate(i.getArgument(5), 12L));
    }

    private int generate(GeneratedId key, long id) {
        key.setId(id);
        return 1;
    }

    private Refund creditedEscrowRefund() {
        return new Refund(12L, 7L, null, 13L, null, "CASH_REFUNDED", AMOUNT, null, null);
    }

    private Order order(BigDecimal amount) {
        return new Order(1L, 4L, 5L, "COMPLETED", CONFIRMED_AT, null, amount, amount);
    }

    private Item request(String status) {
        return new Item(3L, 1L, "order-1", "COMPLETED", AMOUNT, status, true, "Damaged", CONFIRMED_AT, null, null,
                "RETURNED".equals(status) ? CONFIRMED_AT.plusHours(1) : null);
    }

    private void assertCode(String code, long seller) {
        assertEquals(code, assertThrows(BusinessException.class, () -> service.refund(seller, 3L)).code());
    }

    private void verifyNoMoneyWrites() {
        verify(wallets, never()).debit(anyLong(), any());
        verify(settlement, never()).creditWallet(anyLong(), any());
        verify(refunds, never()).recordWalletEntry(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
        verify(refunds, never()).returnEscrow(anyLong());
        verify(refunds, never()).recordCashRefund(anyLong(), any(), anyLong(), any());
    }

    private RefundResult refundWithSimulatedLock(ReentrantLock lock) {
        try {
            return service.refund(2L, 3L);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private PlatformTransactionManager transactionManager(TransactionStatus status) {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        return manager;
    }

    private RefundService transactionalService(PlatformTransactionManager manager) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        return (RefundService) factory.getProxy();
    }
}
