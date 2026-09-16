package com.bobfull.restaurant.restaurant.infrastructure.cache;

import com.bobfull.restaurant.restaurant.presentation.request.RestaurantSearchRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;

// 검색 결과를 결정하는 정규화된 공개 조건만으로 캐시 key를 구성한다.
public record RestaurantSearchCacheKey(String keyword, String category, String sort, int page, int size) {

    // date/time 결과는 TimeSlot 변경에도 영향을 받아 Restaurant 전용 무효화만으로 안전하지 않다.
    public static boolean isCacheEligible(RestaurantSearchRequest request) {
        return request.date() == null && request.time() == null;
    }

    public static RestaurantSearchCacheKey of(RestaurantSearchRequest request, Pageable pageable) {
        String normalizedSort = pageable.getSort().stream()
                .map(order -> order.getProperty() + "," + order.getDirection())
                .collect(Collectors.joining(";"));
        return new RestaurantSearchCacheKey(
                normalize(request.keyword()),
                normalize(request.category()),
                normalizedSort,
                pageable.getPageNumber(),
                pageable.getPageSize()
        );
    }

    public String digest() {
        String raw = keyword + "|" + category + "|" + sort + "|" + page + "|" + size;
        return sha256Hex(raw);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
