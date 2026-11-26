package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.ReturnRequestDtos.Application;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Item;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Page;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Review;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Status;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.ReturnRequestMapper;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class ReturnRequestService {
    private final ReturnRequestMapper requests;
    private final SettlementMapper settlement;
    private final OrderFulfillmentMapper ownership;
    private final NotificationService notifications;

    public ReturnRequestService(ReturnRequestMapper requests, SettlementMapper settlement,
            OrderFulfillmentMapper ownership, NotificationService notifications) {
        this.requests = requests;
        this.settlement = settlement;
        this.ownership = ownership;
        this.notifications = notifications;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Item apply(long buyerId, long orderId, Application input) {
        String reason = normalizedText(input.reason());
        if (reason == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RETURN_REASON_REQUIRED", "请填写退货原因");
        }
        Order order = settlement.lockOrder(orderId);
        if (order == null || !Long.valueOf(buyerId).equals(ownership.findBuyerId(order.batchId()))) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        }

        boolean settled = hasSettlement(orderId);
        Long activeId = settlement.lockActiveReturnId(orderId);
        Item duplicate = requests.lockByRequestKey(orderId, input.requestKey());
        if (duplicate != null) {
            if (!reason.equals(duplicate.reason())) {
                throw conflict("RETURN_REQUEST_KEY_CONFLICT", "该请求键已用于不同的退货原因");
            }
            return duplicate;
        }
        LocalDateTime confirmedAt = requireReturnableOrder(order, settled);
        LocalDateTime appliedAt = settlement.utcNow();
        if (appliedAt.isBefore(confirmedAt) || !appliedAt.isBefore(confirmedAt.plusHours(24))) {
            throw conflict("RETURN_WINDOW_CLOSED", "退货申请须在确认收货后 24 小时内提交");
        }
        if (activeId != null) {
            throw conflict("ACTIVE_RETURN_REQUEST_EXISTS", "该订单已有有效退货申请");
        }

        GeneratedId key = new GeneratedId();
        if (requests.insert(orderId, input.requestKey(), reason, appliedAt, key) != 1 || key.getId() == null) {
            throw new IllegalStateException("Return request was not created");
        }
        notifications.emit(NotificationService.Event.RETURN_APPLIED, orderId, key.getId());
        return requireRecordedRequest(orderId, key.getId());
    }

    @Transactional(readOnly = true)
    public Page list(long sellerUserId, Status status, int page, int size) {
        long total = requests.countForSeller(sellerUserId, status);
        return new Page(requests.pageForSeller(sellerUserId, status, size, (page - 1L) * size), total, page, size);
    }

    public Item find(long sellerUserId, long requestId) {
        Item item = requests.findForSeller(sellerUserId, requestId);
        if (item == null) {
            throw requestNotFound();
        }
        return item;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Item review(long sellerUserId, long requestId, Review input) {
        Status status = switch (input.decision()) {
            case APPROVE -> Status.APPROVED;
            case REJECT -> Status.REJECTED;
        };
        String opinion = normalizedText(input.reviewReason());
        if (status == Status.REJECTED && opinion == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RETURN_REVIEW_REASON_REQUIRED", "拒绝退货时须填写审核意见");
        }
        // This lookup only routes to the order; eligibility is checked again after acquiring the order lock.
        Long orderId = requests.findOrderIdForSeller(sellerUserId, requestId);
        if (orderId == null) {
            throw requestNotFound();
        }
        Order order = settlement.lockOrder(orderId);
        if (order == null || !Long.valueOf(sellerUserId).equals(ownership.findSellerUserId(order.merchantId()))) {
            throw requestNotFound();
        }
        requireReturnableOrder(order, hasSettlement(orderId));
        Long activeId = settlement.lockActiveReturnId(orderId);
        Item item = requests.lockRequest(orderId, requestId);
        if (item == null) {
            throw requestNotFound();
        }
        if (!Long.valueOf(requestId).equals(activeId) || !Status.PENDING.name().equals(item.requestStatus())) {
            throw conflict("RETURN_REQUEST_ALREADY_REVIEWED", "只能审核尚未处理的有效退货申请");
        }
        boolean approved = status == Status.APPROVED;
        if (requests.review(requestId, sellerUserId, status, approved, opinion) != 1) {
            throw conflict("RETURN_REQUEST_ALREADY_REVIEWED", "只能审核尚未处理的有效退货申请");
        }
        if (requests.audit(sellerUserId, requestId, "RETURN_REQUEST_" + input.decision().name()) != 1) {
            throw new IllegalStateException("Return request review audit was not recorded");
        }
        notifications.emit(approved ? NotificationService.Event.RETURN_APPROVED : NotificationService.Event.RETURN_REJECTED,
                orderId, requestId);
        return requireRecordedRequest(orderId, requestId);
    }

    private boolean hasSettlement(long orderId) {
        var escrow = settlement.lockEscrow(orderId);
        return escrow != null && settlement.findSettlement(escrow.id()) != null;
    }

    private LocalDateTime requireReturnableOrder(Order order, boolean settled) {
        LocalDateTime manual = order.buyerConfirmedAt();
        LocalDateTime automatic = order.autoConfirmedAt();
        if (settled || !"COMPLETED".equals(order.orderStatus()) || (manual == null) == (automatic == null)) {
            throw conflict("ORDER_NOT_RETURNABLE", "该订单当前不可申请或审核退货");
        }
        return manual != null ? manual : automatic;
    }

    private Item requireRecordedRequest(long orderId, long requestId) {
        Item item = requests.lockRequest(orderId, requestId);
        if (item == null) {
            throw new IllegalStateException("Return request could not be read after write");
        }
        return item;
    }

    private String normalizedText(String text) {
        if (text == null || text.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
            return null;
        }
        return text.strip();
    }

    private BusinessException requestNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RETURN_REQUEST_NOT_FOUND", "退货申请不存在");
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
