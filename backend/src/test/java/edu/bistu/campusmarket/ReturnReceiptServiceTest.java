package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.ReturnRequestDtos.Item;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.ReceiptConfirmation;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.RestockedItem;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.ReturnInventoryMapper;
import edu.bistu.campusmarket.mapper.ReturnRequestMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import edu.bistu.campusmarket.service.ReturnReceiptService;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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

class ReturnReceiptServiceTest {
    private static final LocalDateTime RECEIVED_AT = LocalDateTime.parse("2026-11-24T09:00:00.123");
    private static final BigDecimal PRICE = new BigDecimal("35.00");
    private final ReturnRequestMapper requests = mock(ReturnRequestMapper.class);
    private final SettlementMapper orders = mock(SettlementMapper.class);
    private final OrderFulfillmentMapper ownership = mock(OrderFulfillmentMapper.class);
    private final ReturnInventoryMapper inventory = mock(ReturnInventoryMapper.class);
    private final ReturnReceiptService service = new ReturnReceiptService(requests, orders, ownership, inventory);

    @BeforeEach
    void approvedReturn() {
        when(requests.findOrderIdForSeller(2L, 3L)).thenReturn(1L);
        when(orders.lockOrder(1L)).thenReturn(new Order(1L, 4L, 5L, "COMPLETED",
                RECEIVED_AT.minusDays(1), null, PRICE, PRICE));
        when(ownership.findSellerUserId(5L)).thenReturn(2L);
        when(requests.lockRequest(1L, 3L)).thenReturn(request("APPROVED", null));
    }

    @Test
    void receivesWholeOrderAndRestocksOnlyRemainingConsumedQuantity() {
        prepareRestock();

        var result = service.receive(2L, 3L);

        assertEquals(request("RETURNED", RECEIVED_AT), result.request());
        assertEquals(List.of(new RestockedItem(8L, 10L, 3), new RestockedItem(9L, 11L, 1)), result.items());
        var writes = inOrder(orders, requests, inventory);
        writes.verify(orders).lockOrder(1L);
        writes.verify(requests).lockRequest(1L, 3L);
        writes.verify(inventory).lockItems(1L);
        writes.verify(inventory).restoreAvailable(10L, 5L, 3);
        writes.verify(inventory).recordRestockedQuantity(8L, 3);
        writes.verify(inventory).recordRestock(10L, 8L, 3L, 3, 2L);
        writes.verify(inventory).restoreAvailable(11L, 5L, 1);
        writes.verify(inventory).recordRestockedQuantity(9L, 1);
        writes.verify(inventory).recordRestock(11L, 9L, 3L, 1, 2L);
        writes.verify(requests).markReturned(3L);
        writes.verify(requests).audit(2L, 3L, "RETURN_REQUEST_RECEIVE");
    }

    @Test
    void otherSellerCannotReceive() {
        when(requests.findOrderIdForSeller(7L, 3L)).thenReturn(null);
        assertCode("RETURN_REQUEST_NOT_FOUND", 7L);
        verifyNoInteractions(orders, inventory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED"})
    void rejectsUnapprovedReturn(String status) {
        when(requests.lockRequest(1L, 3L)).thenReturn(request(status, null));
        assertCode("RETURN_NOT_RECEIVABLE", 2L);
        verifyNoInteractions(inventory);
    }

    @Test
    void repeatedReceiveReturnsOriginalReceiptAndWritesStockOnce() {
        prepareRestock();

        assertEquals(service.receive(2L, 3L), service.receive(2L, 3L));

        verify(inventory).restoreAvailable(10L, 5L, 3);
        verify(inventory).restoreAvailable(11L, 5L, 1);
        verify(requests).markReturned(3L);
        verify(requests).audit(2L, 3L, "RETURN_REQUEST_RECEIVE");
    }

    @Test
    void invalidConsumptionOrExhaustedRestockCannotIncreaseStock() {
        when(inventory.lockItems(1L)).thenReturn(List.of(new ReturnInventoryMapper.Item(8L, 10L, 1, 2)));
        assertCode("RETURN_STOCK_CONFLICT", 2L);

        when(inventory.lockItems(1L)).thenReturn(List.of(new ReturnInventoryMapper.Item(8L, 10L, 0, 0)));
        assertCode("RETURN_STOCK_CONFLICT", 2L);

        verify(inventory, never()).restoreAvailable(anyLong(), anyLong(), anyInt());
        verify(requests, never()).markReturned(3L);
    }

    @Test
    void secondItemLedgerFailureRollsBackEarlierRestock() {
        prepareRestock();
        when(inventory.recordRestock(11L, 9L, 3L, 1, 2L)).thenReturn(0);
        var manager = mock(PlatformTransactionManager.class);
        var transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        var transactional = (ReturnReceiptService) factory.getProxy();

        assertThrows(IllegalStateException.class, () -> transactional.receive(2L, 3L));

        verify(inventory).recordRestock(10L, 8L, 3L, 3, 2L);
        verify(inventory).restoreAvailable(11L, 5L, 1);
        verify(requests, never()).markReturned(3L);
        verify(manager).rollback(transaction);
        verify(manager, never()).commit(transaction);
    }

    @Test
    void requiresExplicitConfirmationThatGoodsCanBeResold() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(new ReceiptConfirmation(null)).isEmpty());
            assertFalse(validator.validate(new ReceiptConfirmation(false)).isEmpty());
            assertTrue(validator.validate(new ReceiptConfirmation(true)).isEmpty());
        }
    }

    private void prepareRestock() {
        when(requests.lockRequest(1L, 3L)).thenReturn(request("APPROVED", null), request("RETURNED", RECEIVED_AT));
        when(inventory.lockItems(1L)).thenReturn(List.of(
                new ReturnInventoryMapper.Item(8L, 10L, 5, 2), new ReturnInventoryMapper.Item(9L, 11L, 1, 0)));
        when(inventory.restoreAvailable(10L, 5L, 3)).thenReturn(1);
        when(inventory.restoreAvailable(11L, 5L, 1)).thenReturn(1);
        when(inventory.recordRestockedQuantity(8L, 3)).thenReturn(1);
        when(inventory.recordRestockedQuantity(9L, 1)).thenReturn(1);
        when(inventory.recordRestock(10L, 8L, 3L, 3, 2L)).thenReturn(1);
        when(inventory.recordRestock(11L, 9L, 3L, 1, 2L)).thenReturn(1);
        when(requests.markReturned(3L)).thenReturn(1);
        when(requests.audit(2L, 3L, "RETURN_REQUEST_RECEIVE")).thenReturn(1);
        when(inventory.findRecordedRestocks(1L, 3L)).thenReturn(
                List.of(new RestockedItem(8L, 10L, 3), new RestockedItem(9L, 11L, 1)));
    }

    private Item request(String status, LocalDateTime returnedAt) {
        return new Item(3L, 1L, "order-1", "COMPLETED", PRICE, status, true,
                "Return request", RECEIVED_AT.minusDays(1), null, RECEIVED_AT.minusHours(1), returnedAt);
    }

    private void assertCode(String code, long seller) {
        assertEquals(code, assertThrows(BusinessException.class, () -> service.receive(seller, 3L)).code());
    }
}
