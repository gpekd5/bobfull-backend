package com.bobfull.reservation.infrastructure.repository.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import com.bobfull.reservation.application.result.MyReservationResult;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:my-reservation-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MyReservationQueryRepositoryImplTest {

    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");

    @Autowired
    private ReservationParticipantRepository reservationParticipantRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private SharedTableRepository sharedTableRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void 최초_예약자와_참여자_모두_본인_예약_목록에_포함된다() {
        // given
        Reservation reservation = reservation();
        confirmedParticipant(reservation, 1L, 2, PaymentPurpose.CREATE);
        confirmedParticipant(reservation, 2L, 1, PaymentPurpose.JOIN);

        // when
        Page<MyReservationResult> creatorView = reservationParticipantRepository
                .searchMyReservations(1L, null, PageRequest.of(0, 20));
        Page<MyReservationResult> joinerView = reservationParticipantRepository
                .searchMyReservations(2L, null, PageRequest.of(0, 20));

        // then
        assertThat(creatorView.getContent()).extracting(MyReservationResult::participationId).hasSize(1);
        assertThat(creatorView.getContent().get(0).partySize()).isEqualTo(2);
        assertThat(joinerView.getContent()).extracting(MyReservationResult::participationId).hasSize(1);
        assertThat(joinerView.getContent().get(0).partySize()).isEqualTo(1);
    }

    @Test
    void reservationStatus_필터가_적용된다() {
        // given
        Reservation recruiting = reservation();
        confirmedParticipant(recruiting, 1L, 1, PaymentPurpose.CREATE);
        Reservation confirmed = reservation();
        confirmed.confirm();
        reservationRepository.saveAndFlush(confirmed);
        confirmedParticipant(confirmed, 1L, 1, PaymentPurpose.CREATE);

        // when
        Page<MyReservationResult> confirmedOnly = reservationParticipantRepository
                .searchMyReservations(1L, ReservationStatus.CONFIRMED, PageRequest.of(0, 20));

        // then
        assertThat(confirmedOnly.getContent()).extracting(MyReservationResult::reservationId)
                .containsExactly(confirmed.getId());
    }

    @Test
    void 본인_참여가_아닌_예약_상세는_조회되지_않는다() {
        // given
        Reservation reservation = reservation();
        confirmedParticipant(reservation, 1L, 1, PaymentPurpose.CREATE);

        // when
        Optional<MyReservationResult> ownDetail = reservationParticipantRepository
                .findMyReservationDetail(1L, reservation.getId());
        Optional<MyReservationResult> otherMemberDetail = reservationParticipantRepository
                .findMyReservationDetail(99L, reservation.getId());

        // then
        assertThat(ownDetail).isPresent();
        assertThat(ownDetail.get().paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(otherMemberDetail).isEmpty();
    }

    private Reservation reservation() {
        Restaurant restaurant = restaurantRepository.save(
                Restaurant.create(1L, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot timeSlot = timeSlotRepository.save(TimeSlot.create(
                table.getId(), NOW.plusSeconds(3600), NOW.plusSeconds(7200)));
        return reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 1L));
    }

    private void confirmedParticipant(Reservation reservation, Long memberId, int partySize, PaymentPurpose purpose) {
        ReservationParticipant participant = reservationParticipantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), memberId, partySize));
        Long readyReservationId = purpose == PaymentPurpose.JOIN ? reservation.getId() : null;
        Payment payment = Payment.createReady("payment-" + reservation.getId() + "-" + memberId, memberId,
                reservation.getTimeSlotId(), readyReservationId, purpose, partySize, BigDecimal.valueOf(10000), NOW.plusSeconds(600));
        payment.complete(NOW);
        payment.attachReservationConfirmation(reservation.getId(), participant.getId());
        paymentRepository.saveAndFlush(payment);
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.RESERVED);
    }
}
