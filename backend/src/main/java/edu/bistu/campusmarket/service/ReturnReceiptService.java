package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.ReturnRequestDtos.Received;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.ReturnInventoryMapper;
import edu.bistu.campusmarket.mapper.ReturnInventoryMapper.Item;
import edu.bistu.campusmarket.mapper.ReturnRequestMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class ReturnReceiptService {
    private final ReturnRequestMapper requests;
    private final SettlementMapper orders;
    private final OrderFulfillmentMapper ownership;
    private final ReturnInventoryMapper inventory;

    public ReturnReceiptService(ReturnRequestMapper requests, SettlementMapper orders,
            OrderFulfillmentMapper ownership, ReturnInventoryMapper inventory) {
        this.requests = requests;
        this.orders = orders;
        this.ownership = ownership;
        this.inventory = inventory;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Received receive(long sellerUserId, long requestId) {
        Long orderId = requests.findOrderIdForSeller(sellerUserId, requestId);
        if (orderId == null) {
            throw notFound();
        }
        var order = orders.lockOrder(orderId);
        if (order == null || !Long.valueOf(sellerUserId).equals(ownership.findSellerUserId(order.merchantId()))) {
            throw notFound();
        }
        var request = requests.lockRequest(orderId, requestId);
        if (request == null) {
            throw notFound();
        }
        if ("RETURNED".equals(request.requestStatus()) && request.returnedAt() != null) {
            return new Received(request, inventory.findRecordedRestocks(orderId, requestId));
        }
        if (!"APPROVED".equals(request.requestStatus()) || !request.active() || request.returnedAt() != null
                || !"COMPLETED".equals(order.orderStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RETURN_NOT_RECEIVABLE", "该退货申请当前无法确认验收");
        }

        List<Item> items = inventory.lockItems(orderId);
        if (items.isEmpty()) {
            throw stockConflict();
        }
        for (Item item : items) {
            int consumed = item.consumedQuantity();
            int restocked = item.restockedQuantity();
            if (consumed <= 0 || restocked < 0 || restocked > consumed) {
                throw stockConflict();
            }
            int quantity = consumed - restocked;
            if (quantity == 0) {
                continue;
            }
            if (inventory.restoreAvailable(item.productId(), order.merchantId(), quantity) != 1
                    || inventory.recordRestockedQuantity(item.id(), quantity) != 1) {
                throw stockConflict();
            }
            requireOne(inventory.recordRestock(item.productId(), item.id(), requestId, quantity, sellerUserId));
        }
        requireOne(requests.markReturned(requestId));
        requireOne(requests.audit(sellerUserId, requestId, "RETURN_REQUEST_RECEIVE"));
        var received = requests.lockRequest(orderId, requestId);
        if (received == null || received.returnedAt() == null || !"RETURNED".equals(received.requestStatus())) {
            throw new IllegalStateException("Return receipt could not be read after write");
        }
        return new Received(received, inventory.findRecordedRestocks(orderId, requestId));
    }

    private static void requireOne(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Return receipt write did not affect one row");
        }
    }

    private BusinessException stockConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "RETURN_STOCK_CONFLICT", "商品或原订单库存记录异常，无法验收入库");
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RETURN_REQUEST_NOT_FOUND", "退货申请不存在");
    }
}
