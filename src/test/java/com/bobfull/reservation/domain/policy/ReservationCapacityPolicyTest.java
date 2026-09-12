package com.bobfull.reservation.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReservationCapacityPolicyTest {

    @Test
    void 정원이_2명이면_확정_기준은_2명이다() {
        assertThat(ReservationCapacityPolicy.confirmationThreshold(2)).isEqualTo(2);
    }

    @Test
    void 정원이_2명보다_크면_확정_기준은_정원_마이너스_1명이다() {
        assertThat(ReservationCapacityPolicy.confirmationThreshold(4)).isEqualTo(3);
        assertThat(ReservationCapacityPolicy.confirmationThreshold(6)).isEqualTo(5);
    }

    @Test
    void 잔여_좌석은_정원에서_참여_인원과_임시_선점_인원을_차감한다() {
        assertThat(ReservationCapacityPolicy.availableCapacity(4, 1L, 1L)).isEqualTo(2);
    }

    @Test
    void 참여_인원과_임시_선점_인원의_합이_정원을_넘어도_잔여_좌석은_0_미만으로_내려가지_않는다() {
        assertThat(ReservationCapacityPolicy.availableCapacity(4, 3L, 3L)).isEqualTo(0);
    }
}
