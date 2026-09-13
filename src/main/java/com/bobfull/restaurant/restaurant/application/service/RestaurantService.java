package com.bobfull.restaurant.restaurant.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ImageErrorCode;
import com.bobfull.common.exception.RestaurantErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.restaurant.restaurant.infrastructure.cache.CachedRestaurantSearchResult;
import com.bobfull.restaurant.restaurant.infrastructure.cache.RestaurantSearchCacheKey;
import com.bobfull.restaurant.restaurant.infrastructure.cache.RestaurantSearchCacheStore;
import com.bobfull.restaurant.restaurant.presentation.dto.OwnerRestaurantDetailResponse;
import com.bobfull.restaurant.restaurant.presentation.dto.OwnerRestaurantListResponse;
import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantCreateRequest;
import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantDetailResponse;
import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantIdResponse;
import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantSearchRequest;
import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantSearchResponse;
import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantUpdateRequest;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.image.application.service.RestaurantImageService;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * OWNER 식당 등록·조회·수정·삭제와 사용자용 식당 상세 조회를 담당한다.
 * 소유권 대상은 SecurityContext의 인증 사용자 ID로만 결정하며 Request 값을 신뢰하지 않는다.
 */
@Service
public class RestaurantService {

    private static final Logger log = LoggerFactory.getLogger(RestaurantService.class);

    private final RestaurantRepository restaurantRepository;
    private final Clock clock;
    private final RestaurantImageService restaurantImageService;
    private final RestaurantSearchCacheStore restaurantSearchCacheStore;

    public RestaurantService(
            RestaurantRepository restaurantRepository,
            Clock clock,
            RestaurantImageService restaurantImageService,
            RestaurantSearchCacheStore restaurantSearchCacheStore
    ) {
        this.restaurantRepository = restaurantRepository;
        this.clock = clock;
        this.restaurantImageService = restaurantImageService;
        this.restaurantSearchCacheStore = restaurantSearchCacheStore;
    }

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

    /**
     * date/time이 없는 검색만 Redis에 캐시한다(Issue #62). date/time이 있으면 결과가 TimeSlot
     * 변경에도 영향을 받아 무효화 대상이 늘어나므로 이번 Issue의 최소 범위에서는 캐시하지 않고
     * 항상 DB를 조회한다.
     *
     * <p>이 메서드 자체는 {@code @Transactional}을 붙이지 않는다. Cache Hit 경로는 DB를 전혀
     * 만지지 않는데도 바깥 메서드가 {@code @Transactional}이면 매 요청마다 실제로 실행되는 SQL이
     * 없어도 Hikari Connection을 열고 닫아 동시 요청에서 Pool을 불필요하게 점유한다는 것을
     * 실측으로 확인했다(Issue #62 Evidence "Warm Hit 동시 반복" 참고). Cache Miss 경로에서
     * {@code restaurantRepository.search(...)}가 실제로 DB에 접근할 때는
     * {@link com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantSearchRepositoryImpl#search}에 명시된
     * 자체 트랜잭션이 그 경로만 감싼다 — 이 메서드가 트랜잭션 없이도 안전한 것은 그 때문이며,
     * "저장소 프록시가 기본적으로 트랜잭션을 연다"는 가정 때문이 아니다(그 가정은 커스텀
     * repository fragment에는 적용되지 않아 실제로는 틀렸었다, PR #202 리뷰로 확인).</p>
     *
     * <p>Cache Miss 시 {@link RestaurantSearchCacheStore#find}가 반환한 버전 스냅샷을 그대로
     * {@link RestaurantSearchCacheStore#put}에 넘긴다 — DB 조회 도중 다른 트랜잭션이 커밋되어
     * 버전이 올라가도, 이번 결과는 조회 시점의 옛 버전에만 저장돼 stale 값이 "현재" 버전으로
     * 노출되지 않는다(PR #202 재리뷰 반영, {@link RestaurantSearchCacheStore} 클래스 설명 참고).</p>
     */
    public PageResponse<RestaurantSearchResponse> searchRestaurants(
            RestaurantSearchRequest request,
            Pageable pageable
    ) {
        if (!RestaurantSearchCacheKey.isCacheEligible(request)) {
            return searchRestaurantsFromDb(request, pageable);
        }

        RestaurantSearchCacheKey cacheKey = RestaurantSearchCacheKey.of(request, pageable);
        RestaurantSearchCacheStore.Lookup lookup = restaurantSearchCacheStore.find(cacheKey);
        if (lookup.result().isPresent()) {
            return toPageResponse(lookup.result().get());
        }

        Page<Restaurant> restaurants = restaurantRepository.search(request, pageable);
        CachedRestaurantSearchResult result = CachedRestaurantSearchResult.from(restaurants);
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

    @Transactional
    public RestaurantIdResponse delete(Long ownerMemberId, Long restaurantId) {
        Restaurant restaurant = findActiveOrThrow(restaurantId);
        validateOwnership(restaurant, ownerMemberId);

        // 합석 테이블·회차·예약 도메인이 아직 없어 연결 데이터 검사를 하지 않는다.
        // 해당 도메인 구현 시 활성 데이터가 있으면 여기서 RestaurantErrorCode.RESTAURANT_DELETE_NOT_ALLOWED를 던져야 한다(Issue #31 결정 2).
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

    /**
     * DB 트랜잭션 커밋 후에만 검색 캐시 버전을 올린다(PR #202 리뷰 반영). 커밋 전에 올리면
     * 아직 반영되지 않은 변경 사항 중간에 동시 검색 요청이 새 버전으로 캐시 Miss를 일으키고,
     * 그 시점 DB에서는 여전히 이전 값을 읽어 그 값을 새 버전 key에 다시 저장해버릴 수 있다
     * (이후 요청은 TTL 동안 이 stale 값을 "최신"으로 오인해 Hit한다). afterCommit에서 올리면
     * 그 시점 이후에 시작하는 모든 DB 조회가 이미 커밋된 값을 보게 되어 이 경쟁이 사라진다.
     */
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
            log.warn("event=RESTAURANT_IMAGE_DELETE_FAILED imageKey={} reason={}",
                    previousImageKey, exception.getClass().getSimpleName(), exception);
        }
    }
}
