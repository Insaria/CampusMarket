package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.TradeAppointmentDtos.Pending;
import edu.bistu.campusmarket.dto.TradeAppointmentDtos.Proposal;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.TradeAppointmentMapper;
import edu.bistu.campusmarket.service.TradeAppointmentService;
import edu.bistu.campusmarket.service.NotificationService;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradeAppointmentTest {
    private static final LocalDateTime TIME = LocalDateTime.parse("2026-11-21T07:00:00");
    private final OrderFulfillmentMapper orders = mock(OrderFulfillmentMapper.class);
    private final TradeAppointmentMapper mapper = mock(TradeAppointmentMapper.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final TradeAppointmentService service = new TradeAppointmentService(orders, mapper, notifications);

    @BeforeEach
    void arrange() {
        when(orders.lockOrder(1)).thenReturn(new OrderFulfillmentMapper.Order(1L, 2L, 3L, "PAID", null, null, null));
        when(orders.findBuyerId(2)).thenReturn(10L);
        when(orders.findSellerUserId(3)).thenReturn(11L);
        when(mapper.now()).thenReturn(TIME.minusDays(1));
    }

    @Test
    void proposalsUseUtcAndAreOnlyAcceptableByTheCounterparty() {
        var saved = new Pending(8L, 10L, TIME, "校园门口");
        when(mapper.latest(1)).thenReturn(null, saved);
        when(mapper.propose(10, 1, TIME, "校园门口")).thenReturn(1);
        var view = service.propose(10, 1, new Proposal(OffsetDateTime.parse("2026-11-21T15:00:00+08:00"), " 校园门口 "));
        assertThat(view.canAccept()).isFalse();
        verify(notifications).emit(NotificationService.Event.SELLER_APPOINTMENT_PROPOSED, 1, 8);
        assertThat(service.pending(11, 1).canAccept()).isTrue();
        assertThatThrownBy(() -> service.accept(10, 1, 8)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.accept(11, 1, 7)).isInstanceOf(BusinessException.class);
        verify(mapper, never()).confirm(1, TIME, "校园门口");
    }

    @Test
    void onlyAConfirmedProposalChangesTheOrder() {
        when(mapper.latest(1)).thenReturn(new Pending(8L, 10L, TIME, "校园门口"));
        when(mapper.confirm(1, TIME, "校园门口")).thenReturn(1);
        when(mapper.accept(11, 1, 8)).thenReturn(1);
        service.accept(11, 1, 8);
        verify(mapper).confirm(1, TIME, "校园门口");
        verify(mapper).accept(11, 1, 8);
        verify(notifications).emit(NotificationService.Event.BUYER_APPOINTMENT_ACCEPTED, 1, 8);
        assertThatThrownBy(() -> service.pending(12, 1)).isInstanceOf(BusinessException.class);
    }
}
