package com.bobfull.reservation.infrastructure.repository.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import com.bobfull.reservation.presentation.request.ReservationSearchRequest;
import com.bobfull.reservation.application.result.ReservationSearchResult;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reservation-search-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReservationSearchRepositoryTest {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationParticipantRepository reservationParticipantRepository;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private SharedTableRepository sharedTableRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void 기본_검색은_RECRUITING_또는_CONFIRMED이면서_OPEN_모집만_반환한다() {
        // given
        Reservation openReservation = createReservation("밥풀식당", "한식", "흑돼지,혼밥", 4, "2026-08-01T18:00:00");
        Reservation closedRecruitment = createReservation("마감식당", "한식", "갈치조림", 4, "2026-08-01T19:00:00");
        closedRecruitment.closeRecruitment();
        reservationRepository.flush();

        // when
        Page<ReservationSearchResult> result = reservationRepository.searchRecruitingReservations(
                new ReservationSearchRequest(null, null, null, null, null),
                NOW,
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).extracting(ReservationSearchResult::reservationId)
                .containsExactly(openReservation.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void 키워드_날짜_시간_정원_조건에_맞는_모집중_예약만_검색한다() {
        // given
        Reservation matched = createReservation("밥풀식당", "한식", "흑돼지,혼밥", 4, "2026-08-01T18:00:00");
        createReservation("초밥집", "일식", "스시", 4, "2026-08-01T18:00:00");
        createReservation("다른시간식당", "한식", "흑돼지", 4, "2026-08-01T19:00:00");

        // when
        Page<ReservationSearchResult> result = reservationRepository.searchRecruitingReservations(
                new ReservationSearchRequest(
                        "흑돼지", LocalDate.of(2026, 8, 1), LocalTime.of(18, 0), 4, null),
                NOW,
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).extracting(ReservationSearchResult::reservationId)
                .containsExactly(matched.getId());
    }

    @Test
    void 최소_잔여석은_참여자와_READY_선점_인원을_차감해_필터링한다() {
        // given
        Reservation remainingOne = createReservation("잔여한자리", "한식", "흑돼지", 4, "2026-08-01T18:00:00");
        reservationParticipantRepository.save(ReservationParticipant.create(remainingOne.getId(), 2L, 2));
        paymentRepository.save(Payment.createReady(
                "ready-hold-1",
                3L,
                remainingOne.getTimeSlotId(),
                remainingOne.getId(),
                PaymentPurpose.JOIN,
                1,
                BigDecimal.valueOf(10000),
                NOW.plusSeconds(600)
        ));

        Reservation remainingThree = createReservation("잔여세자리", "한식", "흑돼지", 4, "2026-08-01T19:00:00");
        reservationRepository.flush();

        // when
        Page<ReservationSearchResult> result = reservationRepository.searchRecruitingReservations(
                new ReservationSearchRequest("흑돼지", null, null, null, 2),
                NOW,
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).extracting(ReservationSearchResult::reservationId)
                .containsExactly(remainingThree.getId());
        assertThat(result.getContent().get(0).availableCapacity()).isEqualTo(3);
    }

    @Test
    void recent_정렬은_예약_최신_생성순으로_조회한다() {
        // given
        Reservation oldReservation =
                createReservation("먼저생성예약", "한식", "흑돼지", 4, "2026-08-01T18:00:00");
        Reservation recentReservation =
                createReservation("나중생성예약", "한식", "갈치조림", 4, "2026-08-01T19:00:00");
        reservationRepository.flush();

        // when
        Page<ReservationSearchResult> result = reservationRepository.searchRecruitingReservations(
                new ReservationSearchRequest(null, null, null, null, null),
                NOW,
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("recent"))));

        // then
        assertThat(result.getContent()).extracting(ReservationSearchResult::reservationId)
                .containsExactly(recentReservation.getId(), oldReservation.getId());
    }

    private Reservation createReservation(
            String restaurantName,
            String category,
            String keyword,
            int capacity,
            String startAt
    ) {
        Restaurant restaurant = restaurantRepository.save(
                Restaurant.create(1L, restaurantName, "제주시 애월읍 1", category, "설명", keyword, 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), capacity));
        TimeSlot timeSlot = timeSlotRepository.save(TimeSlot.create(
                table.getId(),
                toInstant(startAt),
                toInstant(startAt).plusSeconds(7_200)
        ));
        Reservation reservation = reservationRepository.save(Reservation.create(timeSlot.getId(), 1L));
        reservationParticipantRepository.save(ReservationParticipant.create(reservation.getId(), 1L, 1));
        return reservation;
    }

    private Instant toInstant(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SEOUL_ZONE).toInstant();
    }
}
