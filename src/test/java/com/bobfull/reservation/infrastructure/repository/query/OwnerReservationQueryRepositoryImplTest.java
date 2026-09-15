package com.bobfull.reservation.infrastructure.repository.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.config.JpaAuditingConfig;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import com.bobfull.reservation.application.result.OwnerReservationResult;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
@Import({JpaAuditingConfig.class, ClockConfig.class})
class OwnerReservationQueryRepositoryImplTest {

    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;
    @Autowired private PaymentRepository paymentRepository;

    @Test
    void 다른_식당의_예약은_목록에_포함되지_않는다() {
        Restaurant myRestaurant = restaurantRepository.save(
                Restaurant.create(1L, "내 식당", "주소", "한식", "설명", "키워드", 10000));
        Restaurant otherRestaurant = restaurantRepository.save(
                Restaurant.create(2L, "다른 식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable myTable = sharedTableRepository.save(SharedTable.create(myRestaurant.getId(), 4));
        SharedTable otherTable = sharedTableRepository.save(SharedTable.create(otherRestaurant.getId(), 4));
        TimeSlot myTimeSlot = timeSlotRepository.save(TimeSlot.create(
                myTable.getId(), Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        TimeSlot otherTimeSlot = timeSlotRepository.save(TimeSlot.create(
                otherTable.getId(), Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        Reservation myReservation = reservationRepository.save(Reservation.create(myTimeSlot.getId(), 10L));
        reservationRepository.save(Reservation.create(otherTimeSlot.getId(), 11L));

        var result = reservationRepository.searchOwnerReservations(
                myRestaurant.getId(), null, null, null, Instant.parse("2026-08-01T00:00:00Z"), PageRequest.of(0, 20));

        assertThat(result.getContent())
                .extracting(OwnerReservationResult::reservationId)
                .containsExactly(myReservation.getId());
    }

    @Test
    void reservationStatus_조건으로_필터링한다() {
        Restaurant restaurant = restaurantRepository.save(
                Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot recruitingSlot = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        TimeSlot confirmedSlot = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-01T13:00:00Z"), Instant.parse("2026-08-01T15:00:00Z")));
        Reservation recruiting = reservationRepository.save(Reservation.create(recruitingSlot.getId(), 10L));
        Reservation confirmed = reservationRepository.save(Reservation.create(confirmedSlot.getId(), 11L));
        confirmed.confirm();
        reservationRepository.save(confirmed);

        var result = reservationRepository.searchOwnerReservations(
                restaurant.getId(), ReservationStatus.CONFIRMED, null, null,
                Instant.parse("2026-08-01T00:00:00Z"), PageRequest.of(0, 20));

        assertThat(result.getContent())
                .extracting(OwnerReservationResult::reservationId)
                .containsExactly(confirmed.getId());
        assertThat(recruiting).isNotNull();
    }

    @Test
    void 날짜_범위_밖의_예약은_제외한다() {
        Restaurant restaurant = restaurantRepository.save(
                Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot inRange = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        TimeSlot outOfRange = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-02T09:00:00Z"), Instant.parse("2026-08-02T11:00:00Z")));
        Reservation included = reservationRepository.save(Reservation.create(inRange.getId(), 10L));
        reservationRepository.save(Reservation.create(outOfRange.getId(), 11L));

        var result = reservationRepository.searchOwnerReservations(
                restaurant.getId(), null,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"), PageRequest.of(0, 20));

        assertThat(result.getContent())
                .extracting(OwnerReservationResult::reservationId)
                .containsExactly(included.getId());
    }

    @Test
    void 취소접수와_READY_임시선점을_잔여좌석에서_차감한다() {
        Restaurant restaurant = restaurantRepository.save(
                Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 6));
        TimeSlot timeSlot = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        Reservation reservation = reservationRepository.save(Reservation.create(timeSlot.getId(), 10L));
        ReservationParticipant reserved = ReservationParticipant.create(reservation.getId(), 10L, 2);
        participantRepository.save(reserved);
        ReservationParticipant cancelRequested = ReservationParticipant.create(reservation.getId(), 11L, 1);
        cancelRequested.requestCancel("개인 사정");
        participantRepository.save(cancelRequested);
        paymentRepository.save(Payment.createReady(
                "owner-list-payment-1", 12L, timeSlot.getId(), null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-08-01T08:50:00Z")));

        var result = reservationRepository.searchOwnerReservations(
                restaurant.getId(), null, null, null, Instant.parse("2026-08-01T08:00:00Z"), PageRequest.of(0, 20));

        OwnerReservationResult item = result.getContent().get(0);
        assertThat(item.currentParticipantCount()).isEqualTo(3L);
        assertThat(item.availableCapacity()).isEqualTo(2);
        assertThat(item.confirmationThreshold()).isEqualTo(5);
    }
}
