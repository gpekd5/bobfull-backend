package com.bobfull.reservation.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimeSlotReservationUsageAdapterTest {

    @Mock private ReservationRepository reservationRepository;

    @Test
    void 활성_예약_여부는_예약_도메인의_상태_정의로_조회한다() {
        // given
        given(reservationRepository.existsByTimeSlotIdAndReservationStatusIn(any(), any())).willReturn(true);
        TimeSlotReservationUsageAdapter adapter = new TimeSlotReservationUsageAdapter(reservationRepository);

        // when
        boolean result = adapter.hasActiveReservation(200L);

        // then
        assertThat(result).isTrue();
        verify(reservationRepository).existsByTimeSlotIdAndReservationStatusIn(any(), any());
    }

    @Test
    void CLOSED_예약이_있으면_회차_변경도_사용중으로_판단한다() {
        // given: 노쇼 이력 보호·재예약 우회 방지를 위해 CLOSED도 변경·삭제 차단 대상이다(Issue #175 회귀 수정)
        ArgumentCaptor<List<ReservationStatus>> statusesCaptor = ArgumentCaptor.forClass(List.class);
        given(reservationRepository.existsByTimeSlotIdAndReservationStatusIn(eq(200L), statusesCaptor.capture()))
                .willReturn(true);
        TimeSlotReservationUsageAdapter adapter = new TimeSlotReservationUsageAdapter(reservationRepository);

        // when
        boolean result = adapter.hasActiveReservation(200L);

        // then
        assertThat(result).isTrue();
        assertThat(statusesCaptor.getValue()).contains(ReservationStatus.CLOSED);
    }
}
