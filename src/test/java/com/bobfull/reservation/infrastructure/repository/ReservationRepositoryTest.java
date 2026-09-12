package com.bobfull.reservation.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reservation-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReservationRepositoryTest {

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationParticipantRepository reservationParticipantRepository;

    @Test
    void 회차에_RECRUITING_또는_CONFIRMED_예약이_있으면_활성_예약_존재를_확인한다() {
        // given
        reservationRepository.saveAndFlush(Reservation.create(200L, 1L));

        // when & then
        assertThat(reservationRepository.existsByTimeSlotIdAndReservationStatusIn(
                200L, List.of(ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED))).isTrue();
        assertThat(reservationRepository.existsByTimeSlotIdInAndReservationStatusIn(
                List.of(200L, 999L), List.of(ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED))).isTrue();
    }

    @Test
    void 같은_예약에_같은_회원이_중복_참여하면_제약_위반이_발생한다() {
        // given
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(200L, 1L));
        reservationParticipantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), 1L, 2));

        // when & then
        assertThatThrownBy(() -> reservationParticipantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), 1L, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void RESERVED_참여자의_partySize_합계를_계산한다() {
        // given
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(200L, 1L));
        reservationParticipantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), 1L, 2));
        reservationParticipantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), 2L, 1));

        // when
        int sum = reservationParticipantRepository.sumPartySize(reservation.getId(), ParticipationStatus.RESERVED);

        // then
        assertThat(sum).isEqualTo(3);
    }
}
