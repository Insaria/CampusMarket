package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.CheckoutMapper;
import edu.bistu.campusmarket.mapper.OrderCancellationMapper;
import edu.bistu.campusmarket.mapper.OrderCancellationMapper.Item;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Batch;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Order;
import edu.bistu.campusmarket.service.OrderCancellationService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderCancellationTest {
    private static final String TARGET_STATUS = "CANCELLED";
    private final CheckoutMapper checkout = mock(CheckoutMapper.class);
    private final WalletPaymentMapper payment = mock(WalletPaymentMapper.class);
    private final OrderCancellationMapper cancellation = mock(OrderCancellationMapper.class);
    private final OrderCancellationService service = new OrderCancellationService(checkout, payment, cancellation);

    @BeforeEach
    void ownedBatch() {
        when(checkout.lockBuyer(1)).thenReturn(1L);
        when(payment.lockBatch(2, 1)).thenReturn(new Batch(2L, BigDecimal.ZERO, 0L, BigDecimal.ZERO));
    }

    @Test
    void releasesRemainingReservationsAcrossTheBatch() {
        when(payment.lockOrders(2)).thenReturn(List.of(
                new Order(3L, 9L, "PENDING_PAYMENT"), new Order(4L, 10L, "PENDING_PAYMENT")));
        when(cancellation.lockItems(3)).thenReturn(List.of(new Item(5L, 11L, 6, 0, 2)));
        when(cancellation.lockItems(4)).thenReturn(List.of(new Item(6L, 12L, 1, 0, 0)));
        prepareRelease(5, 11, 4);
        prepareRelease(6, 12, 1);
        prepareTransition(3);
        prepareTransition(4);

        var result = service.cancel(1, 2);
        assertEquals(2L, result.batchId());
        assertEquals(TARGET_STATUS, result.orderStatus());
        assertEquals(List.of(3L, 4L), result.orderIds());

        verify(cancellation).recordStockRelease(11, 5, 4, 1);
        verify(cancellation).recordStockRelease(12, 6, 1, 1);
        verify(cancellation).recordCancellation(3, 2, TARGET_STATUS, 1);
        verify(cancellation).recordCancellation(4, 2, TARGET_STATUS, 1);
    }

    @Test
    void rejectsEntireBatchIfAnyOrderIsPaid() {
        when(payment.lockOrders(2)).thenReturn(List.of(
                new Order(3L, 9L, "PENDING_PAYMENT"), new Order(4L, 10L, "PAID")));

        assertEquals("BATCH_NOT_CANCELLABLE",
                assertThrows(BusinessException.class, () -> service.cancel(1, 2)).code());

        verifyNoInteractions(cancellation);
    }

    @Test
    void retryReturnsCancelledOrdersWithoutTouchingInventory() {
        when(payment.lockOrders(2)).thenReturn(List.of(new Order(3L, 9L, TARGET_STATUS)));

        var result = service.cancel(1, 2);
        assertEquals(TARGET_STATUS, result.orderStatus());
        assertEquals(List.of(3L), result.orderIds());

        verifyNoInteractions(cancellation);
    }

    private void prepareRelease(long itemId, long productId, int quantity) {
        when(cancellation.releaseStock(productId, quantity)).thenReturn(1);
        when(cancellation.recordReleasedQuantity(itemId, quantity)).thenReturn(1);
        when(cancellation.recordStockRelease(productId, itemId, quantity, 1)).thenReturn(1);
    }

    private void prepareTransition(long orderId) {
        when(cancellation.markCancelled(orderId, TARGET_STATUS)).thenReturn(1);
        when(payment.lastEventSequence(orderId)).thenReturn(1);
        when(cancellation.recordCancellation(orderId, 2, TARGET_STATUS, 1)).thenReturn(1);
    }
}
