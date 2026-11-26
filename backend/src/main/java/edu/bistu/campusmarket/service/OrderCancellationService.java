package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.OrderCancellationResult;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.CheckoutMapper;
import edu.bistu.campusmarket.mapper.OrderCancellationMapper;
import edu.bistu.campusmarket.mapper.OrderCancellationMapper.Item;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper.Order;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class OrderCancellationService {
    private static final String CANCELLED = "CANCELLED";
    private final CheckoutMapper checkout;
    private final WalletPaymentMapper payment;
    private final OrderCancellationMapper cancellation;

    public OrderCancellationService(CheckoutMapper checkout, WalletPaymentMapper payment,
            OrderCancellationMapper cancellation) {
        this.checkout = checkout;
        this.payment = payment;
        this.cancellation = cancellation;
    }

    @Transactional
    public OrderCancellationResult cancel(long buyerId, long batchId) {
        // Use the same buyer, batch and order locking queries as wallet payment.
        if (checkout.lockBuyer(buyerId) == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CANCELLATION_FORBIDDEN", "当前账号不可取消订单");
        }
        var batch = payment.lockBatch(batchId, buyerId);
        if (batch == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "CHECKOUT_BATCH_NOT_FOUND", "结账批次不存在");
        }
        List<Order> orders = payment.lockOrders(batchId);
        if (orders.isEmpty() || batch.totalWalletDebitAmount().signum() != 0) {
            throw notCancellable();
        }
        var result = new OrderCancellationResult(batchId, CANCELLED, orders.stream().map(Order::id).toList());
        if (orders.stream().allMatch(order -> CANCELLED.equals(order.orderStatus()))) {
            return result;
        }
        if (orders.stream().anyMatch(order -> !"PENDING_PAYMENT".equals(order.orderStatus()))) {
            throw notCancellable();
        }

        List<Item> items = new ArrayList<>();
        for (Order order : orders) {
            List<Item> orderItems = cancellation.lockItems(order.id());
            if (orderItems.isEmpty() || orderItems.stream().anyMatch(item -> item.consumedQuantity() != 0)) {
                throw notCancellable();
            }
            items.addAll(orderItems);
        }
        items.sort(Comparator.comparing(Item::productId).thenComparing(Item::id));
        for (Item item : items) {
            release(item, buyerId);
        }
        for (Order order : orders) {
            if (cancellation.markCancelled(order.id(), CANCELLED) != 1) {
                throw notCancellable();
            }
            Integer sequence = payment.lastEventSequence(order.id());
            if (sequence == null) {
                throw new IllegalStateException("Order is missing its initial event");
            }
            requireOne(cancellation.recordCancellation(order.id(), Math.incrementExact(sequence), CANCELLED, buyerId));
        }
        return result;
    }

    private void release(Item item, long buyerId) {
        int quantity = item.reservedQuantity() - item.releasedQuantity();
        if (quantity == 0) {
            return;
        }
        if (cancellation.releaseStock(item.productId(), quantity) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "STOCK_RELEASE_CONFLICT", "库存不一致，无法取消结账批次");
        }
        requireOne(cancellation.recordReleasedQuantity(item.id(), quantity));
        requireOne(cancellation.recordStockRelease(item.productId(), item.id(), quantity, buyerId));
    }

    private void requireOne(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Order cancellation write did not affect one row");
        }
    }

    private BusinessException notCancellable() {
        return new BusinessException(HttpStatus.CONFLICT, "BATCH_NOT_CANCELLABLE", "该批次不处于可取消状态");
    }
}
