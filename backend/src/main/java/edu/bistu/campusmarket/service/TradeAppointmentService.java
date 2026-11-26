package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.TradeAppointmentDtos.Pending;
import edu.bistu.campusmarket.dto.TradeAppointmentDtos.Proposal;
import edu.bistu.campusmarket.dto.TradeAppointmentDtos.View;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.TradeAppointmentMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class TradeAppointmentService {
    private final OrderFulfillmentMapper orders;
    private final TradeAppointmentMapper appointments;
    private final NotificationService notifications;

    public TradeAppointmentService(OrderFulfillmentMapper orders, TradeAppointmentMapper appointments,
            NotificationService notifications) {
        this.orders = orders;
        this.appointments = appointments;
        this.notifications = notifications;
    }

    @Transactional
    public View pending(long actor, long orderId) {
        if (!"PAID".equals(requireParticipant(actor, orderId).orderStatus())) {
            return null;
        }
        return toView(appointments.latest(orderId), actor);
    }

    @Transactional
    public View propose(long actor, long orderId, Proposal input) {
        boolean buyer = isPaidBuyer(actor, orderId);
        LocalDateTime time = input.scheduledTradeAt().withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
        if (time.getYear() > 9999 || !time.isAfter(appointments.now())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_APPOINTMENT_TIME", "预约时间必须晚于当前时间且在数据库支持范围内");
        }
        String location = input.location().strip();
        Pending previous = appointments.latest(orderId);
        if (previous != null && Objects.equals(previous.actorUserId(), actor)
                && time.equals(previous.scheduledTradeAt()) && location.equals(previous.location())) {
            return toView(previous, actor);
        }
        requireWrite(appointments.propose(actor, orderId, time, location));
        Pending saved = appointments.latest(orderId);
        notifications.emit(buyer ? NotificationService.Event.SELLER_APPOINTMENT_PROPOSED
                : NotificationService.Event.BUYER_APPOINTMENT_PROPOSED, orderId, saved.id());
        return toView(saved, actor);
    }

    @Transactional
    public void accept(long actor, long orderId, long proposalId) {
        boolean buyer = isPaidBuyer(actor, orderId);
        Pending latest = appointments.latest(orderId);
        if (latest == null || latest.id() != proposalId || latest.scheduledTradeAt() == null
                || Objects.equals(latest.actorUserId(), actor) || !latest.scheduledTradeAt().isAfter(appointments.now())) {
            throw new BusinessException(HttpStatus.CONFLICT, "APPOINTMENT_NOT_ACCEPTABLE", "该预约已过期、已变更或必须由对方确认");
        }
        requireWrite(appointments.confirm(orderId, latest.scheduledTradeAt(), latest.location()));
        requireWrite(appointments.accept(actor, orderId, proposalId));
        notifications.emit(buyer ? NotificationService.Event.SELLER_APPOINTMENT_ACCEPTED
                : NotificationService.Event.BUYER_APPOINTMENT_ACCEPTED, orderId, proposalId);
    }

    private OrderFulfillmentMapper.Order requireParticipant(long actor, long orderId) {
        var order = orders.lockOrder(orderId);
        if (order == null || !(Objects.equals(orders.findBuyerId(order.batchId()), actor)
                || Objects.equals(orders.findSellerUserId(order.merchantId()), actor))) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        }
        return order;
    }

    private boolean isPaidBuyer(long actor, long orderId) {
        var order = requireParticipant(actor, orderId);
        if (!"PAID".equals(order.orderStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "APPOINTMENT_ORDER_STATE", "只有已支付且尚未交付的订单可以协商预约");
        }
        return Objects.equals(orders.findBuyerId(order.batchId()), actor);
    }

    private View toView(Pending pending, long actor) {
        return pending == null || pending.scheduledTradeAt() == null ? null
                : new View(pending.id(), pending.scheduledTradeAt(), pending.location(),
                        !Objects.equals(pending.actorUserId(), actor));
    }

    private void requireWrite(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("Could not save trade appointment");
        }
    }
}
