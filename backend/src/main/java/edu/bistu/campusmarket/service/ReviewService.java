package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.ReviewDtos.Created;
import edu.bistu.campusmarket.dto.ReviewDtos.ProductReview;
import edu.bistu.campusmarket.dto.ReviewDtos.ProductReviewPage;
import edu.bistu.campusmarket.dto.ReviewDtos.RatingSummary;
import edu.bistu.campusmarket.dto.ReviewDtos.ReputationHistory;
import edu.bistu.campusmarket.dto.ReviewDtos.TradeReview;
import edu.bistu.campusmarket.dto.ReviewDtos.Write;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.ReturnRequestMapper;
import edu.bistu.campusmarket.mapper.ReviewMapper;
import edu.bistu.campusmarket.mapper.ReviewMapper.Parties;
import edu.bistu.campusmarket.mapper.ReviewMapper.RatingAggregate;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class ReviewService {
    private final ReviewMapper reviews;
    private final SettlementMapper orders;
    private final ReturnRequestMapper returns;
    private final CatalogService catalog;

    public ReviewService(ReviewMapper reviews, SettlementMapper orders, ReturnRequestMapper returns, CatalogService catalog) {
        this.reviews = reviews;
        this.orders = orders;
        this.returns = returns;
        this.catalog = catalog;
    }

    @Transactional
    public Created reviewProduct(long buyerId, long itemId, Write input) {
        var item = reviews.findItem(itemId);
        if (item == null) {
            throw notFound();
        }
        Order order = orders.lockOrder(item.orderId());
        if (order == null || !item.merchantId().equals(order.merchantId())) {
            throw notFound();
        }
        Parties parties = reviews.findParties(order.id());
        if (parties == null || parties.buyerId() != buyerId) {
            throw notFound();
        }
        requireReviewable(order, parties);
        GeneratedId key = new GeneratedId();
        try {
            return created(reviews.insertProductReview(itemId, input.rating().intValueExact(), input.content().strip(), key), key);
        } catch (DuplicateKeyException exception) {
            throw alreadyReviewed();
        }
    }

    @Transactional
    public Created reviewSeller(long buyerId, long orderId, Write input) {
        Order order = orders.lockOrder(orderId);
        Parties parties = requireParty(order, buyerId, false);
        return createTradeReview(orderId, buyerId, parties.sellerUserId(), null, input);
    }

    @Transactional
    public Created reviewBuyerPurchase(long sellerId, long orderId, Write input) {
        Order order = orders.lockOrder(orderId);
        Parties parties = requireParty(order, sellerId, true);
        return createTradeReview(orderId, sellerId, parties.buyerId(), null, input);
    }

    @Transactional
    public Created reviewBuyerReturn(long sellerId, long requestId, Write input) {
        Long orderId = returns.findOrderIdForSeller(sellerId, requestId);
        if (orderId == null) {
            throw notFound();
        }
        Order order = orders.lockOrder(orderId);
        Parties parties = requireParty(order, sellerId, true);
        var request = returns.lockRequest(orderId, requestId);
        if (request == null) {
            throw notFound();
        }
        if (!"RETURNED".equals(request.requestStatus()) || request.returnedAt() == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "RETURN_NOT_REVIEWABLE", "退货验收完成后才能评价");
        }
        return createTradeReview(orderId, sellerId, parties.buyerId(), requestId, input);
    }

    @Transactional(readOnly = true)
    public ProductReviewPage productReviews(long productId, int page, int size) {
        catalog.find(productId);
        RatingSummary summary = summarize(reviews.productRatings(productId));
        long offset = (page - 1L) * size;
        List<ProductReview> items = offset < summary.reviewCount()
                ? reviews.findProductReviews(productId, size, offset) : List.of();
        return new ProductReviewPage(items, summary, page, size);
    }

    public List<TradeReview> tradeReviews(long userId, long orderId) {
        Parties parties = reviews.findParties(orderId);
        if (parties == null || (parties.buyerId() != userId && parties.sellerUserId() != userId)) {
            throw notFound();
        }
        return reviews.findTradeReviews(orderId, parties.buyerId(), parties.sellerUserId());
    }

    public RatingSummary sellerSatisfaction(long sellerId) {
        return summarize(reviews.sellerRatings(sellerId));
    }

    public RatingSummary buyerReputation(long buyerId) {
        return summarize(reviews.buyerRatings(buyerId));
    }

    @Transactional(readOnly = true)
    public ReputationHistory reputationHistory(long userId, int page, int size) {
        long total = reviews.countHistory(userId);
        long offset = (page - 1L) * size;
        return new ReputationHistory(offset < total ? reviews.history(userId, size, offset) : List.of(),
                total, page, size, reviews.hasSellerProfile(userId) ? sellerSatisfaction(userId) : null);
    }

    private Parties requireParty(Order order, long userId, boolean seller) {
        if (order == null) {
            throw notFound();
        }
        Parties parties = reviews.findParties(order.id());
        if (parties == null || (seller ? parties.sellerUserId() : parties.buyerId()) != userId) {
            throw notFound();
        }
        requireReviewable(order, parties);
        return parties;
    }

    private void requireReviewable(Order order, Parties parties) {
        if (parties.buyerId().equals(parties.sellerUserId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SELF_REVIEW_FORBIDDEN", "不能评价自己的商品或交易");
        }
        if (!"COMPLETED".equals(order.orderStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_NOT_REVIEWABLE", "订单完成后才能评价");
        }
    }

    private Created createTradeReview(long orderId, long reviewerId, long revieweeId, Long returnId, Write input) {
        GeneratedId key = new GeneratedId();
        try {
            return created(reviews.insertTradeReview(orderId, reviewerId, revieweeId, returnId,
                    input.rating().intValueExact(), input.content().strip(), key), key);
        } catch (DuplicateKeyException exception) {
            throw alreadyReviewed();
        }
    }

    private Created created(int rows, GeneratedId key) {
        if (rows != 1 || key.getId() == null) {
            throw new IllegalStateException("Review insert did not return a record ID");
        }
        return new Created(key.getId());
    }

    private RatingSummary summarize(RatingAggregate stats) {
        BigDecimal rate = stats.ratedCount() == 0 ? null : BigDecimal.valueOf(stats.positiveCount())
                .divide(BigDecimal.valueOf(stats.ratedCount()), 4, RoundingMode.HALF_UP);
        return new RatingSummary(stats.reviewCount(), stats.ratedCount(), stats.positiveCount(), stats.averageRating(), rate);
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "REVIEW_TARGET_NOT_FOUND", "未找到可访问的评价对象");
    }

    private BusinessException alreadyReviewed() {
        return new BusinessException(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "该场景已提交评价");
    }
}
