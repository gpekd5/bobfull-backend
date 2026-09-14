package com.bobfull.restaurant.restaurant.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.image.domain.exception.ImageErrorCode;
import com.bobfull.restaurant.restaurant.domain.exception.RestaurantErrorCode;
import com.bobfull.common.response.PageResponse;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 식당 등록·조회·수정·삭제의 소유권 검증과 상태 변경을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC);
    private static final String IMAGE_KEY = "restaurants/1/11111111-1111-1111-1111-111111111111.png";
    private static final String SEARCH_IMAGE_KEY = "restaurants/1/22222222-2222-2222-2222-222222222222.png";
    private static final String DETAIL_IMAGE_KEY = "restaurants/1/33333333-3333-3333-3333-333333333333.png";
    private static final String OLD_IMAGE_KEY = "restaurants/1/44444444-4444-4444-4444-444444444444.png";
    private static final String NEW_IMAGE_KEY = "restaurants/1/55555555-5555-5555-5555-555555555555.png";

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private RestaurantImageService restaurantImageService;

    @Mock
    private RestaurantSearchCacheStore restaurantSearchCacheStore;

    @InjectMocks
    private RestaurantService restaurantService;

    private Restaurant restaurantOwnedBy(Long ownerMemberId) {
        return Restaurant.create(ownerMemberId, "밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000);
    }

    private Restaurant restaurantOwnedByWithImage(Long ownerMemberId, String imageKey) {
        return Restaurant.create(
                ownerMemberId, "밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000, imageKey);
    }

    private Restaurant restaurantOwnedByWithIdAndImage(Long restaurantId, Long ownerMemberId, String imageKey) {
        Restaurant restaurant = restaurantOwnedByWithImage(ownerMemberId, imageKey);
        ReflectionTestUtils.setField(restaurant, "id", restaurantId);
        return restaurant;
    }

    @Test
    void 식당을_등록하면_등록한_회원을_소유자로_저장한다() {
        // given
        RestaurantCreateRequest request =
                new RestaurantCreateRequest("밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000, null);
        given(restaurantRepository.save(any(Restaurant.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        RestaurantIdResponse response = restaurantService.register(1L, request);

        // then
        assertThat(response).isNotNull();
        verify(restaurantSearchCacheStore).bumpVersion();
    }

    @Test
    void 트랜잭션_안에서_등록하면_검색_캐시_버전_증가는_커밋_후에만_실행된다() {
        // given
        RestaurantCreateRequest request =
                new RestaurantCreateRequest("밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000, null);
        given(restaurantRepository.save(any(Restaurant.class))).willAnswer(invocation -> invocation.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            // when
            restaurantService.register(1L, request);

            // then: 트랜잭션이 아직 커밋되지 않은 시점에는 호출되지 않는다.
            verify(restaurantSearchCacheStore, never()).bumpVersion();

            // when: 커밋 콜백을 실행하면
            List.copyOf(TransactionSynchronizationManager.getSynchronizations())
                    .forEach(TransactionSynchronization::afterCommit);

            // then: 그제서야 호출된다.
            verify(restaurantSearchCacheStore).bumpVersion();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 식당을_등록할_때_이미지_key가_있으면_검증_완료_객체인지_확인하고_저장한다() {
        // given
        String imageKey = IMAGE_KEY;
        RestaurantCreateRequest request =
                new RestaurantCreateRequest("밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000, imageKey);
        given(restaurantRepository.save(any(Restaurant.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        RestaurantIdResponse response = restaurantService.register(1L, request);

        // then
        assertThat(response).isNotNull();
        verify(restaurantImageService).validateFinalImage(1L, imageKey);
    }

    @Test
    void 식당을_등록할_때_이미_사용중인_이미지_key이면_예외가_발생한다() {
        // given
        RestaurantCreateRequest request =
                new RestaurantCreateRequest("밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000, IMAGE_KEY);
        given(restaurantRepository.existsByImageKeyAndDeletedAtIsNull(IMAGE_KEY)).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> restaurantService.register(1L, request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ImageErrorCode.RESTAURANT_IMAGE_ALREADY_USED);
        verify(restaurantRepository, never()).save(any(Restaurant.class));
    }

    @Test
    void 내_식당_목록을_조회하면_본인_소유_식당만_페이징으로_반환한다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithImage(1L, IMAGE_KEY);
        Pageable pageable = PageRequest.of(0, 20);
        given(restaurantRepository.findAllByOwnerMemberIdAndDeletedAtIsNull(1L, pageable))
                .willReturn(new PageImpl<>(List.of(restaurant), pageable, 1));
        given(restaurantImageService.createGetUrl(IMAGE_KEY)).willReturn("https://image.example");

        // when
        PageResponse<?> response = restaurantService.getMyRestaurants(1L, pageable);

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat((OwnerRestaurantListResponse) response.content().get(0))
                .extracting(OwnerRestaurantListResponse::imageUrl)
                .isEqualTo("https://image.example");
    }

    @Test
    void 사용자용_식당_검색은_공개_목록_응답으로_변환한다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithImage(1L, SEARCH_IMAGE_KEY);
        RestaurantSearchRequest request = new RestaurantSearchRequest("흑돼지", "한식", null, null);
        Pageable pageable = PageRequest.of(0, 20);
        given(restaurantSearchCacheStore.find(any()))
                .willReturn(new RestaurantSearchCacheStore.Lookup(0L, Optional.empty()));
        given(restaurantRepository.search(eq(request), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(restaurant), pageable, 1));
        given(restaurantImageService.createGetUrl(SEARCH_IMAGE_KEY)).willReturn("https://search-image.example");

        // when
        PageResponse<RestaurantSearchResponse> response = restaurantService.searchRestaurants(request, pageable);

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).keyword()).isEqualTo("흑돼지,혼밥");
        assertThat(response.content().get(0).imageUrl()).isEqualTo("https://search-image.example");
        assertThat(response.totalElements()).isEqualTo(1);
        verify(restaurantSearchCacheStore).put(eq(0L), any(), any());
    }

    @Test
    void date나_time이_있는_검색은_캐시를_조회하거나_저장하지_않는다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithImage(1L, SEARCH_IMAGE_KEY);
        RestaurantSearchRequest request =
                new RestaurantSearchRequest(null, null, java.time.LocalDate.of(2026, 8, 12), null);
        Pageable pageable = PageRequest.of(0, 20);
        given(restaurantRepository.search(eq(request), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(restaurant), pageable, 1));
        given(restaurantImageService.createGetUrl(SEARCH_IMAGE_KEY)).willReturn("https://search-image.example");

        // when
        restaurantService.searchRestaurants(request, pageable);

        // then
        verify(restaurantSearchCacheStore, never()).find(any());
        verify(restaurantSearchCacheStore, never()).put(anyLong(), any(), any());
    }

    @Test
    void 캐시에_저장된_결과가_있으면_DB를_조회하지_않고_이미지_URL만_새로_생성한다() {
        // given
        RestaurantSearchRequest request = new RestaurantSearchRequest("흑돼지", "한식", null, null);
        Pageable pageable = PageRequest.of(0, 20);
        com.bobfull.restaurant.restaurant.infrastructure.cache.CachedRestaurantSearchResult.Item cachedItem =
                new com.bobfull.restaurant.restaurant.infrastructure.cache.CachedRestaurantSearchResult.Item(
                        10L, "밥풀식당", "제주시 애월읍 1", "한식", "흑돼지,혼밥", 10000, SEARCH_IMAGE_KEY);
        com.bobfull.restaurant.restaurant.infrastructure.cache.CachedRestaurantSearchResult cached =
                new com.bobfull.restaurant.restaurant.infrastructure.cache.CachedRestaurantSearchResult(List.of(cachedItem), 0, 20, 1, 1);
        given(restaurantSearchCacheStore.find(any()))
                .willReturn(new RestaurantSearchCacheStore.Lookup(0L, Optional.of(cached)));
        given(restaurantImageService.createGetUrl(SEARCH_IMAGE_KEY)).willReturn("https://fresh-image.example");

        // when
        PageResponse<RestaurantSearchResponse> response = restaurantService.searchRestaurants(request, pageable);

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).imageUrl()).isEqualTo("https://fresh-image.example");
        verify(restaurantRepository, never()).search(any(), any());
        verify(restaurantSearchCacheStore, never()).put(anyLong(), any(), any());
    }

    @Test
    void 존재하지_않는_식당을_조회하면_예외가_발생한다() {
        // given
        given(restaurantRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> restaurantService.getMyRestaurant(1L, 999L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND);
    }

    @Test
    void 본인_식당을_조회하면_상세_정보를_반환한다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithImage(1L, DETAIL_IMAGE_KEY);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(restaurantImageService.createGetUrl(DETAIL_IMAGE_KEY)).willReturn("https://detail-image.example");

        // when
        OwnerRestaurantDetailResponse response = restaurantService.getMyRestaurant(1L, 10L);

        // then
        assertThat(response.name()).isEqualTo("밥풀식당");
        assertThat(response.imageUrl()).isEqualTo("https://detail-image.example");
    }

    @Test
    void 타인_식당을_조회하면_403_예외가_발생한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> restaurantService.getMyRestaurant(2L, 10L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void 본인_식당을_수정하면_변경한_내용이_반영된다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        RestaurantUpdateRequest request = new RestaurantUpdateRequest("새이름", "새설명", "한식,혼밥", 12000, null);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        restaurantService.update(1L, 10L, request);

        // then
        assertThat(restaurant.getName()).isEqualTo("새이름");
        assertThat(restaurant.getDepositPerPerson()).isEqualTo(12000);
        verify(restaurantSearchCacheStore).bumpVersion();
    }

    @Test
    void 트랜잭션_안에서_수정하면_검색_캐시_버전_증가는_커밋_후에만_실행된다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        RestaurantUpdateRequest request = new RestaurantUpdateRequest("새이름", "새설명", "한식,혼밥", 12000, null);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        TransactionSynchronizationManager.initSynchronization();
        try {
            // when
            restaurantService.update(1L, 10L, request);

            // then: 아직 DB 트랜잭션이 커밋되지 않은 시점에는 Redis 버전을 올리지 않는다 —
            // 이 시점에 동시 검색이 새 버전으로 캐시 Miss를 내면 아직 반영 안 된 옛 값을
            // 다시 캐시해버리는 경쟁(PR #202 리뷰)이 생기므로, 반드시 커밋 후여야 한다.
            verify(restaurantSearchCacheStore, never()).bumpVersion();

            // when
            List.copyOf(TransactionSynchronizationManager.getSynchronizations())
                    .forEach(TransactionSynchronization::afterCommit);

            // then
            verify(restaurantSearchCacheStore).bumpVersion();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 본인_식당을_수정할_때_이미지_key가_있으면_검증하고_교체한다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithIdAndImage(10L, 1L, OLD_IMAGE_KEY);
        RestaurantUpdateRequest request = new RestaurantUpdateRequest("새이름", "새설명", "한식,혼밥", 12000, NEW_IMAGE_KEY);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        restaurantService.update(1L, 10L, request);

        // then
        assertThat(restaurant.getImageKey()).isEqualTo(NEW_IMAGE_KEY);
        verify(restaurantImageService).validateFinalImage(1L, NEW_IMAGE_KEY);
        verify(restaurantImageService).delete(OLD_IMAGE_KEY);
    }

    @Test
    void 본인_식당을_수정할_때_이미_사용중인_이미지_key이면_예외가_발생한다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithIdAndImage(10L, 1L, OLD_IMAGE_KEY);
        RestaurantUpdateRequest request = new RestaurantUpdateRequest("새이름", "새설명", "한식,혼밥", 12000, NEW_IMAGE_KEY);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(restaurantRepository.existsByImageKeyAndIdNotAndDeletedAtIsNull(NEW_IMAGE_KEY, 10L)).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> restaurantService.update(1L, 10L, request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ImageErrorCode.RESTAURANT_IMAGE_ALREADY_USED);
        assertThat(restaurant.getImageKey()).isEqualTo(OLD_IMAGE_KEY);
        verify(restaurantImageService, never()).delete(any());
    }

    @Test
    void 기존_이미지를_다른_식당이_참조중이면_수정해도_s3_객체를_삭제하지_않는다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithIdAndImage(10L, 1L, OLD_IMAGE_KEY);
        RestaurantUpdateRequest request = new RestaurantUpdateRequest("새이름", "새설명", "한식,혼밥", 12000, NEW_IMAGE_KEY);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(restaurantRepository.existsByImageKeyAndDeletedAtIsNull(OLD_IMAGE_KEY)).willReturn(true);

        // when
        restaurantService.update(1L, 10L, request);

        // then
        assertThat(restaurant.getImageKey()).isEqualTo(NEW_IMAGE_KEY);
        verify(restaurantImageService, never()).delete(OLD_IMAGE_KEY);
    }

    @Test
    void 기존_이미지_삭제는_트랜잭션_커밋_이후에_실행된다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithIdAndImage(10L, 1L, OLD_IMAGE_KEY);
        RestaurantUpdateRequest request = new RestaurantUpdateRequest("새이름", "새설명", "한식,혼밥", 12000, NEW_IMAGE_KEY);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        TransactionSynchronizationManager.initSynchronization();
        try {
            restaurantService.update(1L, 10L, request);
            verify(restaurantImageService, never()).delete(OLD_IMAGE_KEY);

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // then
        verify(restaurantImageService).delete(OLD_IMAGE_KEY);
    }

    @Test
    void 기존_이미지_삭제가_실패해도_식당_수정은_성공한다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithIdAndImage(10L, 1L, OLD_IMAGE_KEY);
        RestaurantUpdateRequest request = new RestaurantUpdateRequest("새이름", "새설명", "한식,혼밥", 12000, NEW_IMAGE_KEY);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        willThrow(new RuntimeException("delete failed")).given(restaurantImageService).delete(OLD_IMAGE_KEY);
        Logger logger = (Logger) LoggerFactory.getLogger(RestaurantService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // when
        RestaurantIdResponse response;
        try {
            response = restaurantService.update(1L, 10L, request);
        } finally {
            logger.detachAppender(appender);
        }

        // then
        assertThat(response).isNotNull();
        assertThat(restaurant.getImageKey()).isEqualTo(NEW_IMAGE_KEY);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=RESTAURANT_IMAGE_DELETE_FAILED");
            assertThat(event.getFormattedMessage()).contains("imageKey=" + OLD_IMAGE_KEY);
            assertThat(event.getFormattedMessage()).contains("reason=RuntimeException");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(RuntimeException.class.getName());
        });
    }

    @Test
    void 본인_식당을_수정할_때_이미지_key가_없으면_기존_이미지를_유지한다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithImage(1L, OLD_IMAGE_KEY);
        RestaurantUpdateRequest request = new RestaurantUpdateRequest("새이름", "새설명", "한식,혼밥", 12000, null);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        restaurantService.update(1L, 10L, request);

        // then
        assertThat(restaurant.getImageKey()).isEqualTo(OLD_IMAGE_KEY);
        verify(restaurantImageService, never()).validateFinalImage(eq(1L), any());
        verify(restaurantImageService, never()).delete(any());
    }

    @Test
    void 타인_식당을_수정하면_403_예외가_발생하고_변경되지_않는다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        RestaurantUpdateRequest request = new RestaurantUpdateRequest("새이름", "새설명", "한식,혼밥", 12000, null);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> restaurantService.update(2L, 10L, request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
        assertThat(restaurant.getName()).isEqualTo("밥풀식당");
    }

    @Test
    void 본인_식당을_삭제하면_소프트_딜리트된다() {
        // given
        RestaurantService clockedService = new RestaurantService(
                restaurantRepository,
                FIXED_CLOCK,
                restaurantImageService,
                restaurantSearchCacheStore
        );
        Restaurant restaurant = restaurantOwnedByWithImage(1L, DETAIL_IMAGE_KEY);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        clockedService.delete(1L, 10L);

        // then
        assertThat(restaurant.getDeletedAt()).isEqualTo(FIXED_CLOCK.instant());
        verify(restaurantSearchCacheStore).bumpVersion();
    }

    @Test
    void 트랜잭션_안에서_삭제하면_검색_캐시_버전_증가는_커밋_후에만_실행된다() {
        // given
        RestaurantService clockedService = new RestaurantService(
                restaurantRepository,
                FIXED_CLOCK,
                restaurantImageService,
                restaurantSearchCacheStore
        );
        Restaurant restaurant = restaurantOwnedByWithImage(1L, DETAIL_IMAGE_KEY);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        TransactionSynchronizationManager.initSynchronization();
        try {
            // when
            clockedService.delete(1L, 10L);

            // then
            verify(restaurantSearchCacheStore, never()).bumpVersion();

            // when
            List.copyOf(TransactionSynchronizationManager.getSynchronizations())
                    .forEach(TransactionSynchronization::afterCommit);

            // then
            verify(restaurantSearchCacheStore).bumpVersion();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 타인_식당을_삭제하면_403_예외가_발생하고_삭제되지_않는다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> restaurantService.delete(2L, 10L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
        assertThat(restaurant.getDeletedAt()).isNull();
    }

    @Test
    void 소프트_삭제된_식당은_findByIdAndDeletedAtIsNull_조회에서_제외돼_404를_반환한다() {
        // given
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> restaurantService.getRestaurantDetail(10L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND);
    }

    @Test
    void 사용자용_상세_조회는_존재하는_식당의_공개_정보를_반환한다() {
        // given
        Restaurant restaurant = restaurantOwnedByWithImage(1L, DETAIL_IMAGE_KEY);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(restaurantImageService.createGetUrl(DETAIL_IMAGE_KEY)).willReturn("https://detail-image.example");

        // when
        RestaurantDetailResponse response = restaurantService.getRestaurantDetail(10L);

        // then
        assertThat(response.name()).isEqualTo("밥풀식당");
        assertThat(response.depositPerPerson()).isEqualTo(10000);
        assertThat(response.imageUrl()).isEqualTo("https://detail-image.example");
    }
}
