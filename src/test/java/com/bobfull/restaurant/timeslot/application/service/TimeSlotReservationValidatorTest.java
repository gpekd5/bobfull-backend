package com.bobfull.restaurant.timeslot.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.TimeSlotErrorCode;
import com.bobfull.restaurant.timeslot.application.port.TimeSlotReservationUsagePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimeSlotReservationValidatorTest {

    @Mock
    private TimeSlotReservationUsagePort reservationUsagePort;

    private TimeSlotReservationValidator validator() {
        return new TimeSlotReservationValidator(reservationUsagePort);
    }

    @Test
    void 활성_예약이_있는_회차는_수정할_수_없다() {
        // given
        given(reservationUsagePort.hasActiveReservation(200L)).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> validator().validateChangeAllowed(200L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(TimeSlotErrorCode.SESSION_HAS_RESERVATION);
    }

    @Test
    void 활성_예약이_없는_회차는_삭제할_수_있다() {
        // given
        given(reservationUsagePort.hasActiveReservation(200L)).willReturn(false);

        // when
        Throwable result = catchThrowable(() -> validator().validateDeletionAllowed(200L));

        // then
        assertThat(result).isNull();
    }
}
