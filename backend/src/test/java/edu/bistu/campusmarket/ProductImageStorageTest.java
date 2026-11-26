package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.CatalogMapper;
import edu.bistu.campusmarket.service.CatalogService;
import edu.bistu.campusmarket.service.ProductImageStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class ProductImageStorageTest {
    @Test
    void rejectsOverlappingPrivateDirectories(@TempDir Path root) throws Exception {
        Path child = Files.createDirectory(root.resolve("private"));
        var storage = new ProductImageStorage(root.toString(), child.toString());
        assertThatThrownBy(() -> storage.read("seller-123.png")).isInstanceOf(BusinessException.class);
    }

    @Test
    void storesAndReadsValidatedImagesByOpaqueReference(@TempDir Path root) throws Exception {
        var storage = new ProductImageStorage(root.toString(), "");
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        String ref = storage.store(new MockMultipartFile("image", "../../local.png", "text/plain", bytes.toByteArray()));
        assertThat(ref).matches("seller-[0-9]+\\.png");
        assertThat(storage.read(ref)).isNotEmpty();
        assertThatThrownBy(() -> storage.read("../" + ref)).isInstanceOf(BusinessException.class);
        storage.discard(ref);
        assertThat(root.resolve(ref)).doesNotExist();
    }

    @Test
    void hiddenProductsDoNotResolveImageReferences() {
        CatalogMapper mapper = mock(CatalogMapper.class);
        ProductImageStorage storage = mock(ProductImageStorage.class);
        when(mapper.find(7)).thenReturn(null);
        assertThatThrownBy(() -> new CatalogService(mapper, storage).image(7, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PRODUCT_NOT_FOUND"));
        verifyNoInteractions(storage);
    }
}
