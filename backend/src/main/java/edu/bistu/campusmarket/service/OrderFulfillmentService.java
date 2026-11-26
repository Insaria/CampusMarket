package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.OrderFulfillmentResult;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper.Order;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper.Reservation;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class OrderFulfillmentService {
    private static final String PAID = "PAID";
    private static final String DELIVERED = "DELIVERED";
    private static final String COMPLETED = "COMPLETED";

    private final OrderFulfillmentMapper mapper;
    private final WalletPaymentMapper orderEvents;
    private final NotificationService notifications;

    public OrderFulfillmentService(OrderFulfillmentMapper mapper, WalletPaymentMapper orderEvents,
            NotificationService notifications) {
        this.mapper = mapper;
        this.orderEvents = orderEvents;
        this.notifications = notifications;
    }

    @Transactional
    public OrderFulfillmentResult deliver(long sellerUserId, long orderId) {
        Order order = mapper.lockOrder(orderId);
        if (order == null || !Long.valueOf(sellerUserId).equals(mapper.findSellerUserId(order.merchantId()))) {
            throw orderNotFound();
        }
        if (order.deliveredAt() != null && (DELIVERED.equals(order.orderStatus()) || COMPLETED.equals(order.orderStatus()))) {
            return result(order);
        }
        if (!PAID.equals(order.orderStatus())) {
            throw notDeliverable();
        }
        consumeReservations(orderId, sellerUserId);
        if (mapper.markDelivered(orderId) != 1) {
            throw notDeliverable();
        }
        var result = recordTransition(orderId, sellerUserId, "SELLER_DELIVERY", PAID, DELIVERED);
        notifications.emit(NotificationService.Event.ORDER_DELIVERED, orderId, orderId);
        return result;
    }

    private void consumeReservations(long orderId, long sellerUserId) {
        List<Reservation> reservations = mapper.lockReservations(orderId);
        if (reservations.isEmpty()) {
            throw new IllegalStateException("Order is missing its items");
        }
        // The mapper orders by product ID to match checkout and cancellation's stock lock order.
        for (Reservation item : reservations) {
            int quantity = item.remainingQuantity();
            if (quantity == 0) {
                continue;
            }
            if (mapper.consumeReservedStock(item.productId(), quantity) != 1
                    || mapper.recordConsumedQuantity(item.id(), quantity) != 1) {
                throw new BusinessException(HttpStatus.CONFLICT, "STOCK_CONSUME_CONFLICT", "预留库存不一致，无法确认交付");
            }
            if (mapper.recordStockConsumption(item.productId(), item.id(), quantity, sellerUserId) != 1) {
                throw new IllegalStateException("Order stock consumption event was not inserted");
            }
        }
    }

    @Transactional
    public OrderFulfillmentResult confirmReceipt(long buyerId, long orderId) {
        Order order = mapper.lockOrder(orderId);
        if (order == null || !Long.valueOf(buyerId).equals(mapper.findBuyerId(order.batchId()))) {
            throw orderNotFound();
        }
        if (COMPLETED.equals(order.orderStatus()) && order.buyerConfirmedAt() != null) {
            return result(order);
        }
        if (!DELIVERED.equals(order.orderStatus()) || order.deliveredAt() == null
                || mapper.markBuyerConfirmed(orderId) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_NOT_CONFIRMABLE", "该订单当前无法确认收货");
        }
        var result = recordTransition(orderId, buyerId, "BUYER_CONFIRM_RECEIPT", DELIVERED, COMPLETED);
        notifications.emit(NotificationService.Event.RECEIPT_CONFIRMED, orderId, orderId);
        notifications.emit(NotificationService.Event.BUYER_RECEIPT_CONFIRMED, orderId, orderId);
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean autoConfirm(long orderId, LocalDateTime cutoff) {
        Order order = mapper.lockOrder(orderId);
        if (order == null || !DELIVERED.equals(order.orderStatus())
                || order.deliveredAt() == null || order.deliveredAt().isAfter(cutoff)
                || order.buyerConfirmedAt() != null || order.autoConfirmedAt() != null) {
            return false;
        }
        if (mapper.markAutoConfirmed(orderId, cutoff) != 1
                || mapper.recordAutoConfirmation(orderId, nextEventSequence(orderId)) != 1) {
            throw new IllegalStateException("Order automatic confirmation was not recorded");
        }
        notifications.emit(NotificationService.Event.RECEIPT_CONFIRMED, orderId, orderId);
        notifications.emit(NotificationService.Event.BUYER_RECEIPT_CONFIRMED, orderId, orderId);
        return true;
    }

    private OrderFulfillmentResult recordTransition(long orderId, long userId, String key, String from, String to) {
        if (mapper.recordTransition(orderId, nextEventSequence(orderId), key, from, to, userId) != 1) {
            throw new IllegalStateException("Order fulfillment event was not inserted");
        }
        return result(mapper.lockOrder(orderId));
    }

    private int nextEventSequence(long orderId) {
        Integer sequence = orderEvents.lastEventSequence(orderId);
        if (sequence == null) {
            throw new IllegalStateException("Order is missing its initial event");
        }
        return Math.incrementExact(sequence);
    }

    private OrderFulfillmentResult result(Order order) {
        return new OrderFulfillmentResult(order.id(), order.orderStatus(), utc(order.deliveredAt()),
                utc(order.buyerConfirmedAt()), utc(order.autoConfirmedAt()));
    }

    private Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private BusinessException orderNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
    }

    private BusinessException notDeliverable() {
        return new BusinessException(HttpStatus.CONFLICT, "ORDER_NOT_DELIVERABLE", "该订单当前无法确认交付");
    }
}
