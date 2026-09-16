package com.bobfull.restaurant.image.domain.policy;

import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.image.domain.exception.ImageErrorCode;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// 식당 이미지의 허용 형식·크기와 소유자별 최종 key 규칙을 검증한다.
@Component
public class RestaurantImagePolicy {

    public static final long MAX_FILE_SIZE = 5L * 1024L * 1024L;

    private static final Map<String, String> CONTENT_TYPES_BY_EXTENSION = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png"
    );

    public ImageUploadSpec validateUploadRequest(String extension, String contentType, long fileSize) {
        String normalizedExtension = normalizeExtension(extension);
        String normalizedContentType = normalizeContentType(contentType);
        validateFileSize(fileSize);
        validateExtensionAndContentType(normalizedExtension, normalizedContentType);
        return new ImageUploadSpec(normalizedExtension, normalizedContentType, fileSize);
    }

    public void validateFinalImageKey(Long ownerMemberId, String imageKey) {
        if (!StringUtils.hasText(imageKey)) {
            throw new CustomException(ImageErrorCode.INVALID_RESTAURANT_IMAGE_KEY);
        }
        String[] parts = imageKey.split("/");
        if (parts.length != 3 || !"restaurants".equals(parts[0])) {
            throw new CustomException(ImageErrorCode.INVALID_RESTAURANT_IMAGE_KEY);
        }
        if (!parts[1].equals(String.valueOf(ownerMemberId))) {
            throw new CustomException(ImageErrorCode.INVALID_RESTAURANT_IMAGE_KEY);
        }
        String fileName = parts[2];
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == fileName.length() - 1) {
            throw new CustomException(ImageErrorCode.INVALID_RESTAURANT_IMAGE_KEY);
        }
        validateUuid(fileName.substring(0, dotIndex));
        String extension = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES_BY_EXTENSION.containsKey(extension)) {
            throw new CustomException(ImageErrorCode.INVALID_RESTAURANT_IMAGE_KEY);
        }
    }

    private String normalizeExtension(String extension) {
        if (!StringUtils.hasText(extension)) {
            throw new CustomException(ImageErrorCode.INVALID_IMAGE_EXTENSION);
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        if (!CONTENT_TYPES_BY_EXTENSION.containsKey(normalized)) {
            throw new CustomException(ImageErrorCode.INVALID_IMAGE_EXTENSION);
        }
        return normalized;
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            throw new CustomException(ImageErrorCode.UNSUPPORTED_IMAGE_CONTENT_TYPE);
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES_BY_EXTENSION.containsValue(normalized)) {
            throw new CustomException(ImageErrorCode.UNSUPPORTED_IMAGE_CONTENT_TYPE);
        }
        return normalized;
    }

    private void validateFileSize(long fileSize) {
        if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
            throw new CustomException(ImageErrorCode.IMAGE_FILE_SIZE_EXCEEDED);
        }
    }

    private void validateExtensionAndContentType(String extension, String contentType) {
        String expectedContentType = CONTENT_TYPES_BY_EXTENSION.get(extension);
        if (!expectedContentType.equals(contentType)) {
            throw new CustomException(ImageErrorCode.IMAGE_EXTENSION_CONTENT_TYPE_MISMATCH);
        }
    }

    private void validateUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(ImageErrorCode.INVALID_RESTAURANT_IMAGE_KEY);
        }
    }

    public record ImageUploadSpec(String extension, String contentType, long fileSize) {
    }
}
