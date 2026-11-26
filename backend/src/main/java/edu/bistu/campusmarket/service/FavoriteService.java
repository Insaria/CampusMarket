package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.CatalogDtos.Page;
import edu.bistu.campusmarket.mapper.FavoriteMapper;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class FavoriteService {
    private final FavoriteMapper mapper;
    private final CatalogService catalog;

    public FavoriteService(FavoriteMapper mapper, CatalogService catalog) {
        this.mapper = mapper;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public Page list(long userId, int page, int size) {
        long offset = (page - 1L) * size;
        long total = mapper.count(userId);
        return new Page(offset < total ? mapper.page(userId, size, offset) : List.of(), total, page, size);
    }

    @Transactional
    public void add(long userId, long productId) {
        catalog.find(productId);
        mapper.add(userId, productId);
    }

    public void remove(long userId, long productId) {
        mapper.remove(userId, productId);
    }
}
