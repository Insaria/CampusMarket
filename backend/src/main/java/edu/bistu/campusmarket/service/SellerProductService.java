package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.SellerProductDtos.Draft;
import edu.bistu.campusmarket.dto.SellerProductDtos.Item;
import edu.bistu.campusmarket.dto.SellerProductDtos.ImageReceipt;
import edu.bistu.campusmarket.dto.SellerProductDtos.Page;
import edu.bistu.campusmarket.dto.SellerProductDtos.Review;
import edu.bistu.campusmarket.dto.SellerProductDtos.ReviewDetail;
import edu.bistu.campusmarket.dto.SellerProductDtos.Stock;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.SellerProductMapper;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.mapper.SellerMapper;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("db")
public class SellerProductService {
    private final SellerProductMapper mapper;
    private final SellerMapper accounts;
    private final ProductImageStorage imageStorage;

    private final NotificationService notifications;

    public SellerProductService(SellerProductMapper mapper, SellerMapper accounts, ProductImageStorage imageStorage,
            NotificationService notifications) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.imageStorage = imageStorage;
        this.notifications = notifications;
    }

    @Transactional
    public Item create(long userId, Draft input) {
        long sellerId = requireSeller(userId);
        validateDraft(input);
        GeneratedId key = new GeneratedId();
        if (mapper.create(sellerId, input, key) != 1 || key.getId() == null) {
            throw new IllegalStateException("Product insert did not generate an ID");
        }
        return requireProduct(sellerId, key.getId());
    }

    @Transactional
    public Item edit(long userId, long id, Draft input) {
        long sellerId = requireSeller(userId);
        Item item = requireProduct(sellerId, id);
        if (!"DRAFT".equals(item.reviewStatus()) && !"REJECTED".equals(item.reviewStatus())) {
            throw stateConflict();
        }
        validateDraft(input);
        mapper.edit(id, input);
        return requireProduct(sellerId, id);
    }

    private void validateDraft(Draft input) {
        if (input.discountPrice() != null && input.discountPrice().compareTo(input.originalPrice()) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT", "折扣价不能高于原价");
        }
        if (!mapper.categoryEnabled(input.categoryId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CATEGORY_UNAVAILABLE", "分类不存在或未启用");
        }
    }

    @Transactional
    public Page list(long userId, int page, int size) {
        long sellerId = requireSeller(userId);
        long offset = (page - 1L) * size;
        long total = mapper.count(sellerId);
        return new Page(offset < total ? mapper.page(sellerId, size, offset) : List.of(), total, page, size);
    }

    @Transactional
    public Item adjust(long userId, long productId, Stock input) {
        long sellerId = requireSeller(userId);
        Item current = requireProduct(sellerId, productId);
        Integer priorDelta = mapper.adjustment(productId, input.operationKey());
        if (priorDelta != null) {
            if (!priorDelta.equals(input.delta())) {
                throw new BusinessException(HttpStatus.CONFLICT, "STOCK_KEY_CONFLICT", "操作键已用于不同的库存变动");
            }
            return current;
        }
        if (input.delta() == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STOCK_DELTA", "库存变动量不能为零");
        }
        long quantity = (long) current.availableQuantity() + input.delta();
        if (quantity < 0 || quantity > Integer.MAX_VALUE) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STOCK_QUANTITY", "可售库存超出允许范围");
        }
        if (mapper.adjust(productId, input.delta()) != 1
                || mapper.inventoryEvent(productId, input.operationKey(), input.delta(), userId) != 1) {
            throw new IllegalStateException("Inventory update did not write both records");
        }
        return requireProduct(sellerId, productId);
    }

    @Transactional
    public Item submit(long userId, long id) {
        long sellerId = requireSeller(userId);
        Item product = requireProduct(sellerId, id);
        if (!"DRAFT".equals(product.reviewStatus())) {
            throw stateConflict();
        }
        requireListingMaterials(product);
        auditChange(mapper.submit(id), userId, "PRODUCT_SUBMITTED", id);
        return requireProduct(sellerId, id);
    }

    @Transactional
    public ImageReceipt appendImage(long userId, long id, MultipartFile upload) {
        Item product = requireProduct(requireSeller(userId), id);
        if (!"DRAFT".equals(product.reviewStatus())) {
            throw stateConflict();
        }
        int position = mapper.nextImagePosition(id);
        if (position >= 10) {
            throw new BusinessException(HttpStatus.CONFLICT, "PRODUCT_IMAGE_LIMIT", "商品最多上传十张图片");
        }
        String reference = imageStorage.store(upload);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    imageStorage.discard(reference);
                }
            }
        });
        boolean primary = !mapper.hasPrimaryImage(id);
        if (mapper.insertImage(id, reference, position, primary) != 1) {
            throw new IllegalStateException("Product image insert did not affect one row");
        }
        return new ImageReceipt(position, primary);
    }

    @Transactional
    public Item publish(long userId, long id) {
        long sellerId = requireSeller(userId);
        Item product = requireProduct(sellerId, id);
        if (!"APPROVED".equals(product.reviewStatus()) || !"OFF_SHELF".equals(product.listingStatus())) {
            throw stateConflict();
        }
        requireListingMaterials(product);
        auditChange(mapper.publish(id), userId, "PRODUCT_PUBLISHED", id);
        return requireProduct(sellerId, id);
    }

    @Transactional
    public Item unpublish(long userId, long id) {
        long sellerId = requireSeller(userId);
        requireProduct(sellerId, id);
        auditChange(mapper.unpublish(id), userId, "PRODUCT_UNPUBLISHED", id);
        return requireProduct(sellerId, id);
    }

    public List<Item> pending(int page, int size) {
        return mapper.pending(size, (page - 1L) * size);
    }

    public long pendingCount() {
        return mapper.countPendingReview();
    }

    @Transactional(readOnly = true)
    public ReviewDetail reviewDetail(long id) {
        return new ReviewDetail(requireReviewProduct(mapper.findForReview(id)), mapper.reviewSeller(id),
                mapper.reviewImagePositions(id), mapper.latestReviewReason(id));
    }

    @Transactional(readOnly = true)
    public byte[] reviewImage(long id, int position) {
        requireReviewProduct(mapper.findForReview(id));
        String reference = mapper.reviewImage(id, position);
        if (reference == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_IMAGE_NOT_FOUND", "商品图片不存在");
        }
        return imageStorage.read(reference);
    }

    @Transactional
    public Item review(long actorId, long id, Review input) {
        var actor = accounts.lockApplicant(actorId);
        if (actor == null || !"ADMIN".equals(actor.roleCode()) || !"APPROVED".equals(actor.registrationStatus())
                || !"ENABLED".equals(actor.accountStatus())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足");
        }
        Item product = requireReviewProduct(mapper.lockForReview(id));
        if (!"PENDING".equals(product.reviewStatus())) {
            throw stateConflict();
        }
        String decision = input.decision();
        String reason = input.reviewReason() == null ? null : input.reviewReason().strip();
        if ("APPROVED".equals(decision)) {
            requireListingMaterials(product);
        } else if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_REVIEW_REASON_REQUIRED", "拒绝商品时请填写审核意见");
        }
        if (mapper.review(id, decision) != 1) throw stateConflict();
        GeneratedId auditKey = new GeneratedId();
        if (mapper.auditReview(actorId, "PRODUCT_" + decision, id, reason, auditKey) != 1 || auditKey.getId() == null) {
            throw new IllegalStateException("Product review audit insert did not affect one row");
        }
        notifications.emitProductReview(auditKey.getId(), id, product.name(), "APPROVED".equals(decision), reason);
        return mapper.lockForReview(id);
    }

    private Item requireReviewProduct(Item product) {
        if (product == null) throw new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在");
        return product;
    }

    private void requireListingMaterials(Item product) {
        if (!mapper.categoryEnabled(product.categoryId()) || !mapper.hasPrimaryImage(product.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PRODUCT_MATERIALS_INCOMPLETE", "需使用启用分类并设置商品主图");
        }
    }

    private void auditChange(int changed, long actor, String action, long id) {
        if (changed != 1) {
            throw stateConflict();
        }
        if (mapper.audit(actor, action, id) != 1) {
            throw new IllegalStateException("Product audit insert did not affect one row");
        }
    }

    private BusinessException stateConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "PRODUCT_STATE_CONFLICT", "当前商品状态不允许此操作");
    }

    private long requireSeller(long userId) {
        Long sellerId = mapper.lockEligibleSeller(userId);
        if (sellerId == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SELLER_NOT_ACTIVE", "卖家不具备经营资格");
        }
        return sellerId;
    }

    private Item requireProduct(long sellerId, long id) {
        Item item = mapper.lock(sellerId, id);
        if (item == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在");
        }
        return item;
    }
}
