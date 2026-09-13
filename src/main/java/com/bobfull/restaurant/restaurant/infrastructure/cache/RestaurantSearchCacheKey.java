package com.bobfull.restaurant.restaurant.infrastructure.cache;

import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantSearchRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;

/**
 * 식당 검색 결과 캐시 Key를 실제 결과를 결정하는 입력(keyword/category/sort/page/size)만으로
 * 정규화해 만든다(Issue #62 Cache Key 계약). JWT·회원 개인정보·requestId는 포함하지 않는다.
 * date/time은 캐시 대상에서 제외한다({@link #isCacheEligible}) — TimeSlot 변경까지 무효화
 * 대상으로 추적해야 해서 이번 Issue의 최소 범위를 넘어선다(Evidence "제외 범위" 참고).
 */
public record RestaurantSearchCacheKey(String keyword, String category, String sort, int page, int size) {

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
