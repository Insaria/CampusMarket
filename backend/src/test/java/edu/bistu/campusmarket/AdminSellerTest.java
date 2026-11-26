package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.AdminSellerDtos.DocumentKind;
import edu.bistu.campusmarket.dto.AdminSellerDtos.LevelChange;
import edu.bistu.campusmarket.dto.AdminSellerDtos.OperationChange;
import edu.bistu.campusmarket.dto.AdminSellerDtos.Review;
import edu.bistu.campusmarket.dto.AdminSellerDtos.Seller;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.AdminSellerMapper;
import edu.bistu.campusmarket.mapper.AdminSellerMapper.LockedSeller;
import edu.bistu.campusmarket.mapper.AdminSellerMapper.Shop;
import edu.bistu.campusmarket.mapper.SellerMapper;
import edu.bistu.campusmarket.mapper.SellerMapper.Applicant;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.service.AdminSellerService;
import edu.bistu.campusmarket.service.NotificationService;
import edu.bistu.campusmarket.service.PrivateSellerDocuments;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class AdminSellerTest {
    private final AdminSellerMapper mapper = mock(AdminSellerMapper.class);
    private final SellerMapper accounts = mock(SellerMapper.class);
    private final PrivateSellerDocuments documents = mock(PrivateSellerDocuments.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AdminSellerService service = new AdminSellerService(mapper, accounts, documents, notifications);

    @BeforeEach
    void administrator() {
        when(accounts.lockApplicant(1)).thenReturn(new Applicant(1L, "ADMIN", "APPROVED", "ENABLED", 0));
    }

    @Test
    void closesAndReopensApprovedShopWithOneAuditAndNotificationPerTransition() {
        when(mapper.lockShop(2)).thenReturn(new Shop(5L, "APPROVED", "ACTIVE"), new Shop(5L, "APPROVED", "SUSPENDED"),
                new Shop(5L, "APPROVED", "SUSPENDED"), new Shop(5L, "APPROVED", "ACTIVE"));
        when(mapper.findShop(2)).thenReturn(new Seller(2L, 5L, "Shop", null, "APPROVED", "SUSPENDED", null, null, "PERSONAL", "盘点"),
                new Seller(2L, 5L, "Shop", null, "APPROVED", "SUSPENDED", null, null, "PERSONAL", "盘点"),
                new Seller(2L, 5L, "Shop", null, "APPROVED", "ACTIVE", null, null, "PERSONAL", null));
        when(mapper.changeOperation(eq(2L), any())).thenReturn(1);
        when(mapper.auditOperation(eq(1L), eq(2L), any(), any(), any(), any())).thenAnswer(call -> {
            call.<GeneratedId>getArgument(5).setId("SHOP_CLOSED".equals(call.getArgument(2)) ? 50L : 51L);
            return 1;
        });
        var close = new OperationChange("SUSPENDED", " 盘点 ");
        var reopen = new OperationChange("ACTIVE", null);
        assertThat(service.changeOperation(1, 2, close).closureReason()).isEqualTo("盘点");
        service.changeOperation(1, 2, close);
        assertThat(service.changeOperation(1, 2, reopen).operationStatus()).isEqualTo("ACTIVE");
        service.changeOperation(1, 2, reopen);
        verify(mapper).changeOperation(2, "SUSPENDED");
        verify(mapper).changeOperation(2, "ACTIVE");
        verify(mapper).auditOperation(eq(1L), eq(2L), eq("SHOP_CLOSED"), eq("盘点"), eq("ACTIVE"), any());
        verify(mapper).auditOperation(eq(1L), eq(2L), eq("SHOP_REOPENED"), eq(""), eq("SUSPENDED"), any());
        verify(notifications).emitShopOperation(5, 2, 50, true, "盘点");
        verify(notifications).emitShopOperation(5, 2, 51, false, "");
    }

    @Test
    void requiresReasonAndDoesNotEnablePreviouslyInactiveShop() {
        when(mapper.lockShop(2)).thenReturn(new Shop(5L, "APPROVED", "ACTIVE"), new Shop(5L, "PENDING", "INACTIVE"));
        assertThatThrownBy(() -> service.changeOperation(1, 2, new OperationChange("SUSPENDED", " ")))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code()).isEqualTo("SHOP_CLOSE_REASON_REQUIRED"));
        assertThatThrownBy(() -> service.changeOperation(1, 2, new OperationChange("ACTIVE", null)))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code()).isEqualTo("SHOP_OPERATION_NOT_ALLOWED"));
        verify(mapper, never()).changeOperation(eq(2L), any());
    }

    @Test
    void failedNotificationRollsBackShopStatusAndAudit() {
        when(mapper.lockShop(2)).thenReturn(new Shop(5L, "APPROVED", "ACTIVE"));
        when(mapper.changeOperation(2, "SUSPENDED")).thenReturn(1);
        when(mapper.auditOperation(eq(1L), eq(2L), eq("SHOP_CLOSED"), eq("盘点"), eq("ACTIVE"), any()))
                .thenAnswer(call -> {
                    call.<GeneratedId>getArgument(5).setId(50L);
                    return 1;
                });
        doThrow(new IllegalStateException("notification failed")).when(notifications).emitShopOperation(5, 2, 50, true, "盘点");
        var manager = mock(PlatformTransactionManager.class);
        var tx = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(tx);
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        assertThatThrownBy(() -> ((AdminSellerService) proxy.getProxy()).changeOperation(1, 2,
                new OperationChange("SUSPENDED", "盘点"))).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(tx);
        verify(manager, never()).commit(tx);
    }

    @Test
    void approvalAssignsEnabledLevelAndAudits() {
        when(mapper.lock(2)).thenReturn(new LockedSeller(2L, "PENDING", null, 1));
        when(mapper.lockEnabledLevel(3)).thenReturn(30L);
        when(mapper.review(2, "APPROVED", "ACTIVE", 30L)).thenReturn(1);
        when(mapper.auditLevel(1, "SELLER_APPROVED", 2, null, 30L, 3, "商家资质审核通过")).thenReturn(1);
        when(mapper.find(2)).thenReturn(new Seller(2L, 5L, "Shop", null, "APPROVED", "ACTIVE", 3, null, "BUSINESS", null));
        assertThat(service.review(1, 2, new Review("APPROVED", 3, null)).levelCode()).isEqualTo(3);
        verify(mapper).auditLevel(1, "SELLER_APPROVED", 2, null, 30L, 3, "商家资质审核通过");
    }

    @Test
    void rejectionStaysInactiveAndHasNoLevel() {
        when(mapper.lock(2)).thenReturn(new LockedSeller(2L, "PENDING", null, 1));
        when(mapper.review(2, "REJECTED", "INACTIVE", null)).thenReturn(1);
        when(mapper.auditRejection(1, 2, "Missing credentials")).thenReturn(1);
        when(mapper.find(2)).thenReturn(new Seller(2L, 5L, "Shop", null, "REJECTED", "INACTIVE", null, "Missing credentials", "BUSINESS", null));
        assertThat(service.review(1, 2, new Review("REJECTED", null, " Missing credentials ")).reviewReason())
                .isEqualTo("Missing credentials");
    }

    @Test
    void approvalRejectsUnavailableLevelAndRepeatReview() {
        when(mapper.lockEnabledLevel(3)).thenReturn(null);
        when(mapper.lock(2)).thenReturn(new LockedSeller(2L, "PENDING", null, 1),
                new LockedSeller(2L, "APPROVED", 10L, 1));
        assertThatThrownBy(() -> service.review(1, 2, new Review("APPROVED", 3, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SELLER_LEVEL_UNAVAILABLE"));
        assertThatThrownBy(() -> service.review(1, 2, new Review("APPROVED", 3, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SELLER_ALREADY_REVIEWED"));
        verify(mapper, never()).auditLevel(1, "SELLER_APPROVED", 2, null, 30L, 3, "商家资质审核通过");
    }

    @Test
    void readsPrivateDocumentAndAuditsAccess() {
        when(mapper.document(2, true)).thenReturn("seller-123.png");
        when(documents.read("seller-123.png")).thenReturn(new byte[]{1});
        when(mapper.audit(1, "SELLER_DOCUMENT_VIEWED_BUSINESS_LICENSE", 2)).thenReturn(1);
        assertThat(service.document(1, 2, DocumentKind.BUSINESS_LICENSE)).containsExactly((byte) 1);
    }

    @Test
    void levelChangeIsAuditedAndDoesNotAlterReview() {
        when(mapper.lock(2)).thenReturn(new LockedSeller(2L, "APPROVED", 10L, 1));
        when(mapper.lockEnabledLevel(3)).thenReturn(30L);
        when(mapper.changeLevel(2, 30L)).thenReturn(1);
        when(mapper.auditLevel(1, "SELLER_LEVEL_CHANGED", 2, 10L, 30L, 3, "经营数据复核")).thenReturn(1);
        when(mapper.find(2)).thenReturn(new Seller(2L, 5L, "Shop", null, "APPROVED", "ACTIVE", 3, null, "BUSINESS", null));
        var seller = service.changeLevel(1, 2, new LevelChange(3, " 经营数据复核 "));
        assertThat(seller.reviewStatus()).isEqualTo("APPROVED");
        assertThat(seller.levelCode()).isEqualTo(3);
        verify(mapper).auditLevel(1, "SELLER_LEVEL_CHANGED", 2, 10L, 30L, 3, "经营数据复核");
    }

    @Test
    void unavailableLevelCannotBeAssigned() {
        when(mapper.lock(2)).thenReturn(new LockedSeller(2L, "APPROVED", 10L, 1));
        when(mapper.lockEnabledLevel(3)).thenReturn(null);

        assertThatThrownBy(() -> service.changeLevel(1, 2, new LevelChange(3, "经营数据复核")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SELLER_LEVEL_UNAVAILABLE"));
        verify(mapper, never()).changeLevel(2, 30L);
    }

    @Test
    void auditFailureRollsBackTheLevelChange() {
        when(mapper.lock(2)).thenReturn(new LockedSeller(2L, "APPROVED", 10L, 1));
        when(mapper.lockEnabledLevel(3)).thenReturn(30L);
        when(mapper.changeLevel(2, 30L)).thenReturn(1);
        var manager = mock(PlatformTransactionManager.class);
        var tx = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(tx);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service);
        factory.addAdvice(interceptor);
        var proxy = (AdminSellerService) factory.getProxy();

        assertThatThrownBy(() -> proxy.changeLevel(1, 2, new LevelChange(3, "经营数据复核")))
                .isInstanceOf(IllegalStateException.class);

        verify(mapper).changeLevel(2, 30L);
        verify(manager).rollback(tx);
        verify(manager, never()).commit(tx);
    }

    @Test
    void merchantCannotChangeLevelThroughService() {
        when(accounts.lockApplicant(9)).thenReturn(new Applicant(9L, "USER", "APPROVED", "ENABLED", 0));
        assertThatThrownBy(() -> service.changeLevel(9, 2, new LevelChange(3, "经营数据复核")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ACCESS_DENIED"));
        verify(mapper, never()).lock(2);
    }

    @Test
    void mapperRegistersScopedOperations() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AdminSellerMapper.class);
        var sql = configuration.getMappedStatement(AdminSellerMapper.class.getName() + ".review").getBoundSql(null);
        assertThat(sql.getSql()).contains("seller_type = 'BUSINESS'", "review_status = 'PENDING'");
        var update = configuration.getMappedStatement(AdminSellerMapper.class.getName() + ".changeLevel").getBoundSql(null);
        assertThat(update.getSql()).contains("UPDATE merchants SET level_id").doesNotContain("settlements");
        var audit = configuration.getMappedStatement(AdminSellerMapper.class.getName() + ".auditLevel").getBoundSql(null);
        assertThat(audit.getSql()).contains("actor_user_id", "'oldLevelId'", "'oldLevelCode'", "'newLevelId'", "'reason'");
    }
}
