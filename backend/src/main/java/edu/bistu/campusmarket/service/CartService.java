package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.CartMapper;
import edu.bistu.campusmarket.mapper.CartMapper.Row;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class CartService {
    private final CartMapper mapper;
    private final CatalogService catalog;

    public CartService(CartMapper mapper, CatalogService catalog) {
        this.mapper = mapper;
        this.catalog = catalog;
    }

    public List<Row> list(long userId) {
        return mapper.list(userId);
    }

    @Transactional
    public void put(long userId, long productId, int quantity) {
        var product = catalog.find(productId);
        if (quantity > product.availableQuantity()) {
            throw new BusinessException(HttpStatus.CONFLICT, "STOCK_UNAVAILABLE", "可售库存不足");
        }
        mapper.ensureCart(userId);
        mapper.put(mapper.lock(userId), productId, quantity);
    }

    public void remove(long userId, long productId) {
        mapper.remove(userId, productId);
    }
}
