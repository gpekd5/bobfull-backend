package com.bobfull.restaurant.restaurant.infrastructure.cache;

import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import java.util.List;
import org.springframework.data.domain.Page;

// 식당 검색 결과의 페이지 정보와 응답 재구성 데이터를 캐시에 보관한다.
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

    // presigned URL은 캐시 TTL과 별도로 만료되므로 원본 imageKey만 저장하고 응답마다 다시 만든다.
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
