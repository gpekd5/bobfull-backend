package com.bobfull.restaurant.sharedtable.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.restaurant.domain.exception.RestaurantErrorCode;
import com.bobfull.restaurant.sharedtable.domain.exception.SharedTableErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableIdResponse;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableBulkRequest;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableBulkResponse;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableRequest;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableResponse;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTableStatus;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SharedTableServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private SharedTableRepository sharedTableRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private SharedTableUsageValidator sharedTableUsageValidator;

    private SharedTableService sharedTableService() {
        return new SharedTableService(sharedTableRepository, restaurantRepository, sharedTableUsageValidator, FIXED_CLOCK);
    }

    private Restaurant restaurantOwnedBy(Long ownerMemberId) {
        Restaurant restaurant = Restaurant.create(
                ownerMemberId, "밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000);
        ReflectionTestUtils.setField(restaurant, "id", 10L);
        return restaurant;
    }

    private SharedTable sharedTable(Long tableId, Long restaurantId, Integer capacity) {
        SharedTable sharedTable = SharedTable.create(restaurantId, 1, capacity);
        ReflectionTestUtils.setField(sharedTable, "id", tableId);
        return sharedTable;
    }

    @Test
    void 합석_테이블을_등록하면_ACTIVE_상태와_식당_ID를_저장한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        ArgumentCaptor<SharedTable> captor = ArgumentCaptor.forClass(SharedTable.class);
        given(restaurantRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).willReturn(Optional.of(restaurant));
        given(sharedTableRepository.findMaxDisplayNumberByRestaurantId(10L)).willReturn(0);
        given(sharedTableRepository.save(any(SharedTable.class))).willAnswer(invocation -> {
            SharedTable table = invocation.getArgument(0);
            ReflectionTestUtils.setField(table, "id", 1L);
            return table;
        });

        // when
        SharedTableIdResponse response = sharedTableService().register(1L, 10L, new SharedTableRequest(4));

        // then
        assertThat(response.tableId()).isEqualTo(1L);
        assertThat(response.displayNumber()).isEqualTo(1);
        verify(sharedTableRepository).save(captor.capture());
        assertThat(captor.getValue().getRestaurantId()).isEqualTo(10L);
        assertThat(captor.getValue().getCapacity()).isEqualTo(4);
        assertThat(captor.getValue().getDisplayNumber()).isEqualTo(1);
        assertThat(captor.getValue().getStatus()).isEqualTo(SharedTableStatus.ACTIVE);
    }

    @Test
    void 합석_테이블을_일괄_등록하면_기존_최대_표시번호_다음부터_연속으로_발급한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        given(restaurantRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).willReturn(Optional.of(restaurant));
        given(sharedTableRepository.findMaxDisplayNumberByRestaurantId(10L)).willReturn(2);
        given(sharedTableRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        // when
        SharedTableBulkResponse response = sharedTableService().registerBulk(
                1L,
                10L,
                new SharedTableBulkRequest(4, 3)
        );

        // then
        assertThat(response.createdTableCount()).isEqualTo(3);
        assertThat(response.tables()).extracting(SharedTableResponse::displayNumber).containsExactly(3, 4, 5);
        assertThat(response.tables()).extracting(SharedTableResponse::capacity).containsOnly(4);
    }

    @Test
    void 일괄_등록_개수가_10개를_초과하면_400_예외가_발생하고_저장하지_않는다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        given(restaurantRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> sharedTableService().registerBulk(
                1L,
                10L,
                new SharedTableBulkRequest(4, 11)
        ));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
        verify(sharedTableRepository, never()).saveAll(any());
    }

    @Test
    void 허용되지_않는_capacity로_등록하면_400_예외가_발생하고_저장하지_않는다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        given(restaurantRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> sharedTableService().register(1L, 10L, new SharedTableRequest(3)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(SharedTableErrorCode.INVALID_TABLE_CAPACITY);
        verify(sharedTableRepository, never()).save(any());
    }

    @Test
    void capacity가_null이면_서비스에서도_400_예외가_발생한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        given(restaurantRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> sharedTableService().register(1L, 10L, new SharedTableRequest(null)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(SharedTableErrorCode.INVALID_TABLE_CAPACITY);
        verify(sharedTableRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_식당에_등록하면_404_예외가_발생한다() {
        // given
        given(restaurantRepository.findByIdAndDeletedAtIsNullForUpdate(999L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> sharedTableService().register(1L, 999L, new SharedTableRequest(4)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND);
    }

    @Test
    void 타인_식당에_등록하면_403_예외가_발생한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(2L);
        given(restaurantRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> sharedTableService().register(1L, 10L, new SharedTableRequest(4)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
        verify(sharedTableRepository, never()).save(any());
    }

    @Test
    void 본인_식당의_테이블_목록을_페이징으로_조회한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable table = sharedTable(1L, 10L, 4);
        Pageable pageable = PageRequest.of(0, 20);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(sharedTableRepository.findAllByRestaurantIdAndDeletedAtIsNull(10L, pageable))
                .willReturn(new PageImpl<>(List.of(table), pageable, 1));

        // when
        PageResponse<SharedTableResponse> response = sharedTableService().getTables(1L, 10L, pageable);

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).status()).isEqualTo(SharedTableStatus.ACTIVE);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void 합석_테이블_상세를_조회하면_식당_소유권을_검증한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable table = sharedTable(1L, 10L, 6);
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(table));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        SharedTableResponse response = sharedTableService().getTable(1L, 1L);

        // then
        assertThat(response.capacity()).isEqualTo(6);
    }

    @Test
    void 존재하지_않거나_삭제된_테이블을_조회하면_404_예외가_발생한다() {
        // given
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(404L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> sharedTableService().getTable(1L, 404L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(SharedTableErrorCode.TABLE_ID_NOT_FOUND);
    }

    @Test
    void 타인_식당의_테이블을_조회하면_403_예외가_발생한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(2L);
        SharedTable table = sharedTable(1L, 10L, 4);
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(table));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> sharedTableService().getTable(1L, 1L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void 본인_테이블의_capacity를_수정한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable table = sharedTable(1L, 10L, 4);
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(table));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        sharedTableService().update(1L, 1L, new SharedTableRequest(8));

        // then
        assertThat(table.getCapacity()).isEqualTo(8);
        verify(sharedTableUsageValidator).validateCapacityChangeAllowed(1L);
    }

    @Test
    void capacity를_수정하면_TABLE_CAPACITY_CHANGED_구조화로그를_남긴다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable table = sharedTable(1L, 10L, 4);
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(table));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        Logger logger = (Logger) LoggerFactory.getLogger(SharedTableService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            sharedTableService().update(1L, 1L, new SharedTableRequest(8));
        } finally {
            logger.detachAppender(appender);
        }

        // then
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("event=TABLE_CAPACITY_CHANGED");
            assertThat(event.getFormattedMessage()).contains("tableId=1");
            assertThat(event.getFormattedMessage()).contains("restaurantId=10");
            assertThat(event.getFormattedMessage()).contains("actorId=1");
            assertThat(event.getFormattedMessage()).contains("beforeCapacity=4");
            assertThat(event.getFormattedMessage()).contains("afterCapacity=8");
        });
    }

    @Test
    void 허용되지_않는_capacity로_수정하면_400_예외가_발생하고_수정하지_않는다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable table = sharedTable(1L, 10L, 4);
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(table));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> sharedTableService().update(1L, 1L, new SharedTableRequest(5)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(SharedTableErrorCode.INVALID_TABLE_CAPACITY);
        assertThat(table.getCapacity()).isEqualTo(4);
        verify(sharedTableUsageValidator, never()).validateCapacityChangeAllowed(any());
    }

    @Test
    void 본인_테이블을_삭제하면_소프트_딜리트된다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable table = sharedTable(1L, 10L, 4);
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(table));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        sharedTableService().delete(1L, 1L);

        // then
        assertThat(table.getDeletedAt()).isEqualTo(FIXED_CLOCK.instant());
        verify(sharedTableUsageValidator).validateDeletionAllowed(1L);
    }

    @Test
    void 연결된_회차가_있는_테이블_삭제는_409_예외가_발생하고_삭제하지_않는다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable table = sharedTable(1L, 10L, 4);
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(table));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        org.mockito.BDDMockito.willThrow(new CustomException(SharedTableErrorCode.TABLE_HAS_DINING_SESSION))
                .given(sharedTableUsageValidator).validateDeletionAllowed(1L);

        // when
        Throwable result = catchThrowable(() -> sharedTableService().delete(1L, 1L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(SharedTableErrorCode.TABLE_HAS_DINING_SESSION);
        assertThat(table.getDeletedAt()).isNull();
    }
}
