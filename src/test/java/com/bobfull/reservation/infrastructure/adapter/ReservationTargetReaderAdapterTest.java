package com.bobfull.reservation.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.bobfull.reservation.application.port.ReservationTargetReader;
import com.bobfull.restaurant.entity.Restaurant;
import com.bobfull.restaurant.repository.RestaurantRepository;
import com.bobfull.sharedtable.entity.SharedTable;
import com.bobfull.sharedtable.repository.SharedTableRepository;
import com.bobfull.timeslot.entity.TimeSlot;
import com.bobfull.timeslot.repository.TimeSlotRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationTargetReaderAdapterTest {

    @Mock private TimeSlotRepository timeSlotRepository;
    @Mock private SharedTableRepository sharedTableRepository;
    @Mock private RestaurantRepository restaurantRepository;

    @Test
    void 잠금_조회는_연결_구조를_예약_대상_DTO로_변환한다() {
        // given
        TimeSlot timeSlot = TimeSlot.create(100L, Instant.parse("2026-08-01T02:00:00Z"), Instant.parse("2026-08-01T04:00:00Z"));
        SharedTable sharedTable = SharedTable.create(10L, 4);
        Restaurant restaurant = Restaurant.create(1L, "식당", "주소", "한식", "설명", "태그", 10000);
        ReflectionTestUtils.setField(timeSlot, "id", 200L);
        given(timeSlotRepository.findWithLockByIdAndDeletedAtIsNull(200L)).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        ReservationTargetReaderAdapter adapter = new ReservationTargetReaderAdapter(
                timeSlotRepository, sharedTableRepository, restaurantRepository);

        // when
        ReservationTargetReader.ReservationTarget target = adapter.read(200L, true);

        // then
        assertThat(target).isEqualTo(new ReservationTargetReader.ReservationTarget(200L, 4, 10000));
        verify(timeSlotRepository).findWithLockByIdAndDeletedAtIsNull(200L);
    }

    @Test
    void 일반_조회는_잠금_없는_회차_조회_경로를_사용한다() {
        // given
        TimeSlot timeSlot = TimeSlot.create(100L, Instant.parse("2026-08-01T02:00:00Z"), Instant.parse("2026-08-01T04:00:00Z"));
        SharedTable sharedTable = SharedTable.create(10L, 4);
        Restaurant restaurant = Restaurant.create(1L, "식당", "주소", "한식", "설명", "태그", 10000);
        ReflectionTestUtils.setField(timeSlot, "id", 200L);
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(200L)).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        ReservationTargetReaderAdapter adapter = new ReservationTargetReaderAdapter(
                timeSlotRepository, sharedTableRepository, restaurantRepository);

        // when
        adapter.read(200L, false);

        // then
        verify(timeSlotRepository).findByIdAndDeletedAtIsNull(200L);
        verify(timeSlotRepository, never()).findWithLockByIdAndDeletedAtIsNull(200L);
    }
}
