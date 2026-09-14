package com.bobfull.admin.application.service;

import com.bobfull.admin.presentation.dto.AdminRestaurantDetailResponse;
import com.bobfull.admin.presentation.dto.AdminRestaurantListItemResponse;
import com.bobfull.admin.application.model.AdminRestaurantResult;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.restaurant.domain.exception.RestaurantErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN의 식당 목록·상세 조회를 담당한다(Issue #49).
 * Fragment 인터페이스(AdminRestaurantRepository) 대신 합성된 {@link RestaurantRepository}를 주입한다
 * (Fragment 인터페이스를 직접 주입하면 Spring이 구현체를 별도 Bean으로도 등록해 중복 Bean 오류가 난다).
 */
@Service
public class AdminRestaurantQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final RestaurantRepository restaurantRepository;

    public AdminRestaurantQueryService(RestaurantRepository restaurantRepository) {
        this.restaurantRepository = restaurantRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminRestaurantListItemResponse> getRestaurants(
            String keyword, String restaurantStatus, Boolean deleted, Pageable pageable
    ) {
        RestaurantStatus status = parseStatus(restaurantStatus);
        Page<AdminRestaurantResult> results =
                restaurantRepository.searchRestaurants(keyword, status, deleted, pageable);
        return PageResponse.from(results.map(result ->
                AdminRestaurantListItemResponse.of(result, toSeoulOffset(result.createdAt()))));
    }

    @Transactional(readOnly = true)
    public AdminRestaurantDetailResponse getRestaurant(Long restaurantId) {
        AdminRestaurantResult result = restaurantRepository.findRestaurantDetail(restaurantId)
                .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));
        return AdminRestaurantDetailResponse.of(
                result, toSeoulOffset(result.createdAt()), toSeoulOffset(result.deletedAt()));
    }

    private RestaurantStatus parseStatus(String restaurantStatus) {
        if (restaurantStatus == null || restaurantStatus.isBlank()) {
            return null;
        }
        try {
            return RestaurantStatus.valueOf(restaurantStatus);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private OffsetDateTime toSeoulOffset(Instant instant) {
        return instant == null ? null : instant.atZone(SEOUL_ZONE).toOffsetDateTime();
    }
}
