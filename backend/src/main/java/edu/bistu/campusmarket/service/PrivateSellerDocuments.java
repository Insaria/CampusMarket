package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.exception.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@Profile("db")
public class PrivateSellerDocuments {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrivateSellerDocuments.class);
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private final Path root;

    public PrivateSellerDocuments(@Value("${SELLER_PRIVATE_DOCUMENTS_DIR:}") String directory) {
        root = directory.isBlank() ? null : Path.of(directory);
    }

    public String store(MultipartFile upload) {
        if (upload.isEmpty() || upload.getSize() > MAX_BYTES) {
            throw invalidImage();
        }
        if (root == null || !root.isAbsolute() || !Files.isDirectory(root)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SELLER_STORAGE_UNAVAILABLE", "资质存储暂不可用");
        }
        Path file = null;
        boolean stored = false;
        try {
            Path privateDirectory = root.toRealPath();
            if (privateDirectory.startsWith(Path.of("").toRealPath())) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SELLER_STORAGE_UNAVAILABLE", "资质存储暂不可用");
            }
            var image = readImage(upload);
            file = Files.createTempFile(privateDirectory, "seller-", ".png");
            if (!ImageIO.write(image, "png", file.toFile()) || Files.size(file) > MAX_BYTES) {
                throw invalidImage();
            }
            stored = true;
            return file.getFileName().toString();
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SELLER_STORAGE_UNAVAILABLE", "资质存储暂不可用");
        } finally {
            if (!stored && file != null) {
                discard(file.getFileName().toString());
            }
        }
    }

    private java.awt.image.BufferedImage readImage(MultipartFile upload) {
        try (var stream = upload.getInputStream(); var input = new MemoryCacheImageInputStream(stream)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!"png".equals(format) && !"jpeg".equals(format)) {
                    throw invalidImage();
                }
                reader.setInput(input, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 12_000_000) {
                    throw invalidImage();
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SELLER_DOCUMENT", "无法处理资质图片");
        }
    }

    public void discard(String reference) {
        if (root == null || !reference.matches("seller-[0-9]+\\.png")) {
            return;
        }
        try {
            Files.deleteIfExists(root.resolve(reference));
        } catch (IOException exception) {
            LOGGER.warn("Could not remove an unreferenced seller document");
        }
    }

    public byte[] read(String reference) {
        if (root == null || reference == null || !reference.matches("seller-[0-9]+\\.png")) {
            throw unavailableDocument();
        }
        try {
            Path directory = root.toRealPath();
            Path file = directory.resolve(reference);
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw unavailableDocument();
            }
            try (var stream = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                byte[] image = stream.readNBytes((int) MAX_BYTES + 1);
                if (image.length == 0 || image.length > MAX_BYTES) {
                    throw unavailableDocument();
                }
                return image;
            }
        } catch (IOException exception) {
            throw unavailableDocument();
        }
    }

    private BusinessException unavailableDocument() {
        return new BusinessException(HttpStatus.NOT_FOUND, "SELLER_DOCUMENT_NOT_FOUND", "资质图片不可用");
    }

    private BusinessException invalidImage() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SELLER_DOCUMENT", "请上传有效的 PNG 或 JPEG 资质图片");
    }
}
