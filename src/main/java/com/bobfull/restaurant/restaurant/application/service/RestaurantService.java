package com.bobfull.restaurant.restaurant.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.image.domain.exception.ImageErrorCode;
import com.bobfull.restaurant.restaurant.domain.exception.RestaurantErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.restaurant.restaurant.infrastructure.cache.CachedRestaurantSearchResult;
import com.bobfull.restaurant.restaurant.infrastructure.cache.RestaurantSearchCacheKey;
import com.bobfull.restaurant.restaurant.infrastructure.cache.RestaurantSearchCacheStore;
import com.bobfull.restaurant.restaurant.presentation.response.OwnerRestaurantDetailResponse;
import com.bobfull.restaurant.restaurant.presentation.response.OwnerRestaurantListResponse;
import com.bobfull.restaurant.restaurant.presentation.request.RestaurantCreateRequest;
import com.bobfull.restaurant.restaurant.presentation.response.RestaurantDetailResponse;
import com.bobfull.restaurant.restaurant.presentation.response.RestaurantIdResponse;
import com.bobfull.restaurant.restaurant.presentation.request.RestaurantSearchRequest;
import com.bobfull.restaurant.restaurant.presentation.response.RestaurantSearchResponse;
import com.bobfull.restaurant.restaurant.presentation.request.RestaurantUpdateRequest;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.image.application.service.RestaurantImageService;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

// 식당 등록·조회·수정·삭제와 사용자용 검색·상세 조회를 담당한다.
// 소유권 대상은 클라이언트 입력이 아닌 인증 사용자 ID로 결정한다.
@Service
@RequiredArgsConstructor
@Slf4j
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final Clock clock;
    private final RestaurantImageService restaurantImageService;
    private final RestaurantSearchCacheStore restaurantSearchCacheStore;

    // 최종 이미지 객체를 검증해 식당을 등록하고 커밋 후 검색 캐시를 무효화한다.
    @Transactional
    public RestaurantIdResponse register(Long ownerMemberId, RestaurantCreateRequest request) {
        String imageKey = resolveNewImageKey(ownerMemberId, request.imageKey());
        Restaurant restaurant = Restaurant.create(
                ownerMemberId,
                request.name(),
                request.address(),
                request.category(),
                request.description(),
                request.keyword(),
                request.depositPerPerson(),
                imageKey
        );

        Restaurant savedRestaurant = restaurantRepository.save(restaurant);
        bumpSearchCacheVersionAfterCommit();
        return RestaurantIdResponse.from(savedRestaurant);
    }

    @Transactional(readOnly = true)
    public PageResponse<OwnerRestaurantListResponse> getMyRestaurants(Long ownerMemberId, Pageable pageable) {
        Page<Restaurant> restaurants =
                restaurantRepository.findAllByOwnerMemberIdAndDeletedAtIsNull(ownerMemberId, pageable);
        return PageResponse.from(restaurants.map(restaurant ->
                OwnerRestaurantListResponse.from(restaurant, createImageUrl(restaurant))));
    }

    // 캐시 대상 검색은 Redis를 우선 조회하고 Miss 결과를 같은 버전 스냅샷에 저장한다.
    public PageResponse<RestaurantSearchResponse> searchRestaurants(
            RestaurantSearchRequest request,
            Pageable pageable
    ) {
        // date/time 검색은 TimeSlot 변경에도 영향을 받으므로 Restaurant 전용 캐시에서 제외한다.
        if (!RestaurantSearchCacheKey.isCacheEligible(request)) {
            return searchRestaurantsFromDb(request, pageable);
        }

        RestaurantSearchCacheKey cacheKey = RestaurantSearchCacheKey.of(request, pageable);
        RestaurantSearchCacheStore.Lookup lookup = restaurantSearchCacheStore.find(cacheKey);
        if (lookup.result().isPresent()) {
            return toPageResponse(lookup.result().get());
        }

        // Cache Hit에는 트랜잭션을 열지 않고, Miss의 DB 조회만 fragment의 읽기 전용 트랜잭션에 맡긴다.
        Page<Restaurant> restaurants = restaurantRepository.search(request, pageable);
        CachedRestaurantSearchResult result = CachedRestaurantSearchResult.from(restaurants);
        // 조회 중 버전이 바뀌어도 stale 결과가 새 버전 key에 저장되지 않도록 기존 스냅샷을 사용한다.
        restaurantSearchCacheStore.put(lookup.version(), cacheKey, result);
        return toPageResponse(result);
    }

    private PageResponse<RestaurantSearchResponse> searchRestaurantsFromDb(
            RestaurantSearchRequest request,
            Pageable pageable
    ) {
        Page<Restaurant> restaurants = restaurantRepository.search(request, pageable);
        return PageResponse.from(restaurants.map(restaurant ->
                RestaurantSearchResponse.from(restaurant, createImageUrl(restaurant))));
    }

    private PageResponse<RestaurantSearchResponse> toPageResponse(CachedRestaurantSearchResult result) {
        List<RestaurantSearchResponse> content = result.items().stream()
                .map(item -> new RestaurantSearchResponse(
                        item.restaurantId(),
                        item.name(),
                        item.address(),
                        item.category(),
                        item.keyword(),
                        item.depositPerPerson(),
                        restaurantImageService.createGetUrl(item.imageKey())
                ))
                .toList();
        return new PageResponse<>(content, result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @Transactional(readOnly = true)
    public OwnerRestaurantDetailResponse getMyRestaurant(Long ownerMemberId, Long restaurantId) {
        Restaurant restaurant = findActiveOrThrow(restaurantId);
        validateOwnership(restaurant, ownerMemberId);
        return OwnerRestaurantDetailResponse.from(restaurant, createImageUrl(restaurant));
    }

    // 소유권과 이미지 사용 제약을 검증하고 커밋 후 캐시 무효화와 이전 이미지 정리를 수행한다.
    @Transactional
    public RestaurantIdResponse update(Long ownerMemberId, Long restaurantId, RestaurantUpdateRequest request) {
        Restaurant restaurant = findActiveOrThrow(restaurantId);
        validateOwnership(restaurant, ownerMemberId);

        String previousImageKey = restaurant.getImageKey();
        String newImageKey = resolveUpdatedImageKey(
                ownerMemberId,
                restaurant.getId(),
                previousImageKey,
                request.imageKey()
        );
        restaurant.update(request.name(), request.description(), request.keyword(), request.depositPerPerson());
        if (request.imageKey() != null) {
            restaurant.updateImageKey(newImageKey);
            deletePreviousImageAfterCommit(previousImageKey, newImageKey);
        }
        bumpSearchCacheVersionAfterCommit();
        return RestaurantIdResponse.from(restaurant);
    }

    // 활성 식당의 소유권을 확인해 soft delete하고 커밋 후 검색 캐시를 무효화한다.
    @Transactional
    public RestaurantIdResponse delete(Long ownerMemberId, Long restaurantId) {
        Restaurant restaurant = findActiveOrThrow(restaurantId);
        validateOwnership(restaurant, ownerMemberId);

        restaurant.softDelete(clock.instant());
        bumpSearchCacheVersionAfterCommit();
        return RestaurantIdResponse.from(restaurant);
    }

    @Transactional(readOnly = true)
    public RestaurantDetailResponse getRestaurantDetail(Long restaurantId) {
        Restaurant restaurant = findActiveOrThrow(restaurantId);
        return RestaurantDetailResponse.from(restaurant, createImageUrl(restaurant));
    }

    private Restaurant findActiveOrThrow(Long restaurantId) {
        return restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
                .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));
    }

    private void validateOwnership(Restaurant restaurant, Long ownerMemberId) {
        if (!restaurant.isOwnedBy(ownerMemberId)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
    }

    private String resolveNewImageKey(Long ownerMemberId, String imageKey) {
        if (imageKey == null) {
            return null;
        }
        restaurantImageService.validateFinalImage(ownerMemberId, imageKey);
        validateUnusedImageKey(imageKey);
        return imageKey;
    }

    private String resolveUpdatedImageKey(
            Long ownerMemberId,
            Long restaurantId,
            String previousImageKey,
            String requestedImageKey
    ) {
        if (requestedImageKey == null) {
            return previousImageKey;
        }
        restaurantImageService.validateFinalImage(ownerMemberId, requestedImageKey);
        validateUnusedImageKeyForUpdate(requestedImageKey, restaurantId);
        return requestedImageKey;
    }

    private void validateUnusedImageKey(String imageKey) {
        if (restaurantRepository.existsByImageKeyAndDeletedAtIsNull(imageKey)) {
            throw new CustomException(ImageErrorCode.RESTAURANT_IMAGE_ALREADY_USED);
        }
    }

    private void validateUnusedImageKeyForUpdate(String imageKey, Long restaurantId) {
        if (restaurantRepository.existsByImageKeyAndIdNotAndDeletedAtIsNull(imageKey, restaurantId)) {
            throw new CustomException(ImageErrorCode.RESTAURANT_IMAGE_ALREADY_USED);
        }
    }

    private String createImageUrl(Restaurant restaurant) {
        return restaurantImageService.createGetUrl(restaurant.getImageKey());
    }

    // 커밋 전에 버전을 올리면 미커밋 값을 조회한 결과가 새 key에 저장될 수 있다.
    // 캐시 버전은 afterCommit에서만 올려 이후 조회가 커밋된 데이터를 보도록 한다.
    private void bumpSearchCacheVersionAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    restaurantSearchCacheStore.bumpVersion();
                }
            });
            return;
        }
        restaurantSearchCacheStore.bumpVersion();
    }

    // DB 롤백 뒤 이미지가 먼저 사라지는 일을 막기 위해 이전 객체는 커밋 후 삭제한다.
    private void deletePreviousImageAfterCommit(String previousImageKey, String newImageKey) {
        if (!StringUtils.hasText(previousImageKey) || previousImageKey.equals(newImageKey)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deletePreviousImage(previousImageKey);
                }
            });
            return;
        }
        deletePreviousImage(previousImageKey);
    }

    private void deletePreviousImage(String previousImageKey) {
        if (restaurantRepository.existsByImageKeyAndDeletedAtIsNull(previousImageKey)) {
            log.info("다른 식당이 참조 중인 기존 이미지는 삭제하지 않습니다. imageKey={}", previousImageKey);
            return;
        }
        try {
            restaurantImageService.delete(previousImageKey);
        } catch (RuntimeException exception) {
            // 이미 커밋된 식당 수정은 유지하고 실패한 저장소 정리만 기록한다.
            log.warn("event=RESTAURANT_IMAGE_DELETE_FAILED imageKey={} reason={}",
                    previousImageKey, exception.getClass().getSimpleName(), exception);
        }
    }
}
