package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.CheckoutDtos.Order;
import edu.bistu.campusmarket.dto.CheckoutDtos.PaidRequest;
import edu.bistu.campusmarket.dto.CheckoutDtos.SellerRedemption;
import edu.bistu.campusmarket.dto.WalletPaymentRequest;
import edu.bistu.campusmarket.dto.WalletPaymentResult;
import edu.bistu.campusmarket.dto.CheckoutDtos.Request;
import edu.bistu.campusmarket.dto.CheckoutDtos.Selection;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.CheckoutMapper;
import edu.bistu.campusmarket.mapper.CheckoutMapper.BalanceSnapshot;
import edu.bistu.campusmarket.mapper.CheckoutMapper.Batch;
import edu.bistu.campusmarket.mapper.CheckoutMapper.Product;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.service.CheckoutService;
import edu.bistu.campusmarket.service.NotificationService;
import edu.bistu.campusmarket.service.WalletPaymentService;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CheckoutTest {
    private final CheckoutMapper mapper = mock(CheckoutMapper.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final WalletPaymentService payments = mock(WalletPaymentService.class);
    private final CheckoutService service = new CheckoutService(mapper, notifications, payments);
    private final Request request = new Request("attempt-1", List.of(new Selection(8L, 1), new Selection(7L, 2)));

    @BeforeEach
    void buyer() {
        when(mapper.lockBuyer(1)).thenReturn(1L);
    }

    @Test
    void splitsIntoTwoOrdersAndRecordsBothReservations() {
        readyToCheckout();
        var result = service.checkout(1, request);
        assertThat(result.batchId()).isEqualTo(30L);
        assertThat(result.quotedAmount()).isEqualByComparingTo("21");
        assertThat(result.orders()).hasSize(2);
        verify(mapper).inventoryEvent(7, 50, 30, 2, 1);
        verify(mapper).inventoryEvent(8, 51, 30, 1, 1);
        verify(notifications).emit(NotificationService.Event.ORDER_CREATED, 40, 40);
        verify(notifications).emit(NotificationService.Event.ORDER_CREATED, 41, 41);
    }

    @Test
    void paysBothOrdersWithSellerPointsAndReplaysWithoutReservingAgain() {
        readyToCheckout();
        WalletPaymentResult payment = new WalletPaymentResult(30L, new BigDecimal("20.00"), List.of(40L, 41L),
                100L, BigDecimal.ONE);
        when(payments.pay(1, 30, new WalletPaymentRequest(List.of(new WalletPaymentRequest.Redemption(40L, 100L)))))
                .thenReturn(payment);
        var paidOrders = orders().stream().map(order -> new Order(order.orderId(), order.sellerId(), order.orderNo(),
                "PAID", order.quotedAmount())).toList();
        when(mapper.orders(30)).thenReturn(orders(), paidOrders);
        var input = new PaidRequest(request, new BigDecimal("21.00"), List.of(new SellerRedemption(10L, 100L)));
        assertThat(service.checkoutAndPay(1, input).payment()).isEqualTo(payment);
        var digest = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(mapper).createBatch(eq(1L), eq("attempt-1"), digest.capture(), any());
        when(mapper.findBatch(1, "attempt-1")).thenReturn(new Batch(30L, digest.getValue()));
        assertThat(service.checkoutAndPay(1, input).orders()).extracting(Order::orderStatus).containsOnly("PAID");
        assertThatThrownBy(() -> service.checkoutAndPay(1, new PaidRequest(request, new BigDecimal("21.00"), List.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("CHECKOUT_KEY_CONFLICT"));
        verify(mapper).reserve(7, 2);
        verify(mapper).reserve(8, 1);
        org.mockito.Mockito.verifyNoInteractions(notifications);
    }

    @Test
    void insufficientWalletRollsBackTheOuterTransaction() {
        readyToCheckout();
        when(payments.pay(eq(1L), eq(30L), any()))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "INSUFFICIENT_WALLET_BALANCE", "余额不足"));
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        assertThatThrownBy(() -> ((CheckoutService) proxy.getProxy())
                .checkoutAndPay(1, new PaidRequest(request, new BigDecimal("21.00"), List.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("INSUFFICIENT_WALLET_BALANCE"));
        verify(mapper).reserve(7, 2);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test
    void rejectsPriceChangesBeforeCreatingBatch() {
        readyToCheckout();
        assertThatThrownBy(() -> service.checkoutAndPay(1, new PaidRequest(request, BigDecimal.TEN, List.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("CHECKOUT_QUOTE_CHANGED"));
        verify(mapper, never()).createBatch(eq(1L), anyString(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(payments);
    }

    private void readyToCheckout() {
        cart();
        Product first = new Product(7L, 10L, "Book", BigDecimal.TEN, 5, 0);
        Product second = new Product(8L, 20L, "Lamp", BigDecimal.ONE, 1, 0);
        when(mapper.lockSellableProduct(7)).thenReturn(first);
        when(mapper.lockSellableProduct(8)).thenReturn(second);
        when(mapper.createBatch(eq(1L), eq("attempt-1"), any(), any())).thenAnswer(call -> generated(call, 30L));
        when(mapper.createOrder(eq(30L), eq(10L), anyString(), eq("PENDING_PAYMENT"), any()))
                .thenAnswer(call -> generated(call, 40L));
        when(mapper.createOrder(eq(30L), eq(20L), anyString(), eq("PENDING_PAYMENT"), any()))
                .thenAnswer(call -> generated(call, 41L));
        when(mapper.createItem(eq(40L), eq(first), eq(2), any())).thenAnswer(call -> generated(call, 50L));
        when(mapper.createItem(eq(41L), eq(second), eq(1), any())).thenAnswer(call -> generated(call, 51L));
        when(mapper.initialEvent(40, "PENDING_PAYMENT", 1)).thenReturn(1);
        when(mapper.initialEvent(41, "PENDING_PAYMENT", 1)).thenReturn(1);
        when(mapper.reserve(7, 2)).thenReturn(1);
        when(mapper.reserve(8, 1)).thenReturn(1);
        when(mapper.inventoryEvent(7, 50, 30, 2, 1)).thenReturn(1);
        when(mapper.inventoryEvent(8, 51, 30, 1, 1)).thenReturn(1);
        when(mapper.removeCheckedOutItem(5, 7)).thenReturn(1);
        when(mapper.removeCheckedOutItem(5, 8)).thenReturn(1);
        when(mapper.orders(30)).thenReturn(orders());

    }

    @Test
    void closedShopProductCannotBePurchasedDirectly() {
        when(mapper.lockCart(1)).thenReturn(20L);
        when(mapper.lockCartQuantity(20, 7)).thenReturn(2);
        when(mapper.lockCartQuantity(20, 8)).thenReturn(1);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(CheckoutMapper.class);
        assertThat(configuration.getMappedStatement(CheckoutMapper.class.getName() + ".lockSellableProduct")
                .getBoundSql(null).getSql()).contains("m.operation_status = 'ACTIVE'", "FOR UPDATE");
        assertThatThrownBy(() -> service.checkoutAndPay(1, new PaidRequest(request, BigDecimal.TEN, List.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("PRODUCT_UNAVAILABLE"));
        verify(mapper, never()).createBatch(eq(1L), anyString(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(payments, notifications);
    }

    @Test
    void rejectsWholeBatchBeforeWritingIfAnyLineIsShort() {
        cart();
        when(mapper.lockSellableProduct(7)).thenReturn(new Product(7L, 10L, "Book", BigDecimal.TEN, 5, 0));
        when(mapper.lockSellableProduct(8)).thenReturn(new Product(8L, 20L, "Lamp", BigDecimal.ONE, 0, 0));
        assertThatThrownBy(() -> service.checkout(1, request)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.code()).isEqualTo("STOCK_UNAVAILABLE"));
        verify(mapper, never()).createBatch(eq(1L), anyString(), any(), any());
        verify(mapper, never()).reserve(7, 2);
        org.mockito.Mockito.verifyNoInteractions(notifications);
    }

    @Test
    void replaysBeforeReadingCartAndRejectsDifferentSelection() throws Exception {
        byte[] intent = ByteBuffer.allocate(24).putLong(7L).putInt(2).putLong(8L).putInt(1).array();
        when(mapper.findBatch(1, "attempt-1")).thenReturn(
                new Batch(30L, MessageDigest.getInstance("SHA-256").digest(intent)));
        when(mapper.orders(30)).thenReturn(orders());
        assertThat(service.checkout(1, request).batchId()).isEqualTo(30L);
        assertThatThrownBy(() -> service.checkout(1, new Request("attempt-1", List.of(new Selection(7L, 1)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("CHECKOUT_KEY_CONFLICT"));
        verify(mapper, never()).lockCart(1);
        org.mockito.Mockito.verifyNoInteractions(notifications);
    }

    @Test
    void batchLookupChecksOwnershipBeforeReadingOrders() {
        assertThatThrownBy(() -> service.batch(1, 30)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.code()).isEqualTo("CHECKOUT_BATCH_NOT_FOUND"));
        verify(mapper, never()).orders(30);
    }

    @Test
    void paidBatchReturnsPersistedAmountsAndCurrentOrderStatuses() {
        when(mapper.findOwnedBatch(30, 1)).thenReturn(new BalanceSnapshot(30L, new BigDecimal("18.25"), 200L,
                new BigDecimal("2.00")));
        when(mapper.orders(30)).thenReturn(List.of(new Order(40L, 10L, "order-1", "COMPLETED", new BigDecimal("20.25"))));

        var batch = service.batch(1, 30);
        assertThat(batch.quotedAmount()).isEqualByComparingTo("20.25");
        assertThat(batch.orders().get(0).orderStatus()).isEqualTo("COMPLETED");
        assertThat(batch.payment().paidAmount()).isEqualByComparingTo("18.25");
        assertThat(batch.payment().pointsUsed()).isEqualTo(200);
        assertThat(batch.payment().pointsDiscountAmount()).isEqualByComparingTo("2.00");
    }

    private int generated(InvocationOnMock call, long id) {
        call.<GeneratedId>getArgument(call.getArguments().length - 1).setId(id);
        return 1;
    }

    private void cart() {
        when(mapper.lockCart(1)).thenReturn(5L);
        when(mapper.lockCartQuantity(5, 7)).thenReturn(2);
        when(mapper.lockCartQuantity(5, 8)).thenReturn(1);
    }

    private List<Order> orders() {
        return List.of(new Order(40L, 10L, "order-1", "PENDING_PAYMENT", new BigDecimal("20")),
                new Order(41L, 20L, "order-2", "PENDING_PAYMENT", BigDecimal.ONE));
    }
}
