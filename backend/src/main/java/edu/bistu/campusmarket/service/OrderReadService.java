package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.OrderReadDtos.*;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderReadMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
@Transactional(readOnly = true)
public class OrderReadService {
    private final OrderReadMapper mapper;

    public OrderReadService(OrderReadMapper mapper) {
        this.mapper = mapper;
    }

    public Page list(long userId, boolean seller, Filter filter, int page, int size) {
        long total = mapper.count(userId, seller, filter);
        long offset = (page - 1L) * size;
        List<Order> orders = offset < total ? mapper.page(userId, seller, filter, size, offset) : List.of();
        if (orders.isEmpty()) return new Page(List.of(), total, page, size);
        var lines = mapper.lines(orders.stream().map(Order::orderId).toList()).stream()
                .collect(Collectors.groupingBy(Line::orderId));
        return new Page(orders.stream().map(order -> summary(order, lines.getOrDefault(order.orderId(), List.of())))
                .toList(), total, page, size);
    }

    public Details detail(long userId, boolean seller, long orderId) {
        Order order = mapper.find(userId, seller, orderId);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        }
        var returns = mapper.returns(orderId);
        LocalDateTime confirmed = confirmation(order);
        LocalDateTime closing = confirmed == null ? null : confirmed.plusHours(24);
        boolean allowReturn = false;
        if (!seller && closing != null && "COMPLETED".equals(order.orderStatus())
                && returns.stream().noneMatch(ReturnProgress::active) && !mapper.settled(orderId)) {
            LocalDateTime now = mapper.utcNow();
            allowReturn = !now.isBefore(confirmed) && now.isBefore(closing);
        }
        return new Details(summary(order, mapper.lines(List.of(orderId))), mapper.events(orderId), returns,
                seller && returns.stream().anyMatch(item -> item.returnedAt() != null) ? mapper.restocks(orderId) : List.of(),
                mapper.reward(orderId, order.buyerId()),
                !seller && "PENDING_PAYMENT".equals(order.orderStatus()) && mapper.batchCancellable(order.batchId()),
                closing, allowReturn,
                "COMPLETED".equals(order.orderStatus()) && !order.buyerId().equals(order.sellerUserId()),
                seller ? List.of() : mapper.itemReviews(orderId));
    }

    private LocalDateTime confirmation(Order order) {
        if ((order.buyerConfirmedAt() == null) == (order.autoConfirmedAt() == null)) return null;
        return order.buyerConfirmedAt() != null ? order.buyerConfirmedAt() : order.autoConfirmedAt();
    }

    private OrderItem summary(Order order, List<Line> lines) {
        BigDecimal quoted = lines.stream().map(Line::lineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderItem(order, lines, quoted);
    }
}
