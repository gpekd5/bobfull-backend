package com.bobfull.reservation.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dining-end-candidate-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DiningEndCandidateRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Test
    void 식사_종료_시각에_도달한_CONFIRMED_예약만_후보로_조회된다() {
        // given: endAt <= now, CONFIRMED -> 후보
        Reservation ended = confirmedReservationEndingAt(NOW.minusSeconds(60));

        // when
        List<Long> candidates = reservationRepository.findDiningEndCandidateIds(
                ReservationStatus.CONFIRMED, NOW, PageRequest.of(0, 100));

        // then
        assertThat(candidates).containsExactly(ended.getId());
    }

    @Test
    void 식사_종료_시각과_정확히_같아도_후보로_조회된다() {
        // given: endAt == now (Issue #175 Q1 경계)
        Reservation endingNow = confirmedReservationEndingAt(NOW);

        // when
        List<Long> candidates = reservationRepository.findDiningEndCandidateIds(
                ReservationStatus.CONFIRMED, NOW, PageRequest.of(0, 100));

        // then
        assertThat(candidates).containsExactly(endingNow.getId());
    }

    @Test
    void 아직_식사가_끝나지_않은_예약은_후보에서_제외된다() {
        // given
        confirmedReservationEndingAt(NOW.plusSeconds(60));

        // when
        List<Long> candidates = reservationRepository.findDiningEndCandidateIds(
                ReservationStatus.CONFIRMED, NOW, PageRequest.of(0, 100));

        // then
        assertThat(candidates).isEmpty();
    }

    @Test
    void RECRUITING_예약은_후보에서_제외된다() {
        // given: 식사 종료 시점까지 RECRUITING인 예약은 자동 종료로 덮지 않는다(Issue #175 Q2)
        TimeSlot timeSlot = timeSlotRepository.saveAndFlush(
                TimeSlot.create(1000L, NOW.minusSeconds(3600), NOW.minusSeconds(60)));
        reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 1L));

        // when
        List<Long> candidates = reservationRepository.findDiningEndCandidateIds(
                ReservationStatus.CONFIRMED, NOW, PageRequest.of(0, 100));

        // then
        assertThat(candidates).isEmpty();
    }

    @Test
    void 이미_CLOSED인_예약은_후보에서_제외된다() {
        // given
        Reservation reservation = confirmedReservationEndingAt(NOW.minusSeconds(60));
        reservation.close();
        reservationRepository.saveAndFlush(reservation);

        // when
        List<Long> candidates = reservationRepository.findDiningEndCandidateIds(
                ReservationStatus.CONFIRMED, NOW, PageRequest.of(0, 100));

        // then
        assertThat(candidates).isEmpty();
    }

    @Test
    void 회차_종료_시각_오름차순으로_정렬된다() {
        // given
        Reservation later = confirmedReservationEndingAt(NOW.minusSeconds(10));
        Reservation earlier = confirmedReservationEndingAt(NOW.minusSeconds(120));

        // when
        List<Long> candidates = reservationRepository.findDiningEndCandidateIds(
                ReservationStatus.CONFIRMED, NOW, PageRequest.of(0, 100));

        // then
        assertThat(candidates).containsExactly(earlier.getId(), later.getId());
    }

    private Reservation confirmedReservationEndingAt(Instant endAt) {
        TimeSlot timeSlot = timeSlotRepository.saveAndFlush(
                TimeSlot.create(1000L, endAt.minusSeconds(3600), endAt));
        Reservation reservation = Reservation.create(timeSlot.getId(), 1L);
        reservation.confirm();
        return reservationRepository.saveAndFlush(reservation);
    }
}
