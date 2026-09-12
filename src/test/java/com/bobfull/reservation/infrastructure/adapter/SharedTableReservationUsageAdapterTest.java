package com.bobfull.reservation.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SharedTableReservationUsageAdapterTest {

    @Mock private ReservationRepository reservationRepository;

    @Test
    void 연결된_회차의_활성_예약_여부를_예약_도메인_기준으로_조회한다() {
        // given
        given(reservationRepository.existsByTimeSlotIdInAndReservationStatusIn(any(), any())).willReturn(true);
        SharedTableReservationUsageAdapter adapter = new SharedTableReservationUsageAdapter(reservationRepository);

        // when
        boolean result = adapter.hasActiveReservation(List.of(200L));

        // then
        assertThat(result).isTrue();
    }

    @Test
    void 연결된_회차가_없으면_예약_조회_없이_사용되지_않음으로_판단한다() {
        // given
        SharedTableReservationUsageAdapter adapter = new SharedTableReservationUsageAdapter(reservationRepository);

        // when
        boolean result = adapter.hasActiveReservation(List.of());

        // then
        assertThat(result).isFalse();
    }

    @Test
    void CLOSED_예약_이력이_있으면_테이블_삭제를_사용중으로_판단한다() {
        // given: 노쇼 이력·소유권 조회 체인 보호를 위해 CLOSED도 삭제 차단 대상이다(Issue #175 회귀 수정)
        ArgumentCaptor<List<ReservationStatus>> statusesCaptor = ArgumentCaptor.forClass(List.class);
        given(reservationRepository.existsByTimeSlotIdInAndReservationStatusIn(eq(List.of(200L)), statusesCaptor.capture()))
                .willReturn(true);
        SharedTableReservationUsageAdapter adapter = new SharedTableReservationUsageAdapter(reservationRepository);

        // when
        boolean result = adapter.hasActiveReservation(List.of(200L));

        // then
        assertThat(result).isTrue();
        assertThat(statusesCaptor.getValue()).contains(ReservationStatus.CLOSED);
    }
}
