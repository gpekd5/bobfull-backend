package com.bobfull.reservation.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.reservation.domain.entity.RecruitmentStatus;
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
        "spring.datasource.url=jdbc:h2:mem:recruitment-deadline-candidate-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecruitmentDeadlineCandidateRepositoryTest {

    private static final Instant DEADLINE = Instant.parse("2026-08-08T00:00:00Z");
    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED);

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Test
    void 마감기한_이내이고_모집중인_예약만_후보로_조회된다() {
        // given: 마감기한(2시간 전) 이내 시작, 모집 OPEN, RECRUITING -> 후보
        Reservation withinDeadline = reservationAt(DEADLINE.minusSeconds(60));

        // when
        List<Long> candidates = reservationRepository.findRecruitmentDeadlineCandidateIds(
                RecruitmentStatus.OPEN, ACTIVE_STATUSES, DEADLINE, PageRequest.of(0, 100));

        // then
        assertThat(candidates).containsExactly(withinDeadline.getId());
    }

    @Test
    void 아직_마감기한_이전인_예약은_후보에서_제외된다() {
        // given: 마감기한보다 나중에 시작하는 회차
        reservationAt(DEADLINE.plusSeconds(3600));

        // when
        List<Long> candidates = reservationRepository.findRecruitmentDeadlineCandidateIds(
                RecruitmentStatus.OPEN, ACTIVE_STATUSES, DEADLINE, PageRequest.of(0, 100));

        // then
        assertThat(candidates).isEmpty();
    }

    @Test
    void 이미_모집_마감된_예약은_후보에서_제외된다() {
        // given
        Reservation reservation = reservationAt(DEADLINE.minusSeconds(60));
        reservation.closeRecruitment();
        reservationRepository.saveAndFlush(reservation);

        // when
        List<Long> candidates = reservationRepository.findRecruitmentDeadlineCandidateIds(
                RecruitmentStatus.OPEN, ACTIVE_STATUSES, DEADLINE, PageRequest.of(0, 100));

        // then
        assertThat(candidates).isEmpty();
    }

    @Test
    void 이미_취소_접수된_예약은_후보에서_제외된다() {
        // given
        Reservation reservation = reservationAt(DEADLINE.minusSeconds(60));
        reservation.startCancelling();
        reservationRepository.saveAndFlush(reservation);

        // when
        List<Long> candidates = reservationRepository.findRecruitmentDeadlineCandidateIds(
                RecruitmentStatus.OPEN, ACTIVE_STATUSES, DEADLINE, PageRequest.of(0, 100));

        // then
        assertThat(candidates).isEmpty();
    }

    @Test
    void 회차_시작_시각_오름차순으로_정렬된다() {
        // given
        Reservation later = reservationAt(DEADLINE.minusSeconds(10));
        Reservation earlier = reservationAt(DEADLINE.minusSeconds(120));

        // when
        List<Long> candidates = reservationRepository.findRecruitmentDeadlineCandidateIds(
                RecruitmentStatus.OPEN, ACTIVE_STATUSES, DEADLINE, PageRequest.of(0, 100));

        // then
        assertThat(candidates).containsExactly(earlier.getId(), later.getId());
    }

    private Reservation reservationAt(Instant startAt) {
        TimeSlot timeSlot = timeSlotRepository.saveAndFlush(
                TimeSlot.create(1000L, startAt, startAt.plusSeconds(7200)));
        return reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 1L));
    }
}
