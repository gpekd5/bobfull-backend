package com.bobfull.reservation.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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

@ExtendWith(MockitoExtension.class)
class ReservationCapacityReaderAdapterTest {

    @Mock private TimeSlotRepository timeSlotRepository;
    @Mock private SharedTableRepository sharedTableRepository;

    @Test
    void 예약_확정에_필요한_테이블_정원만_반환한다() {
        // given
        TimeSlot timeSlot = TimeSlot.create(100L, Instant.parse("2026-08-01T02:00:00Z"), Instant.parse("2026-08-01T04:00:00Z"));
        SharedTable sharedTable = SharedTable.create(10L, 4);
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(200L)).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        ReservationCapacityReaderAdapter adapter = new ReservationCapacityReaderAdapter(
                timeSlotRepository, sharedTableRepository);

        // when
        int capacity = adapter.readTableCapacity(200L);

        // then
        assertThat(capacity).isEqualTo(4);
    }

    @Test
    void 취소_기한_검증에_필요한_회차_시작_시각을_반환한다() {
        // given
        Instant startAt = Instant.parse("2026-08-01T02:00:00Z");
        TimeSlot timeSlot = TimeSlot.create(100L, startAt, Instant.parse("2026-08-01T04:00:00Z"));
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(200L)).willReturn(Optional.of(timeSlot));
        ReservationCapacityReaderAdapter adapter = new ReservationCapacityReaderAdapter(
                timeSlotRepository, sharedTableRepository);

        // when
        Instant result = adapter.readTimeSlotStartAt(200L);

        // then
        assertThat(result).isEqualTo(startAt);
    }
}
