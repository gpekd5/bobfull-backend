package com.bobfull.restaurant.sharedtable.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bobfull.restaurant.sharedtable.application.port.SharedTableReservationUsagePort;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JpaSharedTableUsageAdapterTest {

    @Mock private TimeSlotRepository timeSlotRepository;
    @Mock private SharedTableReservationUsagePort reservationUsagePort;

    @Test
    void 연결된_회차의_활성_예약_여부를_조회한다() {
        // given
        TimeSlot timeSlot = TimeSlot.create(100L, Instant.parse("2026-08-01T02:00:00Z"), Instant.parse("2026-08-01T04:00:00Z"));
        ReflectionTestUtils.setField(timeSlot, "id", 200L);
        given(timeSlotRepository.findAllBySharedTableIdAndDeletedAtIsNull(100L)).willReturn(List.of(timeSlot));
        given(reservationUsagePort.hasActiveReservation(List.of(200L))).willReturn(true);
        JpaSharedTableUsageAdapter adapter = new JpaSharedTableUsageAdapter(timeSlotRepository, reservationUsagePort);

        // when
        boolean result = adapter.hasActiveReservation(100L);

        // then
        assertThat(result).isTrue();
    }
}
