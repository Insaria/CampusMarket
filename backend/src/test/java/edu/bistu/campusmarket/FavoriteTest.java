package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.FavoriteMapper;
import edu.bistu.campusmarket.service.CatalogService;
import edu.bistu.campusmarket.service.FavoriteService;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FavoriteTest {
    private final FavoriteMapper mapper = mock(FavoriteMapper.class);
    private final CatalogService catalog = mock(CatalogService.class);
    private final FavoriteService service = new FavoriteService(mapper, catalog);

    @Test
    void addingUnavailableProductDoesNotWriteFavorite() {
        when(catalog.find(2)).thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Unavailable"));
        assertThatThrownBy(() -> service.add(1, 2)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void removesOnlyCurrentUsersFavorite() {
        service.remove(1, 2);
        verify(mapper).remove(1, 2);
        verifyNoInteractions(catalog);
    }

    @Test
    void mapperScopesListAndUsesExistingKeyForIdempotence() {
        Configuration configuration = new Configuration();
        configuration.addMapper(FavoriteMapper.class);
        String prefix = FavoriteMapper.class.getName() + ".";
        assertThat(configuration.getMappedStatement(prefix + "page").getBoundSql(null).getSql())
                .contains("f.user_id = ?", "p.listing_status = 'ON_SHELF'");
        assertThat(configuration.getMappedStatement(prefix + "add").getBoundSql(null).getSql())
                .contains("ON DUPLICATE KEY UPDATE");
    }
}
