package com.bobfull.admin.infrastructure.repository.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.admin.application.result.AdminReservationResult;
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
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class AdminReservationRepositoryImplTest {

    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;

    @Test
    void 상태_필터와_참여인원_정원을_함께_반환한다() {
        Restaurant restaurant = restaurantRepository.save(
                Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot timeSlot = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        Reservation reservation = reservationRepository.save(Reservation.create(timeSlot.getId(), 10L));
        participantRepository.save(ReservationParticipant.create(reservation.getId(), 10L, 2));

        var result = reservationRepository.searchReservations(
                ReservationStatus.RECRUITING, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        AdminReservationResult item = result.getContent().get(0);
        assertThat(item.restaurantName()).isEqualTo("식당");
        assertThat(item.currentParticipantCount()).isEqualTo(2);
        assertThat(item.capacity()).isEqualTo(4);
    }

    @Test
    void 날짜_범위로_필터링한다() {
        Restaurant restaurant = restaurantRepository.save(
                Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot inRange = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        TimeSlot outOfRange = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-09-01T09:00:00Z"), Instant.parse("2026-09-01T11:00:00Z")));
        Reservation included = reservationRepository.save(Reservation.create(inRange.getId(), 10L));
        reservationRepository.save(Reservation.create(outOfRange.getId(), 11L));

        var result = reservationRepository.searchReservations(
                null, Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"), PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AdminReservationResult::reservationId).containsExactly(included.getId());
    }
}
