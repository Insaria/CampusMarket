package edu.bistu.campusmarket.dto;

import java.math.BigDecimal;
import java.util.List;

public final class CatalogDtos {
    public record SoldProduct(Long id, String name, Long sellerId, String shopName, Long unitsSold) {
    }

    public record SoldPage(List<SoldProduct> items, long total, int page, int size) {
    }
    private CatalogDtos() {
    }

    public enum Sort { NEWEST, PRICE_ASC, PRICE_DESC, SALES_DESC, POSITIVE_RATE_DESC }

    public record Category(Long id, String name, String description) {
    }

    public record Product(Long id, Long categoryId, String name, Long sellerId, String shopName,
            BigDecimal originalPrice, BigDecimal price, String sizeDescription, String usageInstructions,
            Integer negotiable, String conditionCode, Integer availableQuantity, String operationStatus) {
    }

    public record Shop(Long id, String shopName, String operationStatus) {}

    public record Page(List<Product> items, long total, int page, int size) {
    }

    public record Query(String keyword, Long categoryId, Long sellerId, BigDecimal minPrice, BigDecimal maxPrice,
            Sort sort, long offset, int size) {
    }
}
