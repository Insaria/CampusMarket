package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.CatalogDtos.Category;
import edu.bistu.campusmarket.dto.CatalogDtos.Page;
import edu.bistu.campusmarket.dto.CatalogDtos.Product;
import edu.bistu.campusmarket.dto.CatalogDtos.Query;
import edu.bistu.campusmarket.dto.CatalogDtos.Sort;
import edu.bistu.campusmarket.dto.CatalogDtos.Shop;
import edu.bistu.campusmarket.dto.CatalogDtos.SoldPage;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.CatalogMapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class CatalogService {
    private final CatalogMapper mapper;
    private final ProductImageStorage imageStorage;

    public CatalogService(CatalogMapper mapper, ProductImageStorage imageStorage) {
        this.mapper = mapper;
        this.imageStorage = imageStorage;
    }

    public List<Category> categories() {
        return mapper.categories();
    }

    @Transactional(readOnly = true)
    public SoldPage sold(Long sellerId, int page, int size) {
        long offset = (page - 1L) * size;
        long total = mapper.soldCount(sellerId);
        return new SoldPage(offset < total ? mapper.soldPage(sellerId, offset, size) : List.of(), total, page, size);
    }

    @Transactional(readOnly = true)
    public Page list(String keyword, Long categoryId, Long sellerId, BigDecimal minPrice, BigDecimal maxPrice,
            Sort sort, int page, int size) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PRICE_RANGE", "最低价不能高于最高价");
        }
        String pattern = keyword == null || keyword.isBlank() ? null
                : "%" + keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long offset = (page - 1L) * size;
        Query query = new Query(pattern, categoryId, sellerId, minPrice, maxPrice, sort, offset, size);
        long total = mapper.count(query);
        return new Page(offset < total ? mapper.page(query) : List.of(), total, page, size);
    }

    public Product find(long id) {
        Product product = mapper.find(id);
        if (product == null) {
            if (mapper.suspendedProduct(id)) {
                throw new BusinessException(HttpStatus.CONFLICT, "SHOP_SUSPENDED", "店铺暂时关闭，商品不可购买");
            }
            throw new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在或不可浏览");
        }
        return product;
    }

    public Shop shop(long id) {
        Shop shop = mapper.shop(id);
        if (shop == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND", "未找到店铺");
        }
        return shop;
    }

    @Transactional(readOnly = true)
    public List<Integer> imagePositions(long id) {
        find(id);
        return mapper.imagePositions(id);
    }

    @Transactional(readOnly = true)
    public byte[] image(long id, int position) {
        find(id);
        return imageStorage.read(mapper.imageReference(id, position));
    }
}
