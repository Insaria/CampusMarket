package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.exception.BusinessException;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@Profile("db")
public class ProductImageStorage {
    private final String productDirectory;
    private final String privateDirectory;
    private final PrivateSellerDocuments storage;

    public ProductImageStorage(@Value("${PRODUCT_IMAGES_DIR:}") String productDirectory,
            @Value("${SELLER_PRIVATE_DOCUMENTS_DIR:}") String privateDirectory) {
        this.productDirectory = productDirectory;
        this.privateDirectory = privateDirectory;
        this.storage = new PrivateSellerDocuments(productDirectory);
    }

    public String store(MultipartFile file) {
        requireSeparateDirectories();
        return storage.store(file);
    }

    public byte[] read(String reference) {
        requireSeparateDirectories();
        return storage.read(reference);
    }

    public void discard(String reference) {
        storage.discard(reference);
    }

    private void requireSeparateDirectories() {
        if (!productDirectory.isBlank() && !privateDirectory.isBlank()) {
            try {
                Path products = Path.of(productDirectory).toRealPath();
                Path credentials = Path.of(privateDirectory).toRealPath();
                if (!products.startsWith(credentials) && !credentials.startsWith(products)) {
                    return;
                }
            } catch (IOException ignored) {
                throw unavailable();
            }
        } else if (!productDirectory.isBlank()) {
            return;
        }
        throw unavailable();
    }

    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT_IMAGE_STORAGE_UNAVAILABLE", "请配置独立的商品图片目录");
    }
}
