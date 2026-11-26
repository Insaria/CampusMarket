package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.NotificationDtos.Message;
import edu.bistu.campusmarket.dto.NotificationDtos.Page;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.NotificationMapper;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class NotificationService {
    public enum Event {
        ORDER_CREATED(true, "ORDER", "收到新订单", "有新的结账订单，当前等待买家支付。"),
        ORDER_PAID(true, "ORDER", "订单已支付", "买家已支付，可安排线下交付。"),
        BUYER_ORDER_PAID(false, "ORDER", "付款成功", "订单已支付，请查看订单并与卖家安排线下交付。"),
        SELLER_APPOINTMENT_PROPOSED(true, "ORDER", "有新的交付预约", "买家提出了交付预约，请查看订单。"),
        BUYER_APPOINTMENT_PROPOSED(false, "ORDER", "有新的交付预约", "卖家提出了交付预约，请查看订单。"),
        SELLER_APPOINTMENT_ACCEPTED(true, "ORDER", "交付预约已确认", "买家已接受交付预约，请查看订单。"),
        BUYER_APPOINTMENT_ACCEPTED(false, "ORDER", "交付预约已确认", "卖家已接受交付预约，请查看订单。"),
        ORDER_DELIVERED(false, "ORDER", "卖家已交付", "卖家已确认线下交付，请在收到商品后确认收货。"),
        RECEIPT_CONFIRMED(true, "ORDER", "订单已确认收货", "订单已完成收货确认。"),
        BUYER_RECEIPT_CONFIRMED(false, "ORDER", "收货已确认", "订单已完成收货确认，可在订单中查看详情。"),
        ORDER_SETTLED(true, "ORDER", "订单货款已到账", "订单已结算，卖家净收入已计入钱包。请查看钱包流水。"),
        RETURN_APPLIED(true, "RETURN_REQUEST", "收到退货申请", "买家已提交退货申请，请查看并审核。"),
        RETURN_APPROVED(false, "ORDER", "退货申请已获批", "卖家已批准退货，可在订单中查看处理进度。"),
        RETURN_REJECTED(false, "ORDER", "退货申请未获批准", "卖家已拒绝退货，可在订单中查看审核意见。"),
        REFUND_COMPLETED(false, "ORDER", "退款已完成", "现金和适用的积分处理均已完成，可查看退款记录。");

        private final boolean seller;
        private final String targetType;
        private final String title;
        private final String content;

        Event(boolean seller, String targetType, String title, String content) {
            this.seller = seller;
            this.targetType = targetType;
            this.title = title;
            this.content = content;
        }
    }

    private final NotificationMapper mapper;

    public NotificationService(NotificationMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page list(long recipientId, boolean unreadOnly, int page, int size) {
        long total = mapper.count(recipientId, unreadOnly);
        long offset = (page - 1L) * size;
        return new Page(offset < total ? mapper.page(recipientId, unreadOnly, size, offset) : List.of(), total, page, size);
    }

    public long unread(long recipientId) {
        return mapper.count(recipientId, true);
    }

    public Message detail(long recipientId, long id) {
        Message item = mapper.find(recipientId, id);
        if (item == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在");
        }
        return item;
    }

    @Transactional
    public Message read(long recipientId, long id) {
        mapper.read(recipientId, id);
        return detail(recipientId, id);
    }

    @Transactional
    public int readAll(long recipientId) {
        return mapper.readAll(recipientId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void emit(Event event, long orderId, long sourceId) {
        long targetId = "ORDER".equals(event.targetType) ? orderId : sourceId;
        mapper.record(orderId, event.seller, event.name(), event.name() + ":" + sourceId,
                event.targetType, targetId, event.title, event.content);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void emitProductReview(long auditId, long productId, String productName, boolean approved, String reason) {
        String title = approved ? "商品审核通过" : "商品审核未通过";
        String result = approved ? "审核通过，请前往商品管理手动上架。"
                : "审核未通过，审核意见：" + reason + "。请修改后重新提交审核。";
        String content = "商品「" + productName + "」（编号 " + productId + "）" + result;
        if (mapper.recordProductReview(auditId, title, content) != 1) {
            throw new IllegalStateException("Product review notification insert did not affect one row");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void emitProductMessage(long recipient, long id) {
        mapper.recordProductMessage(recipient, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void emitShopOperation(long recipient, long shopId, long auditId, boolean closing, String reason) {
        String kind = closing ? "SHOP_CLOSED" : "SHOP_REOPENED";
        String title = closing ? "店铺暂时关闭" : "店铺已重新开放";
        String content = closing ? "关闭原因：" + reason + "。已付款订单及售后仍可处理。"
                : "符合公开上架条件的商品已恢复展示。";
        if (mapper.recordShopOperation(recipient, shopId, auditId, kind, title, content) != 1) {
            throw new IllegalStateException("Shop operation notification could not be recorded");
        }
    }
}
