package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.AdminSellerDtos.*;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.AdminSellerMapper;
import edu.bistu.campusmarket.mapper.AdminSellerMapper.LockedSeller;
import edu.bistu.campusmarket.mapper.SellerMapper;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class AdminSellerService {
    private final AdminSellerMapper mapper;
    private final SellerMapper accounts;
    private final PrivateSellerDocuments documents;
    private final NotificationService notifications;

    public AdminSellerService(AdminSellerMapper mapper, SellerMapper accounts, PrivateSellerDocuments documents,
            NotificationService notifications) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.documents = documents;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public Page list(ReviewStatus status, boolean includePersonal, int page, int size) {
        long offset = (page - 1L) * size;
        long total = mapper.count(status, includePersonal);
        return new Page(offset < total ? mapper.page(status, includePersonal, size, offset) : List.of(), total, page, size);
    }

    public Seller find(long id) {
        return requireSeller(mapper.find(id));
    }

    public Seller findShop(long id) {
        var seller = mapper.findShop(id);
        if (seller == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "未找到店铺");
        }
        return seller;
    }

    @Transactional
    public Seller changeOperation(long actorId, long id, OperationChange input) {
        requireAdministrator(actorId);
        var shop = mapper.lockShop(id);
        if (shop == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "未找到店铺");
        }
        boolean closing = "SUSPENDED".equals(input.status());
        String action = closing ? "SHOP_CLOSED" : "SHOP_REOPENED";
        String previous = closing ? "ACTIVE" : "SUSPENDED";
        if (!"APPROVED".equals(shop.reviewStatus()) || "INACTIVE".equals(shop.operationStatus())) {
            throw conflict("SHOP_OPERATION_NOT_ALLOWED", "只能关闭或恢复已通过审核并已启用的店铺");
        }
        if (input.status().equals(shop.operationStatus())) {
            return findShop(id);
        }
        if (!previous.equals(shop.operationStatus())) {
            throw conflict("SHOP_OPERATION_NOT_ALLOWED", "当前店铺状态不允许此操作");
        }
        String reason = input.reason() == null ? "" : input.reason().strip();
        if (closing && reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SHOP_CLOSE_REASON_REQUIRED", "请填写关闭原因");
        }
        GeneratedId audit = new GeneratedId();
        if (mapper.changeOperation(id, input.status()) != 1
                || mapper.auditOperation(actorId, id, action, reason, previous, audit) != 1 || audit.getId() == null) {
            throw new IllegalStateException("Shop operation and audit could not be recorded");
        }
        notifications.emitShopOperation(shop.userId(), id, audit.getId(), closing, reason);
        return findShop(id);
    }

    public List<Level> levels() {
        return mapper.levels();
    }

    @Transactional
    public byte[] document(long actorId, long id, DocumentKind kind) {
        requireAdministrator(actorId);
        byte[] image = documents.read(mapper.document(id, kind == DocumentKind.BUSINESS_LICENSE));
        audit(actorId, "SELLER_DOCUMENT_VIEWED_" + kind.name(), id);
        return image;
    }

    @Transactional
    public Seller review(long actorId, long id, Review input) {
        requireAdministrator(actorId);
        LockedSeller seller = requireSeller(mapper.lock(id));
        if (!"PENDING".equals(seller.reviewStatus())) {
            throw conflict("SELLER_ALREADY_REVIEWED", "该商家不在待审核状态");
        }
        boolean approve = "APPROVED".equals(input.decision());
        String reason = input.reviewReason() == null ? "" : input.reviewReason().strip();
        if (!approve && reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SELLER_REVIEW_REASON_REQUIRED", "拒绝商家时请填写审核意见");
        }
        if (approve != (input.levelCode() != null)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SELLER_REVIEW", "批准时须指定等级，拒绝时不得指定等级");
        }
        if (approve && !Integer.valueOf(1).equals(seller.credentialsPresent())) {
            throw conflict("SELLER_CREDENTIALS_MISSING", "商家资质不完整");
        }
        Long levelId = approve ? enabledLevel(input.levelCode()) : null;
        if (mapper.review(id, input.decision(), approve ? "ACTIVE" : "INACTIVE", levelId) != 1) {
            throw conflict("SELLER_ALREADY_REVIEWED", "该商家不在待审核状态");
        }
        if (approve) {
            auditLevel(actorId, "SELLER_APPROVED", seller, levelId, input.levelCode(),
                    reason.isBlank() ? "商家资质审核通过" : reason);
        } else if (mapper.auditRejection(actorId, id, reason) != 1) {
            throw new IllegalStateException("Seller review audit insert did not affect one row");
        }
        return find(id);
    }

    @Transactional
    public Seller changeLevel(long actorId, long id, LevelChange input) {
        requireAdministrator(actorId);
        LockedSeller seller = requireSeller(mapper.lock(id));
        if (!"APPROVED".equals(seller.reviewStatus())) {
            throw conflict("SELLER_NOT_APPROVED", "仅已通过审核的正式商家可以调整等级");
        }
        Long levelId = enabledLevel(input.levelCode());
        if (!levelId.equals(seller.levelId())) {
            if (mapper.changeLevel(id, levelId) != 1) {
                throw new IllegalStateException("Seller level update did not affect one row");
            }
            auditLevel(actorId, "SELLER_LEVEL_CHANGED", seller, levelId, input.levelCode(), input.reason().strip());
        }
        return find(id);
    }

    private void requireAdministrator(long actorId) {
        var actor = accounts.lockApplicant(actorId);
        if (actor == null || !"ADMIN".equals(actor.roleCode()) || !"APPROVED".equals(actor.registrationStatus())
                || !"ENABLED".equals(actor.accountStatus())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足");
        }
    }

    private Long enabledLevel(int code) {
        Long id = mapper.lockEnabledLevel(code);
        if (id == null) {
            throw conflict("SELLER_LEVEL_UNAVAILABLE", "该等级不存在或未启用");
        }
        return id;
    }

    private <T> T requireSeller(T seller) {
        if (seller == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "BUSINESS_SELLER_NOT_FOUND", "未找到正式商家");
        }
        return seller;
    }

    private void audit(long actorId, String action, long id) {
        if (mapper.audit(actorId, action, id) != 1) {
            throw new IllegalStateException("Seller audit insert did not affect one row");
        }
    }

    private void auditLevel(long actorId, String action, LockedSeller seller, long levelId, int levelCode, String reason) {
        if (mapper.auditLevel(actorId, action, seller.id(), seller.levelId(), levelId, levelCode, reason) != 1) {
            throw new IllegalStateException("Seller level audit insert did not affect one row");
        }
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
