package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.CheckoutDtos.BatchDetails;
import edu.bistu.campusmarket.dto.CheckoutDtos.Order;
import edu.bistu.campusmarket.dto.CheckoutDtos.PaidRequest;
import edu.bistu.campusmarket.dto.CheckoutDtos.SellerRedemption;
import edu.bistu.campusmarket.dto.CheckoutDtos.PendingPage;
import edu.bistu.campusmarket.dto.CheckoutDtos.Request;
import edu.bistu.campusmarket.dto.CheckoutDtos.Result;
import edu.bistu.campusmarket.dto.CheckoutDtos.Selection;
import edu.bistu.campusmarket.dto.WalletPaymentResult;
import edu.bistu.campusmarket.dto.WalletPaymentRequest;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.CheckoutMapper;
import edu.bistu.campusmarket.mapper.CheckoutMapper.Product;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class CheckoutService {
    private static final String INITIAL_STATUS = "PENDING_PAYMENT";
    private static final BigDecimal MAX_BATCH_AMOUNT = new BigDecimal("999999999999.99");
    private final CheckoutMapper mapper;
    private final NotificationService notifications;
    private final WalletPaymentService payments;

    public CheckoutService(CheckoutMapper mapper, NotificationService notifications, WalletPaymentService payments) {
        this.mapper = mapper;
        this.notifications = notifications;
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public BatchDetails batch(long buyerId, long batchId) {
        var snapshot = mapper.findOwnedBatch(batchId, buyerId);
        if (snapshot == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "CHECKOUT_BATCH_NOT_FOUND", "结账批次不存在");
        }
        Result batch = result(batchId);
        WalletPaymentResult payment = snapshot.totalWalletDebitAmount().signum() > 0
                ? new WalletPaymentResult(batchId, snapshot.totalWalletDebitAmount(),
                        batch.orders().stream().map(Order::orderId).toList(), snapshot.totalPointsUsed(),
                        snapshot.totalPointsDiscountAmount())
                : null;
        return new BatchDetails(batchId, batch.quotedAmount(), batch.orders(), payment);
    }

    @Transactional(readOnly = true)
    public PendingPage pending(long buyerId, int page, int size) {
        long total = mapper.countPending(buyerId);
        long offset = (page - 1L) * size;
        return new PendingPage(offset < total ? mapper.pending(buyerId, size, offset) : List.of(), total, page, size);
    }

    @Transactional
    public Result checkout(long buyerId, Request request) {
        return checkout(buyerId, request, null, List.of());
    }

    @Transactional
    public BatchDetails checkoutAndPay(long buyerId, PaidRequest input) {
        List<SellerRedemption> redemptions = input.redemptions() == null ? List.of()
                : input.redemptions().stream().sorted(Comparator.comparing(SellerRedemption::sellerId)).toList();
        Result batch = checkout(buyerId, input.checkout(), input.quotedAmount(), redemptions);
        var allocations = new ArrayList<WalletPaymentRequest.Redemption>();
        for (var redemption : redemptions) {
            long orderId = batch.orders().stream().filter(order -> order.sellerId().equals(redemption.sellerId()))
                    .findFirst().orElseThrow(() -> conflict("INVALID_POINTS_REDEMPTION", "只能为本次结账的卖家使用积分")).orderId();
            allocations.add(new WalletPaymentRequest.Redemption(orderId, redemption.points()));
        }
        WalletPaymentResult payment = payments.pay(buyerId, batch.batchId(), new WalletPaymentRequest(allocations));
        Result paid = result(batch.batchId());
        return new BatchDetails(paid.batchId(), paid.quotedAmount(), paid.orders(), payment);
    }

    private Result checkout(long buyerId, Request request, BigDecimal expectedTotal, List<SellerRedemption> redemptions) {
        List<Selection> selections = request.items().stream().sorted(Comparator.comparing(Selection::productId)).toList();
        byte[] digest = digest(selections);
        if (expectedTotal != null) {
            // Separate the paid request identity from historical unpaid checkout, even if the client reuses a key.
            var identity = new StringBuilder("wallet-v1:").append(expectedTotal.stripTrailingZeros().toPlainString());
            for (var redemption : redemptions) {
                identity.append(':').append(redemption.sellerId()).append('=').append(redemption.points());
            }
            byte[] terms = identity.toString().getBytes(StandardCharsets.US_ASCII);
            digest = sha256(ByteBuffer.allocate(digest.length + terms.length).put(digest).put(terms).array());
        }
        if (mapper.lockBuyer(buyerId) == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CHECKOUT_FORBIDDEN", "当前账号不可结算");
        }
        var existing = mapper.findBatch(buyerId, request.requestKey());
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.requestDigest(), digest)) {
                throw conflict("CHECKOUT_KEY_CONFLICT", "结账请求键已用于不同的结账内容");
            }
            return result(existing.id());
        }
        Long cart = mapper.lockCart(buyerId);
        if (cart == null) {
            throw conflict("CART_CHANGED", "购物车已变动，请刷新后重试");
        }
        for (Selection selection : selections) {
            if (!selection.quantity().equals(mapper.lockCartQuantity(cart, selection.productId()))) {
                throw conflict("CART_CHANGED", "购物车已变动，请刷新后重试");
            }
        }
        Map<Long, List<Line>> bySeller = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Selection selection : selections) {
            Product product = mapper.lockSellableProduct(selection.productId());
            if (product == null) {
                throw conflict("PRODUCT_UNAVAILABLE", "所选商品已不可购买");
            }
            int quantity = selection.quantity();
            if (product.availableQuantity() < quantity || (long) product.reservedQuantity() + quantity > Integer.MAX_VALUE) {
                throw conflict("STOCK_UNAVAILABLE", "所选商品库存不足");
            }
            total = total.add(product.price().multiply(BigDecimal.valueOf(quantity)));
            bySeller.computeIfAbsent(product.merchantId(), ignored -> new ArrayList<>()).add(new Line(product, quantity));
        }
        if (total.compareTo(MAX_BATCH_AMOUNT) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CHECKOUT_AMOUNT_TOO_LARGE", "结账金额超出允许范围");
        }
        if (expectedTotal != null && total.compareTo(expectedTotal) != 0) {
            throw conflict("CHECKOUT_QUOTE_CHANGED", "商品报价已变化，请重新核对金额");
        }
        GeneratedId batch = new GeneratedId();
        long batchId = inserted(mapper.createBatch(buyerId, request.requestKey(), digest, batch), batch);
        for (var group : bySeller.entrySet()) {
            GeneratedId order = new GeneratedId();
            long orderId = inserted(mapper.createOrder(batchId, group.getKey(), UUID.randomUUID().toString(),
                    INITIAL_STATUS, order), order);
            requireOne(mapper.initialEvent(orderId, INITIAL_STATUS, buyerId));
            for (Line line : group.getValue()) {
                long productId = line.product().id();
                int quantity = line.quantity();
                if (mapper.reserve(productId, quantity) != 1) {
                    throw conflict("STOCK_UNAVAILABLE", "所选商品库存不足");
                }
                GeneratedId item = new GeneratedId();
                long itemId = inserted(mapper.createItem(orderId, line.product(), quantity, item), item);
                requireOne(mapper.inventoryEvent(productId, itemId, batchId, quantity, buyerId));
                requireOne(mapper.removeCheckedOutItem(cart, productId));
            }
            if (expectedTotal == null) {
                notifications.emit(NotificationService.Event.ORDER_CREATED, orderId, orderId);
            }
        }
        return result(batchId);
    }

    private Result result(long batchId) {
        List<Order> orders = mapper.orders(batchId);
        if (orders.isEmpty()) {
            throw new IllegalStateException("Checkout batch has no orders");
        }
        return new Result(batchId, orders.stream().map(Order::quotedAmount).reduce(BigDecimal.ZERO, BigDecimal::add), orders);
    }

    private byte[] digest(List<Selection> selections) {
        ByteBuffer bytes = ByteBuffer.allocate(selections.size() * (Long.BYTES + Integer.BYTES));
        Long previous = null;
        for (Selection selection : selections) {
            if (selection.productId().equals(previous)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_CHECKOUT_ITEM", "结账商品不能重复");
            }
            previous = selection.productId();
            bytes.putLong(selection.productId()).putInt(selection.quantity());
        }
        return sha256(bytes.array());
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private long inserted(int changed, GeneratedId key) {
        requireOne(changed);
        if (key.getId() == null) {
            throw new IllegalStateException("Checkout insert did not generate an ID");
        }
        return key.getId();
    }

    private void requireOne(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Checkout write did not affect one row");
        }
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private record Line(Product product, int quantity) {
    }
}
