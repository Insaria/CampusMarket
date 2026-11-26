package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.bistu.campusmarket.controller.CatalogController;
import edu.bistu.campusmarket.dto.CatalogDtos.Product;
import edu.bistu.campusmarket.dto.CatalogDtos.Query;
import edu.bistu.campusmarket.dto.CatalogDtos.Sort;
import edu.bistu.campusmarket.dto.CatalogDtos.Shop;
import edu.bistu.campusmarket.dto.CatalogDtos.SoldProduct;
import edu.bistu.campusmarket.exception.GlobalApiExceptionHandler;
import edu.bistu.campusmarket.mapper.CatalogMapper;
import edu.bistu.campusmarket.mapper.FavoriteMapper;
import edu.bistu.campusmarket.security.ApiAccessInterceptor;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.CatalogService;
import edu.bistu.campusmarket.service.ProductImageStorage;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogTest {
    private final CatalogMapper mapper = mock(CatalogMapper.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new CatalogController(new CatalogService(mapper, mock(ProductImageStorage.class))))
            .addInterceptors(new ApiAccessInterceptor(currentUser))
            .setControllerAdvice(new GlobalApiExceptionHandler()).build();

    @Test
    void closedShopShowsStatusButKeepsListingsOutOfCatalogAndInOwnedFavorites() throws Exception {
        when(mapper.shop(4)).thenReturn(new Shop(4L, "Test shop", "SUSPENDED"));
        when(mapper.suspendedProduct(7)).thenReturn(true);
        mvc.perform(get("/api/shops/4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationStatus").value("SUSPENDED"));
        mvc.perform(get("/api/products/7")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOP_SUSPENDED"));
        mvc.perform(get("/api/products/7/images/0")).andExpect(status().isConflict());
        assertThat(catalogSql("page", new Query(null, null, 4L, null, null, Sort.NEWEST, 0, 20)))
                .contains("m.operation_status = 'ACTIVE'", "p.review_status = 'APPROVED'",
                        "p.listing_status = 'ON_SHELF'");
        assertThat(FavoriteMapper.FAVORITE_PRODUCTS)
                .contains("m.operation_status IN ('ACTIVE', 'SUSPENDED')", "f.user_id = #{userId}");
    }

    @Test
    void soldHistoryUsesCompletedNetSalesAndSellerPaging() throws Exception {
        when(mapper.soldCount(4L)).thenReturn(8L);
        when(mapper.soldPage(4L, 3, 3)).thenReturn(List.of(new SoldProduct(11L, "Book", 4L, "Shop", 2L)));
        mvc.perform(get("/api/products/sold").param("sellerId", "4").param("page", "2").param("size", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.items[0].unitsSold").value(2));
        Configuration configuration = new Configuration();
        configuration.addMapper(CatalogMapper.class);
        String sql = configuration.getMappedStatement(CatalogMapper.class.getName() + ".soldPage").getBoundSql(null).getSql();
        assertThat(sql).contains("o.order_status = 'COMPLETED'", "i.quantity - i.restocked_quantity", "p.deleted_at IS NULL")
                .doesNotContain("p.listing_status = 'ON_SHELF'", "buyer_id");
    }

    @Test
    void anonymousCanSearchAndFiltersRemainLiteral() throws Exception {
        when(mapper.count(new Query("%book!_!%%", 3L, 4L, null, new BigDecimal("50"),
                Sort.PRICE_ASC, 10L, 10))).thenReturn(9L);
        mvc.perform(get("/api/products").param("keyword", "book_%").param("categoryId", "3")
                        .param("sellerId", "4").param("maxPrice", "50").param("sort", "PRICE_ASC")
                        .param("page", "2").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(9))
                .andExpect(jsonPath("$.data.items").isEmpty());
        verifyNoInteractions(currentUser);
    }

    @Test
    void rejectsInvalidPriceRangeAndPageSize() throws Exception {
        mvc.perform(get("/api/products").param("minPrice", "10").param("maxPrice", "2"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRICE_RANGE"));
        mvc.perform(get("/api/products").param("size", "101")).andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @Test
    void unavailableProductIsNotFound() throws Exception {
        mvc.perform(get("/api/products/7")).andExpect(status().isNotFound());
    }

    @Test
    void searchesByNameAcrossSellersWithoutRequiringASellerFilter() throws Exception {
        var query = new Query("%book%", null, null, null, null, Sort.SALES_DESC, 0, 20);
        when(mapper.count(query)).thenReturn(2L);
        when(mapper.page(query)).thenReturn(List.of(product(7L, 3L), product(8L, 4L)));

        mvc.perform(get("/api/products").param("keyword", "book").param("sort", "SALES_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].sellerId").value(3))
                .andExpect(jsonPath("$.data.items[1].sellerId").value(4));
    }

    @Test
    void acceptsPositiveRateWithAllFiltersAndRejectsUnknownSort() throws Exception {
        var query = new Query("%book%", 2L, 3L, BigDecimal.ONE, BigDecimal.TEN, Sort.POSITIVE_RATE_DESC, 5, 5);
        when(mapper.count(query)).thenReturn(6L);
        when(mapper.page(query)).thenReturn(List.of(product(7L, 3L)));

        mvc.perform(get("/api/products").param("keyword", "book").param("categoryId", "2").param("sellerId", "3")
                        .param("minPrice", "1").param("maxPrice", "10").param("sort", "POSITIVE_RATE_DESC")
                        .param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.items[0].id").value(7));
        mvc.perform(get("/api/products").param("sort", "price DESC, p.id"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapperBindsFiltersAndScopesPublicQueries() {
        Configuration configuration = new Configuration();
        configuration.addMapper(CatalogMapper.class);
        var statement = configuration.getMappedStatement(CatalogMapper.class.getName() + ".page");
        var sql = statement.getBoundSql(new Query("%book%", 3L, 4L, BigDecimal.ONE, null, Sort.PRICE_DESC, 0, 20));
        assertThat(normalize(sql.getSql())).contains("p.review_status = 'APPROVED'", "p.listing_status = 'ON_SHELF'",
                "p.deleted_at IS NULL", "u.account_status = 'ENABLED'", "l.is_enabled = 1",
                "p.name LIKE ?", "p.category_id = ?", "p.merchant_id = ?", "ORDER BY price DESC")
                .doesNotContain("%book%");
    }

    @ParameterizedTest
    @CsvSource(value = {
        "NEWEST|p.first_listed_at DESC, p.id DESC",
        "PRICE_ASC|price ASC, p.first_listed_at DESC, p.id DESC",
        "PRICE_DESC|price DESC, p.first_listed_at DESC, p.id DESC",
        "SALES_DESC|COALESCE(sales.units_sold, 0) DESC, p.first_listed_at DESC, p.id DESC",
        "POSITIVE_RATE_DESC|COALESCE(ratings.positive_rate, 0) DESC, p.first_listed_at DESC, p.id DESC"
    }, delimiter = '|')
    void eachSortIsStableAndSharesCountFilters(Sort sort, String orderBy) {
        var query = new Query("%book%", 2L, 3L, BigDecimal.ONE, BigDecimal.TEN, sort, 20, 10);
        String page = catalogSql("page", query);
        String count = catalogSql("count", query);
        String visibility = "WHERE p.review_status";
        int pageVisibility = page.lastIndexOf(visibility);
        assertThat(page.substring(pageVisibility, page.lastIndexOf("ORDER BY")).strip())
                .isEqualTo(count.substring(count.lastIndexOf(visibility)));
        assertThat(page).endsWith("ORDER BY " + orderBy + " LIMIT ? OFFSET ?");
        assertThat(page).contains("COALESCE(p.discount_price, p.original_price) AS price",
                "COALESCE(p.discount_price, p.original_price) >= ?",
                "COALESCE(p.discount_price, p.original_price) <= ?");
        assertThat(count).doesNotContain("order_items", "product_reviews");
    }

    @Test
    void salesUseCompletedOrderQuantityMinusAcceptedRestocksExactlyOnce() {
        String sql = catalogSql("page", new Query(null, null, null, null, null, Sort.SALES_DESC, 0, 20));
        assertThat(sql).contains("SUM(i.quantity - i.restocked_quantity)", "o.order_status = 'COMPLETED'",
                "GROUP BY i.product_id", "COALESCE(sales.units_sold, 0)")
                .doesNotContain("return_requests", "inventory_events", "product_reviews", "cart_items");
    }

    @Test
    void ratingsUseOnlyProductStarsAndDefaultMissingRatingsToZero() {
        String sql = catalogSql("page", new Query(null, null, null, null, null, Sort.POSITIVE_RATE_DESC, 0, 20));
        assertThat(sql).contains("JOIN product_reviews r ON r.order_item_id = i.id",
                "COUNT(CASE WHEN rating BETWEEN 4 AND 5 THEN 1 END)", "NULLIF(COUNT(rating), 0)",
                "COALESCE(ratings.positive_rate, 0)")
                .doesNotContain("trade_reviews", "SUM(i.quantity");
    }

    private String catalogSql(String method, Query query) {
        Configuration configuration = new Configuration();
        configuration.addMapper(CatalogMapper.class);
        return normalize(configuration.getMappedStatement(CatalogMapper.class.getName() + "." + method)
                .getBoundSql(query).getSql());
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    private Product product(long id, long sellerId) {
        return new Product(id, 2L, "Book " + id, sellerId, "Shop " + sellerId, BigDecimal.TEN, BigDecimal.ONE,
                null, null, 0, "USED", 1, "ACTIVE");
    }
}
