package com.bobfull.reservation.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

/**
 * ReservationParticipant의 노쇼 처리·해제 상태 전이를 검증한다(Issue #48).
 */
class ReservationParticipantTest {

    @Test
    void RESERVED_상태의_참여자는_노쇼_처리하면_NO_SHOW로_전이한다() {
        // given
        ReservationParticipant participant = ReservationParticipant.create(1L, 10L, 2);

        // when
        participant.markNoShow();

        // then
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.NO_SHOW);
    }

    @Test
    void RESERVED가_아닌_참여자는_노쇼_처리할_수_없다() {
        // given
        ReservationParticipant participant = ReservationParticipant.create(1L, 10L, 2);
        participant.markNoShow();

        // when
        Throwable result = org.assertj.core.api.Assertions.catchThrowable(participant::markNoShow);

        // then
        assertThat(result).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void NO_SHOW_상태의_참여자는_해제하면_RESERVED로_복귀한다() {
        // given
        ReservationParticipant participant = ReservationParticipant.create(1L, 10L, 2);
        participant.markNoShow();

        // when
        participant.unmarkNoShow();

        // then
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.RESERVED);
    }

    @Test
    void NO_SHOW가_아닌_참여자는_노쇼_해제할_수_없다() {
        // given
        ReservationParticipant participant = ReservationParticipant.create(1L, 10L, 2);

        // when & then
        assertThatThrownBy(participant::unmarkNoShow).isInstanceOf(IllegalStateException.class);
    }
}
