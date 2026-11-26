package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.ReturnRequestDtos.Application;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Decision;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Item;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Review;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Status;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.ReturnRequestMapper;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Escrow;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import edu.bistu.campusmarket.mapper.SettlementMapper.Settlement;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import edu.bistu.campusmarket.service.ReturnRequestService;
import edu.bistu.campusmarket.service.NotificationService;
import edu.bistu.campusmarket.service.SettlementService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class ReturnRequestServiceTest {
    private static final LocalDateTime CONFIRMED = LocalDateTime.parse("2026-11-20T08:00:00.123");
    private static final LocalDateTime APPLIED = CONFIRMED.plusHours(24).minusNanos(1_000_000);
    private static final BigDecimal PRINCIPAL = new BigDecimal("100.00");
    private static final Application INPUT = new Application("return-1", "Item damaged");
    private final ReturnRequestMapper requests = mock(ReturnRequestMapper.class);
    private final SettlementMapper settlement = mock(SettlementMapper.class);
    private final OrderFulfillmentMapper ownership = mock(OrderFulfillmentMapper.class);
    private final ReturnRequestService service = new ReturnRequestService(
            requests, settlement, ownership, mock(NotificationService.class));

    @BeforeEach
    void paidOrder() {
        when(settlement.lockOrder(1L)).thenReturn(order("COMPLETED", CONFIRMED, null));
        when(settlement.lockEscrow(1L)).thenReturn(new Escrow(2L, 7L, PRINCIPAL, BigDecimal.ZERO, BigDecimal.ZERO));
        when(settlement.lockActiveReturnId(1L)).thenReturn(null);
        when(ownership.findBuyerId(3L)).thenReturn(4L);
        when(ownership.findSellerUserId(5L)).thenReturn(6L);
        when(settlement.utcNow()).thenReturn(APPLIED);
        when(settlement.settlementCutoff()).thenReturn(CONFIRMED);
    }

    @Test
    void buyerCanApplyOneMillisecondBeforeTheDeadlineUsingAutomaticConfirmationTime() {
        when(settlement.lockOrder(1L)).thenReturn(order("COMPLETED", null, CONFIRMED));
        prepareInsert();

        assertEquals(item(Status.PENDING, true, null), service.apply(4L, 1L, INPUT));

        var reads = inOrder(settlement, requests);
        reads.verify(settlement).lockOrder(1L);
        reads.verify(settlement).findSettlement(2L);
        reads.verify(settlement).lockActiveReturnId(1L);
        reads.verify(requests).lockByRequestKey(1L, INPUT.requestKey());
        reads.verify(requests).insert(eq(1L), eq(INPUT.requestKey()), eq(INPUT.reason()), eq(APPLIED), any());
    }

    @Test
    void otherBuyerCannotApply() {
        assertEquals("ORDER_NOT_FOUND", assertThrows(BusinessException.class,
                () -> service.apply(8L, 1L, INPUT)).code());
        verifyNoInteractions(requests);
    }

    @Test
    void nonCompletedOrderCannotApply() {
        when(settlement.lockOrder(1L)).thenReturn(order("DELIVERED", null, null));
        assertApplicationRejected("ORDER_NOT_RETURNABLE");
    }

    @ParameterizedTest
    @ValueSource(ints = {24, 25})
    void cannotApplyAtOrAfterSettlementBoundary(int elapsedHours) {
        when(settlement.utcNow()).thenReturn(CONFIRMED.plusHours(elapsedHours));
        assertApplicationRejected("RETURN_WINDOW_CLOSED");
    }

    @Test
    void settledOrderCannotApply() {
        when(settlement.findSettlement(2L)).thenReturn(mock(Settlement.class));
        assertApplicationRejected("ORDER_NOT_RETURNABLE");
    }

    @Test
    void aDifferentKeyCannotCreateASecondActiveRequest() {
        when(settlement.lockActiveReturnId(1L)).thenReturn(10L);
        assertApplicationRejected("ACTIVE_RETURN_REQUEST_EXISTS");
    }

    @Test
    void retryAfterRejectionReturnsTheSameRequestWithoutCreatingAnother() {
        Item rejected = item(Status.REJECTED, false, "Seller rejected");
        when(requests.lockByRequestKey(1L, INPUT.requestKey())).thenReturn(rejected);
        when(settlement.utcNow()).thenReturn(CONFIRMED.plusHours(25));

        assertEquals(rejected, service.apply(4L, 1L, INPUT));
        assertEquals("RETURN_REQUEST_KEY_CONFLICT", assertThrows(BusinessException.class,
                () -> service.apply(4L, 1L, new Application(INPUT.requestKey(), "Different reason"))).code());
        verify(requests, never()).insert(eq(1L), any(), any(), any(), any());
    }

    @Test
    void sellerQueriesAreScopedAndPaged() {
        Item pending = item(Status.PENDING, true, null);
        when(requests.findForSeller(6L, 10L)).thenReturn(pending);
        when(requests.countForSeller(6L, Status.PENDING)).thenReturn(21L);
        when(requests.pageForSeller(6L, Status.PENDING, 10, 20L)).thenReturn(List.of(pending));

        assertEquals(pending, service.find(6L, 10L));
        var page = service.list(6L, Status.PENDING, 3, 10);
        assertEquals(List.of(pending), page.items());
        assertEquals(21L, page.total());
        assertEquals("RETURN_REQUEST_NOT_FOUND", assertThrows(BusinessException.class,
                () -> service.find(8L, 10L)).code());
        assertEquals("RETURN_REQUEST_NOT_FOUND", assertThrows(BusinessException.class,
                () -> service.review(8L, 10L, new Review(Decision.APPROVE, null))).code());
    }

    @Test
    void approvalIsAuditedAndKeepsSettlementBlocked() {
        Item approved = prepareReview(Status.APPROVED, null);

        assertEquals(approved, service.review(6L, 10L, new Review(Decision.APPROVE, null)));

        var writes = inOrder(settlement, requests);
        writes.verify(settlement).lockOrder(1L);
        writes.verify(settlement).lockActiveReturnId(1L);
        writes.verify(requests).lockRequest(1L, 10L);
        writes.verify(requests).review(10L, 6L, Status.APPROVED, true, null);
        writes.verify(requests).audit(6L, 10L, "RETURN_REQUEST_APPROVE");
        var wallets = mock(WalletPaymentMapper.class);
        assertTrue(new SettlementService(settlement, wallets, mock(NotificationService.class)).settle(1L).isEmpty());
        verifyNoInteractions(wallets);
    }

    @Test
    void rejectionReleasesActiveSlotAndSettlementPassesReturnCheck() {
        Item rejected = prepareReview(Status.REJECTED, "No defect");
        assertEquals(rejected, service.review(6L, 10L, new Review(Decision.REJECT, " No defect ")));
        verify(requests).review(10L, 6L, Status.REJECTED, false, "No defect");
        verify(requests).audit(6L, 10L, "RETURN_REQUEST_REJECT");

        // The returned request is inactive; settlement advances to funding validation.
        when(settlement.lockActiveReturnId(1L)).thenReturn(null);
        var wallets = mock(WalletPaymentMapper.class);
        assertThrows(IllegalStateException.class,
                () -> new SettlementService(settlement, wallets, mock(NotificationService.class)).settle(1L));
        verify(settlement).hasMatchingBuyerDebit(7L, 1L, PRINCIPAL);
    }

    @Test
    void approvedRequestCannotBeReviewedAgain() {
        prepareReview(Status.APPROVED, null);
        when(requests.lockRequest(1L, 10L)).thenReturn(item(Status.APPROVED, true, null));

        assertEquals("RETURN_REQUEST_ALREADY_REVIEWED", assertThrows(BusinessException.class,
                () -> service.review(6L, 10L, new Review(Decision.REJECT, "Late reversal"))).code());
        verify(requests, never()).audit(eq(6L), eq(10L), any());
    }

    @Test
    void missingAuditRollsBackReview() {
        prepareReview(Status.APPROVED, null);
        when(requests.audit(6L, 10L, "RETURN_REQUEST_APPROVE")).thenReturn(0);
        var transaction = mock(TransactionStatus.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        var target = (ReturnRequestService) factory.getProxy();

        assertThrows(IllegalStateException.class, () -> target.review(6L, 10L, new Review(Decision.APPROVE, null)));
        verify(manager).rollback(transaction);
        verify(manager, never()).commit(transaction);
    }

    @Test
    void concurrentSettlementWaitsForApplicationAndSeesItsActiveRequest() throws Exception {
        prepareInsert();
        var lock = new ReentrantLock();
        var applicationWriting = new CountDownLatch(1);
        var settlementWaiting = new CountDownLatch(1);
        var activeRequest = new AtomicReference<Long>();
        when(settlement.lockOrder(1L)).thenAnswer(invocation -> {
            if (lock.isLocked()) {
                settlementWaiting.countDown();
            }
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test could not acquire simulated order lock");
            }
            return order("COMPLETED", CONFIRMED, null);
        });
        when(settlement.lockActiveReturnId(1L)).thenAnswer(invocation -> activeRequest.get());
        when(requests.insert(eq(1L), eq(INPUT.requestKey()), eq(INPUT.reason()), eq(APPLIED), any()))
                .thenAnswer(invocation -> {
                    applicationWriting.countDown();
                    assertTrue(settlementWaiting.await(5, TimeUnit.SECONDS));
                    GeneratedId key = invocation.getArgument(4);
                    key.setId(10L);
                    activeRequest.set(10L);
                    return 1;
                });
        var wallets = mock(WalletPaymentMapper.class);
        var payout = new SettlementService(settlement, wallets, mock(NotificationService.class));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var application = executor.submit(() -> {
                try {
                    return service.apply(4L, 1L, INPUT);
                } finally {
                    lock.unlock();
                }
            });
            assertTrue(applicationWriting.await(5, TimeUnit.SECONDS));
            var release = executor.submit(() -> {
                try {
                    return payout.settle(1L);
                } finally {
                    lock.unlock();
                }
            });
            assertEquals(10L, application.get(10, TimeUnit.SECONDS).id());
            assertTrue(release.get(10, TimeUnit.SECONDS).isEmpty());
        } finally {
            executor.shutdownNow();
        }
        verifyNoInteractions(wallets);
    }

    private void prepareInsert() {
        when(requests.insert(eq(1L), eq(INPUT.requestKey()), eq(INPUT.reason()), eq(APPLIED), any()))
                .thenAnswer(invocation -> {
                    GeneratedId key = invocation.getArgument(4);
                    key.setId(10L);
                    return 1;
                });
        when(requests.lockRequest(1L, 10L)).thenReturn(item(Status.PENDING, true, null));
    }

    private Item prepareReview(Status result, String reason) {
        boolean approved = result == Status.APPROVED;
        Item reviewed = item(result, approved, reason);
        when(requests.findOrderIdForSeller(6L, 10L)).thenReturn(1L);
        when(settlement.lockActiveReturnId(1L)).thenReturn(10L);
        when(requests.lockRequest(1L, 10L)).thenReturn(item(Status.PENDING, true, null), reviewed);
        when(requests.review(10L, 6L, result, approved, reason)).thenReturn(1);
        when(requests.audit(6L, 10L, approved ? "RETURN_REQUEST_APPROVE" : "RETURN_REQUEST_REJECT")).thenReturn(1);
        return reviewed;
    }

    private void assertApplicationRejected(String code) {
        assertEquals(code, assertThrows(BusinessException.class, () -> service.apply(4L, 1L, INPUT)).code());
        verify(requests, never()).insert(eq(1L), any(), any(), any(), any());
    }

    private Order order(String status, LocalDateTime manual, LocalDateTime automatic) {
        return new Order(1L, 3L, 5L, status, manual, automatic, PRINCIPAL, PRINCIPAL);
    }

    private Item item(Status status, boolean active, String opinion) {
        return new Item(10L, 1L, "Order-1", "COMPLETED", PRINCIPAL, status.name(), active,
                INPUT.reason(), APPLIED, opinion, status == Status.PENDING ? null : APPLIED.plusHours(1), null);
    }
}
