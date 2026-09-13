package com.bobfull.restaurant.restaurant.infrastructure.cache;

import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 식당 검색 결과를 Redis에 캐시하기 위한 형태다(Issue #62).
 * {@code imageKey}는 원본 S3 키만 저장하고 presigned URL은 저장하지 않는다 — presigned URL은
 * {@code S3_IMAGE_GET_URL_EXPIRATION}(기본 5분)이 지나면 무효가 되므로, 캐시 TTL과 무관하게
 * 매 조회(캐시 Hit/Miss 모두)마다 새로 생성해야 한다.
 */
public record CachedRestaurantSearchResult(
        List<Item> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static CachedRestaurantSearchResult from(Page<Restaurant> restaurants) {
        List<Item> items = restaurants.getContent().stream().map(Item::from).toList();
        return new CachedRestaurantSearchResult(
                items,
                restaurants.getNumber(),
                restaurants.getSize(),
                restaurants.getTotalElements(),
                restaurants.getTotalPages()
        );
    }

    public record Item(
            Long restaurantId,
            String name,
            String address,
            String category,
            String keyword,
            Integer depositPerPerson,
            String imageKey
    ) {
        public static Item from(Restaurant restaurant) {
            return new Item(
                    restaurant.getId(),
                    restaurant.getName(),
                    restaurant.getAddress(),
                    restaurant.getCategory(),
                    restaurant.getKeyword(),
                    restaurant.getDepositPerPerson(),
                    restaurant.getImageKey()
            );
        }
    }
}
