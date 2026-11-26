package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.AdminSellerDtos.Seller;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Detail;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Filter;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Group;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.GroupBy;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Totals;
import edu.bistu.campusmarket.dto.ReviewDtos.RatingSummary;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.AdminSettlementMapper;
import edu.bistu.campusmarket.service.AdminSellerService;
import edu.bistu.campusmarket.service.AdminSettlementService;
import edu.bistu.campusmarket.service.ReviewService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminSettlementTest {
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-20T08:00:00+08:00");
    private static final OffsetDateTime TO = FROM.plusDays(1);
    private static final LocalDateTime UTC_FROM = LocalDateTime.parse("2026-11-20T00:00:00");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal AMOUNT = new BigDecimal("200.00");
    private static final BigDecimal FEE = new BigDecimal("0.30");
    private final AdminSettlementMapper mapper = mock(AdminSettlementMapper.class);
    private final AdminSellerService sellers = mock(AdminSellerService.class);
    private final ReviewService reviews = mock(ReviewService.class);
    private final AdminSettlementService service = new AdminSettlementService(mapper, sellers, reviews);

    @Test
    void returnsPlatformTotalsAndZeroWhenEmpty() {
        var filter = new Filter(null, null, null, null);
        var total = new Totals(3, AMOUNT, FEE);
        var empty = new Totals(0, ZERO, ZERO);
        when(mapper.totals(filter)).thenReturn(total, empty);

        assertThat(service.fees(null, null, null, null, null).totals()).isEqualTo(total);
        var result = service.fees(null, null, null, null, null);
        assertThat(result.totals()).isEqualTo(empty);
        assertThat(result.groups()).isEmpty();
    }

    @Test
    void filtersUtcTimeAndGroupsBySellerOrHistoricalLevel() {
        var filter = new Filter(UTC_FROM, UTC_FROM.plusDays(1), 7L, 1);
        var totals = new Totals(1, AMOUNT, FEE);
        var seller = new Group(7L, null, 1, AMOUNT, FEE);
        var level = new Group(null, 1, 1, AMOUNT, FEE);
        when(mapper.totals(filter)).thenReturn(totals);
        when(mapper.bySeller(filter)).thenReturn(List.of(seller));
        when(mapper.byLevel(filter)).thenReturn(List.of(level));

        assertThat(service.fees(FROM, TO, 7L, 1, GroupBy.SELLER).groups()).containsExactly(seller);
        assertThat(service.fees(FROM, TO, 7L, 1, GroupBy.LEVEL).groups()).containsExactly(level);
    }

    @Test
    void merchantMetricsReuseCurrentLevelAndSellerSatisfaction() {
        var seller = new Seller(7L, 8L, "Business", null, "APPROVED", "ACTIVE", 5, null, "BUSINESS", null);
        var totals = new Totals(3, AMOUNT, FEE);
        var satisfaction = new RatingSummary(1, 1, 1, BigDecimal.valueOf(5), BigDecimal.ONE);
        when(sellers.find(7L)).thenReturn(seller);
        when(mapper.totals(new Filter(null, null, 7L, null))).thenReturn(totals);
        when(reviews.sellerSatisfaction(8L)).thenReturn(satisfaction);

        var metrics = service.sellerMetrics(7L);
        assertThat(metrics.seller().levelCode()).isEqualTo(5);
        assertThat(metrics.settlements()).isEqualTo(totals);
        assertThat(metrics.satisfaction()).isEqualTo(satisfaction);
    }

    @Test
    void detailsReturnStoredRateAndPaginateWithSameParameters() {
        var filter = new Filter(UTC_FROM, null, 7L, null);
        var detail = new Detail(1L, 2L, 7L, 1, new BigDecimal("0.001000"), UTC_FROM,
                AMOUNT, AMOUNT, FEE, AMOUNT.subtract(FEE), UTC_FROM);
        when(mapper.count(filter)).thenReturn(3L);
        when(mapper.page(filter, 2, 2L)).thenReturn(List.of(detail));

        var page = service.details(FROM, null, 7L, null, 2, 2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).containsExactly(detail);
        assertThat(service.details(FROM, null, 7L, null, 3, 2).items()).isEmpty();
    }

    @Test
    void invalidDateRangeIsRejected() {
        assertThatThrownBy(() -> service.fees(FROM, FROM, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_SETTLEMENT_RANGE"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"totals", "bySeller", "byLevel", "count", "page"})
    void reportSqlBindsSnapshotFiltersAndDoesNotDeductRefundFees(String method) {
        var configuration = new Configuration();
        configuration.addMapper(AdminSettlementMapper.class);
        var sql = configuration.getMappedStatement(AdminSettlementMapper.class.getName() + "." + method)
                .getBoundSql(Map.of("filter", new Filter(UTC_FROM, UTC_FROM.plusDays(1), 7L, 1), "size", 20, "offset", 0L))
                .getSql().replaceAll("\\s+", " ").strip();

        assertThat(sql).contains("FROM settlements s", "s.settled_at >= ?", "s.settled_at < ?",
                "o.merchant_id = ?", "s.level_code_snapshot = ?")
                .doesNotContain("refunds", "fee_reversal_amount", "merchant_levels", "JOIN merchants");
        if (method.equals("byLevel")) {
            assertThat(sql).contains("GROUP BY s.level_code_snapshot");
        }
        if (method.equals("bySeller")) {
            assertThat(sql).contains("GROUP BY o.merchant_id");
        }
        if (method.equals("page")) {
            assertThat(sql).contains("s.fee_rate_snapshot", "ORDER BY s.settled_at DESC, s.id DESC");
        }
    }
}
