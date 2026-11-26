package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.SellerProductDtos.Draft;
import edu.bistu.campusmarket.dto.SellerProductDtos.Item;
import edu.bistu.campusmarket.dto.SellerProductDtos.Review;
import edu.bistu.campusmarket.dto.SellerProductDtos.Stock;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.SellerProductMapper;
import edu.bistu.campusmarket.mapper.NotificationMapper;
import edu.bistu.campusmarket.mapper.SellerMapper;
import edu.bistu.campusmarket.mapper.SellerMapper.Applicant;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import edu.bistu.campusmarket.service.SellerProductService;
import edu.bistu.campusmarket.service.NotificationService;
import edu.bistu.campusmarket.service.ProductImageStorage;
import java.math.BigDecimal;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SellerProductTest {
    private final SellerProductMapper mapper = mock(SellerProductMapper.class);
    private final SellerMapper accounts = mock(SellerMapper.class);
    private final NotificationMapper messages = mock(NotificationMapper.class);
    private final SellerProductService service = new SellerProductService(mapper, accounts, mock(ProductImageStorage.class),
            new NotificationService(messages));

    @Test
    void pendingCountUsesTheSameScopeAsThePaginatedReviewQueue() {
        when(mapper.countPendingReview()).thenReturn(25L);
        assertThat(service.pendingCount()).isEqualTo(25L);
        var config = new Configuration();
        config.addMapper(SellerProductMapper.class);
        String mapperPrefix = SellerProductMapper.class.getName() + ".";
        String scope = "FROM products WHERE review_status = 'PENDING' AND deleted_at IS NULL";
        assertThat(config.getMappedStatement(mapperPrefix + "countPendingReview").getBoundSql(null).getSql())
                .contains("SELECT COUNT(*)", scope).doesNotContain("LIMIT");
        assertThat(config.getMappedStatement(mapperPrefix + "pending").getBoundSql(null).getSql()).contains(scope, "LIMIT");
    }

    @Test
    void cannotSubmitWithoutMainImage() {
        when(mapper.lockEligibleSeller(17)).thenReturn(9L);
        when(mapper.lock(9, 7)).thenReturn(item(0));
        when(mapper.categoryEnabled(2)).thenReturn(true);
        assertThatThrownBy(() -> service.submit(17, 7))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("PRODUCT_MATERIALS_INCOMPLETE"));
        verify(mapper, never()).submit(7);
    }

    @Test
    void approvalAuditsWithoutPublishing() {
        when(accounts.lockApplicant(1)).thenReturn(new Applicant(1L, "ADMIN", "APPROVED", "ENABLED", 0));
        when(mapper.lockForReview(7)).thenReturn(item(0, "PENDING"), item(0, "APPROVED"));
        when(mapper.categoryEnabled(2)).thenReturn(true);
        when(mapper.hasPrimaryImage(7)).thenReturn(true);
        when(mapper.review(7, "APPROVED")).thenReturn(1);
        mockReviewAudit("APPROVED", "Approved after preview");
        String content = "商品「Book」（编号 7）审核通过，请前往商品管理手动上架。";
        when(messages.recordProductReview(70, "商品审核通过", content)).thenReturn(1);
        assertThat(service.review(1, 7, new Review("APPROVED", "Approved after preview")).listingStatus()).isEqualTo("OFF_SHELF");
        verify(messages).recordProductReview(70, "商品审核通过", content);
        verify(mapper, never()).publish(7);
    }

    @Test
    void rejectionNotifiesSellerWithOpinionAndRepeatedReviewDoesNotResend() {
        when(accounts.lockApplicant(1)).thenReturn(new Applicant(1L, "ADMIN", "APPROVED", "ENABLED", 0));
        when(mapper.lockForReview(7)).thenReturn(item(0, "PENDING"), item(0, "REJECTED"));
        when(mapper.review(7, "REJECTED")).thenReturn(1);
        mockReviewAudit("REJECTED", "请替换模糊图片");
        String content = "商品「Book」（编号 7）审核未通过，审核意见：请替换模糊图片。请修改后重新提交审核。";
        when(messages.recordProductReview(70, "商品审核未通过", content)).thenReturn(1);

        assertThat(service.review(1, 7, new Review("REJECTED", " 请替换模糊图片 ")).reviewStatus()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> service.review(1, 7, new Review("REJECTED", "请替换模糊图片")))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code()).isEqualTo("PRODUCT_STATE_CONFLICT"));
        verify(messages).recordProductReview(70, "商品审核未通过", content);
        verify(mapper).review(7, "REJECTED");
    }

    @Test
    void notificationWriteFailureRollsBackTheReviewTransaction() {
        when(accounts.lockApplicant(1)).thenReturn(new Applicant(1L, "ADMIN", "APPROVED", "ENABLED", 0));
        when(mapper.lockForReview(7)).thenReturn(item(0, "PENDING"));
        when(mapper.review(7, "REJECTED")).thenReturn(1);
        mockReviewAudit("REJECTED", "需修改");
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));

        assertThatThrownBy(() -> ((SellerProductService) proxy.getProxy()).review(1, 7, new Review("REJECTED", "需修改")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("notification insert");
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    private void mockReviewAudit(String decision, String reason) {
        when(mapper.auditReview(eq(1L), eq("PRODUCT_" + decision), eq(7L), eq(reason), any())).thenAnswer(invocation -> {
            invocation.<GeneratedId>getArgument(4).setId(70L);
            return 1;
        });
    }

    @Test
    void createsDraftForSessionSeller() {
        var draft = new Draft(2L, "Book", BigDecimal.TEN, null, null, null, false, "GOOD");
        when(mapper.lockEligibleSeller(17)).thenReturn(9L);
        when(mapper.categoryEnabled(2)).thenReturn(true);
        when(mapper.create(eq(9L), eq(draft), any())).thenAnswer(invocation -> {
            invocation.<GeneratedId>getArgument(2).setId(7L);
            return 1;
        });
        when(mapper.lock(9, 7)).thenReturn(item(0));
        assertThat(service.create(17, draft).reviewStatus()).isEqualTo("DRAFT");
    }

    @Test
    void rejectedDraftCanBeCorrectedWithoutChangingStock() {
        var draft = new Draft(2L, "Book", BigDecimal.TEN, null, null, null, false, "GOOD");
        when(mapper.lockEligibleSeller(17)).thenReturn(9L);
        when(mapper.lock(9, 7)).thenReturn(item(4, "REJECTED"), item(4));
        when(mapper.categoryEnabled(2)).thenReturn(true);
        assertThat(service.edit(17, 7, draft).availableQuantity()).isEqualTo(4);
        verify(mapper).edit(7, draft);
    }

    @Test
    void refusesIneligibleSellerBeforeProductLookup() {
        when(mapper.lockEligibleSeller(17)).thenReturn(null);
        assertThatThrownBy(() -> service.adjust(17, 7, new Stock(1, "test-1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SELLER_NOT_ACTIVE"));
        verify(mapper, never()).lock(9, 7);
    }

    @Test
    void updatesAvailableInventoryAndAppendsEvent() {
        when(mapper.lockEligibleSeller(17)).thenReturn(9L);
        when(mapper.lock(9, 7)).thenReturn(item(2), item(5));
        when(mapper.adjustment(7, "test-1")).thenReturn(null);
        when(mapper.adjust(7, 3)).thenReturn(1);
        when(mapper.inventoryEvent(7, "test-1", 3, 17)).thenReturn(1);
        assertThat(service.adjust(17, 7, new Stock(3, "test-1")).availableQuantity()).isEqualTo(5);
        verify(mapper).inventoryEvent(7, "test-1", 3, 17);
    }

    @Test
    void refusesNegativeStockAndDoesNotApplyReplay() {
        when(mapper.lockEligibleSeller(17)).thenReturn(9L);
        when(mapper.lock(9, 7)).thenReturn(item(2));
        when(mapper.adjustment(7, "test-1")).thenReturn(null);
        assertThatThrownBy(() -> service.adjust(17, 7, new Stock(-3, "test-1")))
                .isInstanceOf(BusinessException.class);
        when(mapper.adjustment(7, "test-2")).thenReturn(3);
        assertThat(service.adjust(17, 7, new Stock(3, "test-2")).availableQuantity()).isEqualTo(2);
        assertThatThrownBy(() -> service.adjust(17, 7, new Stock(4, "test-2")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("STOCK_KEY_CONFLICT"));
        verify(mapper, never()).adjust(eq(7L), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void mapsProductKeyAndScopesOwnerLock() {
        Configuration configuration = new Configuration();
        configuration.addMapper(SellerProductMapper.class);
        String prefix = SellerProductMapper.class.getName() + ".";
        assertThat(configuration.getMappedStatement(prefix + "create").getKeyProperties()).containsExactly("key.id");
        assertThat(configuration.getMappedStatement(prefix + "lock").getBoundSql(null).getSql())
                .contains("merchant_id = ?", "FOR UPDATE");
    }

    private Item item(int stock) {
        return item(stock, "DRAFT");
    }

    private Item item(int stock, String reviewStatus) {
        return new Item(7L, 2L, "Book", BigDecimal.TEN, null, null, null, 0, "GOOD", stock, 0, reviewStatus, "OFF_SHELF");
    }
}
