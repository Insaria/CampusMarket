package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.dto.CatalogDtos.Product;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.CartMapper;
import edu.bistu.campusmarket.service.CartService;
import edu.bistu.campusmarket.service.CatalogService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CartTest {
    private final CartMapper mapper = mock(CartMapper.class);
    private final CatalogService catalog = mock(CatalogService.class);
    private final CartService service = new CartService(mapper, catalog);

    @Test
    void setsQuantityInTheBuyersCartWithoutReservingStock() {
        when(catalog.find(7)).thenReturn(product());
        when(mapper.lock(1)).thenReturn(21L);
        service.put(1, 7, 2);
        var calls = inOrder(mapper);
        calls.verify(mapper).ensureCart(1);
        calls.verify(mapper).lock(1);
        calls.verify(mapper).put(21, 7, 2);
        calls.verifyNoMoreInteractions();
    }

    @Test
    void rejectsInsufficientAvailableStockWithoutCreatingCart() {
        when(catalog.find(7)).thenReturn(product());
        assertThatThrownBy(() -> service.put(1, 7, 3)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void listsAndRemovesByAuthenticatedOwner() {
        service.list(1);
        service.remove(1, 7);
        verify(mapper).list(1);
        verify(mapper).remove(1, 7);
    }

    private Product product() {
        return new Product(7L, 2L, "Book", 9L, "Shop", BigDecimal.TEN, BigDecimal.TEN,
                null, null, 0, "GOOD", 2, "ACTIVE");
    }
}
