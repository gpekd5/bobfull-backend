package com.bobfull.restaurant.sharedtable.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.SharedTableErrorCode;
import com.bobfull.restaurant.sharedtable.application.port.SharedTableUsagePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SharedTableUsageValidatorTest {

    @Mock
    private SharedTableUsagePort sharedTableUsagePort;

    private SharedTableUsageValidator validator() {
        return new SharedTableUsageValidator(sharedTableUsagePort);
    }

    @Test
    void 활성_회차가_연결된_합석_테이블은_삭제할_수_없다() {
        // given
        given(sharedTableUsagePort.hasDiningSession(100L)).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> validator().validateDeletionAllowed(100L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(SharedTableErrorCode.TABLE_HAS_DINING_SESSION);
    }

    @Test
    void 활성_예약이_연결된_회차가_있으면_정원을_변경할_수_없다() {
        // given
        given(sharedTableUsagePort.hasActiveReservation(100L)).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> validator().validateCapacityChangeAllowed(100L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(SharedTableErrorCode.TABLE_HAS_RESERVATION);
    }

    @Test
    void 연결된_회차가_없으면_정원을_변경할_수_있다() {
        // given
        given(sharedTableUsagePort.hasActiveReservation(100L)).willReturn(false);

        // when
        Throwable result = catchThrowable(() -> validator().validateCapacityChangeAllowed(100L));

        // then
        assertThat(result).isNull();
    }

}
