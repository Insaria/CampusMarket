package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper.Order;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper.Reservation;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import edu.bistu.campusmarket.service.OrderFulfillmentService;
import edu.bistu.campusmarket.service.NotificationService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class OrderFulfillmentTest {
    private static final LocalDateTime DELIVERED_AT = LocalDateTime.parse("2026-11-19T08:30:00.125");
    private static final LocalDateTime CONFIRMED_AT = DELIVERED_AT.plusMinutes(10);
    private final OrderFulfillmentMapper mapper = mock(OrderFulfillmentMapper.class);
    private final WalletPaymentMapper events = mock(WalletPaymentMapper.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final OrderFulfillmentService service = new OrderFulfillmentService(mapper, events, notifications);

    @BeforeEach
    void owners() {
        when(mapper.findSellerUserId(3L)).thenReturn(4L);
        when(mapper.findBuyerId(2L)).thenReturn(5L);
    }

    @Test
    void sellerDeliveryMovesPaidOrderAndRecordsActor() {
        prepareDelivery(new Reservation(7L, 11L, 2));
        prepareConsumption(7L, 11L, 2);

        var result = service.deliver(4L, 1L);

        assertEquals("DELIVERED", result.orderStatus());
        assertEquals(Instant.parse("2026-11-19T08:30:00.125Z"), result.deliveredAt());
        assertNull(result.buyerConfirmedAt());
        verify(mapper).consumeReservedStock(11L, 2);
        verify(mapper).recordConsumedQuantity(7L, 2);
        verify(mapper).recordStockConsumption(11L, 7L, 2, 4L);
        verify(mapper).recordTransition(1L, 3, "SELLER_DELIVERY", "PAID", "DELIVERED", 4L);
    }

    @Test
    void consumesEachProductsOutstandingQuantityBeforeMarkingDelivery() {
        prepareDelivery(new Reservation(7L, 11L, 2), new Reservation(8L, 14L, 3), new Reservation(9L, 25L, 0));
        prepareConsumption(7L, 11L, 2);
        prepareConsumption(8L, 14L, 3);

        service.deliver(4L, 1L);

        var writes = inOrder(mapper);
        writes.verify(mapper).consumeReservedStock(11L, 2);
        writes.verify(mapper).recordStockConsumption(11L, 7L, 2, 4L);
        writes.verify(mapper).consumeReservedStock(14L, 3);
        writes.verify(mapper).recordStockConsumption(14L, 8L, 3, 4L);
        writes.verify(mapper).markDelivered(1L);
        verify(mapper, never()).consumeReservedStock(25L, 0);
    }

    @Test
    void repeatedDeliveryConsumesStockOnlyOnce() {
        prepareDelivery(new Reservation(7L, 11L, 2));
        prepareConsumption(7L, 11L, 2);

        assertEquals(service.deliver(4L, 1L), service.deliver(4L, 1L));

        verify(mapper).lockReservations(1L);
        verify(mapper).consumeReservedStock(11L, 2);
        verify(mapper).recordStockConsumption(11L, 7L, 2, 4L);
    }

    @Test
    void insufficientStockOnLaterItemRollsBackEarlierWrites() {
        when(mapper.lockOrder(1L)).thenReturn(order("PAID", null, null));
        when(mapper.lockReservations(1L)).thenReturn(List.of(
                new Reservation(7L, 11L, 2), new Reservation(8L, 14L, 3)));
        prepareConsumption(7L, 11L, 2);
        var manager = mock(PlatformTransactionManager.class);
        var transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        var target = transactionalService(manager);

        assertEquals("STOCK_CONSUME_CONFLICT",
                assertThrows(BusinessException.class, () -> target.deliver(4L, 1L)).code());

        verify(mapper).recordStockConsumption(11L, 7L, 2, 4L);
        verify(manager).rollback(transaction);
        verify(manager, never()).commit(transaction);
        verify(mapper, never()).markDelivered(1L);
    }

    @Test
    void inventoryEventFailurePreventsDeliveryAndRollsBack() {
        when(mapper.lockOrder(1L)).thenReturn(order("PAID", null, null));
        when(mapper.lockReservations(1L)).thenReturn(List.of(new Reservation(7L, 11L, 2)));
        when(mapper.consumeReservedStock(11L, 2)).thenReturn(1);
        when(mapper.recordConsumedQuantity(7L, 2)).thenReturn(1);
        var manager = mock(PlatformTransactionManager.class);
        var transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);

        assertThrows(IllegalStateException.class, () -> transactionalService(manager).deliver(4L, 1L));

        verify(manager).rollback(transaction);
        verify(manager, never()).commit(transaction);
        verify(mapper, never()).markDelivered(1L);
        verifyNoInteractions(events);
    }

    @Test
    void buyerConfirmsDeliveredOrderAndKeepsDeliveryTimestamp() {
        when(mapper.lockOrder(1L)).thenReturn(order("DELIVERED", DELIVERED_AT, null),
                order("COMPLETED", DELIVERED_AT, CONFIRMED_AT));
        when(mapper.markBuyerConfirmed(1L)).thenReturn(1);
        when(events.lastEventSequence(1L)).thenReturn(3);
        when(mapper.recordTransition(1L, 4, "BUYER_CONFIRM_RECEIPT", "DELIVERED", "COMPLETED", 5L)).thenReturn(1);

        var result = service.confirmReceipt(5L, 1L);

        assertEquals("COMPLETED", result.orderStatus());
        assertEquals(Instant.parse("2026-11-19T08:30:00.125Z"), result.deliveredAt());
        assertEquals(Instant.parse("2026-11-19T08:40:00.125Z"), result.buyerConfirmedAt());
        assertNull(result.autoConfirmedAt());
        verify(mapper).recordTransition(1L, 4, "BUYER_CONFIRM_RECEIPT", "DELIVERED", "COMPLETED", 5L);
        verify(notifications).emit(NotificationService.Event.RECEIPT_CONFIRMED, 1L, 1L);
        verify(notifications).emit(NotificationService.Event.BUYER_RECEIPT_CONFIRMED, 1L, 1L);
    }

    @Test
    void rejectsNonOwnersAndPrematureReceipt() {
        when(mapper.lockOrder(1L)).thenReturn(order("PAID", null, null));

        assertEquals("ORDER_NOT_FOUND", assertThrows(BusinessException.class, () -> service.deliver(6L, 1L)).code());
        assertEquals("ORDER_NOT_FOUND", assertThrows(BusinessException.class,
                () -> service.confirmReceipt(6L, 1L)).code());
        assertEquals("ORDER_NOT_CONFIRMABLE", assertThrows(BusinessException.class,
                () -> service.confirmReceipt(5L, 1L)).code());

        verify(mapper, never()).markDelivered(1L);
        verify(mapper, never()).markBuyerConfirmed(1L);
        verifyNoInteractions(events);
    }

    @Test
    void retriesAfterCompletionKeepOriginalTimestampsAndWriteNoEvents() {
        when(mapper.lockOrder(1L)).thenReturn(order("COMPLETED", DELIVERED_AT, CONFIRMED_AT));

        assertEquals(service.deliver(4L, 1L), service.confirmReceipt(5L, 1L));

        verify(mapper, never()).markDelivered(1L);
        verify(mapper, never()).markBuyerConfirmed(1L);
        verifyNoInteractions(events);
    }

    @Test
    void automaticConfirmationCannotBeReplacedByManualConfirmation() {
        when(mapper.lockOrder(1L)).thenReturn(new Order(1L, 2L, 3L, "COMPLETED",
                DELIVERED_AT, null, DELIVERED_AT.plusDays(7)));

        assertEquals("ORDER_NOT_CONFIRMABLE", assertThrows(BusinessException.class,
                () -> service.confirmReceipt(5L, 1L)).code());

        verify(mapper, never()).markBuyerConfirmed(1L);
        verifyNoInteractions(events);
    }

    @Test
    void autoConfirmationAtExactDeadlineRecordsOneEventAcrossRetries() {
        when(mapper.lockOrder(1L)).thenReturn(order("DELIVERED", DELIVERED_AT, null),
                new Order(1L, 2L, 3L, "COMPLETED", DELIVERED_AT, null, DELIVERED_AT.plusDays(7)));
        when(mapper.markAutoConfirmed(1L, DELIVERED_AT)).thenReturn(1);
        when(events.lastEventSequence(1L)).thenReturn(3);
        when(mapper.recordAutoConfirmation(1L, 4)).thenReturn(1);

        assertTrue(service.autoConfirm(1L, DELIVERED_AT));
        assertFalse(service.autoConfirm(1L, DELIVERED_AT));

        var writes = inOrder(mapper, events);
        writes.verify(mapper).lockOrder(1L);
        writes.verify(mapper).markAutoConfirmed(1L, DELIVERED_AT);
        writes.verify(events).lastEventSequence(1L);
        writes.verify(mapper).recordAutoConfirmation(1L, 4);
        verify(mapper, never()).markBuyerConfirmed(1L);
        verify(mapper, never()).lockReservations(1L);
    }

    @Test
    void autoConfirmationSkipsOrderOneMillisecondBeforeDeadline() {
        when(mapper.lockOrder(1L)).thenReturn(order("DELIVERED", DELIVERED_AT, null));
        LocalDateTime earlyCutoff = DELIVERED_AT.minusNanos(1_000_000);

        assertFalse(service.autoConfirm(1L, earlyCutoff));

        verify(mapper, never()).markAutoConfirmed(1L, earlyCutoff);
        verifyNoInteractions(events);
    }

    @Test
    void manualConfirmationBeforeAutoTaskAcquiresTheLockWins() {
        when(mapper.lockOrder(1L)).thenReturn(order("COMPLETED", DELIVERED_AT, CONFIRMED_AT));

        assertFalse(service.autoConfirm(1L, DELIVERED_AT));

        verify(mapper, never()).markAutoConfirmed(1L, DELIVERED_AT);
        verifyNoInteractions(events);
    }

    @Test
    void autoConfirmationEventFailureRollsBackIndependentTransaction() {
        when(mapper.lockOrder(1L)).thenReturn(order("DELIVERED", DELIVERED_AT, null));
        when(mapper.markAutoConfirmed(1L, DELIVERED_AT)).thenReturn(1);
        when(events.lastEventSequence(1L)).thenReturn(3);
        var manager = mock(PlatformTransactionManager.class);
        var transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        var target = transactionalService(manager);

        assertThrows(IllegalStateException.class, () -> target.autoConfirm(1L, DELIVERED_AT));

        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getValue().getPropagationBehavior());
        verify(manager).rollback(transaction);
        verify(manager, never()).commit(transaction);
    }

    private Order order(String status, LocalDateTime deliveredAt, LocalDateTime confirmedAt) {
        return new Order(1L, 2L, 3L, status, deliveredAt, confirmedAt, null);
    }

    private void prepareDelivery(Reservation... reservations) {
        when(mapper.lockOrder(1L)).thenReturn(order("PAID", null, null), order("DELIVERED", DELIVERED_AT, null));
        when(mapper.lockReservations(1L)).thenReturn(List.of(reservations));
        when(mapper.markDelivered(1L)).thenReturn(1);
        when(events.lastEventSequence(1L)).thenReturn(2);
        when(mapper.recordTransition(1L, 3, "SELLER_DELIVERY", "PAID", "DELIVERED", 4L)).thenReturn(1);
    }

    private void prepareConsumption(long itemId, long productId, int quantity) {
        when(mapper.consumeReservedStock(productId, quantity)).thenReturn(1);
        when(mapper.recordConsumedQuantity(itemId, quantity)).thenReturn(1);
        when(mapper.recordStockConsumption(productId, itemId, quantity, 4L)).thenReturn(1);
    }

    private OrderFulfillmentService transactionalService(PlatformTransactionManager manager) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        return (OrderFulfillmentService) factory.getProxy();
    }
}
