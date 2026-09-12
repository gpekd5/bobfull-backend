package com.bobfull.reservation.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.member.entity.Member;
import com.bobfull.member.repository.MemberRepository;
import com.bobfull.reservation.application.dto.NoShowCustomerResult;
import com.bobfull.reservation.application.dto.NoShowHistoryResult;
import com.bobfull.reservation.domain.entity.NoShowHistory;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.restaurant.entity.Restaurant;
import com.bobfull.restaurant.repository.RestaurantRepository;
import com.bobfull.sharedtable.entity.SharedTable;
import com.bobfull.sharedtable.repository.SharedTableRepository;
import com.bobfull.timeslot.entity.TimeSlot;
import com.bobfull.timeslot.repository.TimeSlotRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:no-show-query-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NoShowQueryRepositoryImpl.class)
class NoShowQueryRepositoryImplTest {

    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");

    @Autowired
    private NoShowQueryRepository noShowQueryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationParticipantRepository reservationParticipantRepository;

    @Autowired
    private NoShowHistoryRepository noShowHistoryRepository;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private SharedTableRepository sharedTableRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    void 예약별_노쇼_이력을_처리_시각_역순으로_조회한다() {
        // given
        Reservation reservation = reservationFor(restaurant());
        Member member = member("10");
        ReservationParticipant participant = participant(reservation, member, 2);
        noShowHistoryRepository.saveAndFlush(NoShowHistory.marked(participant.getId(), 1L, NOW));
        noShowHistoryRepository.saveAndFlush(NoShowHistory.unmarked(participant.getId(), 1L, NOW.plusSeconds(60)));

        // when
        Page<NoShowHistoryResult> results = noShowQueryRepository
                .findHistoriesByReservationId(reservation.getId(), PageRequest.of(0, 20));

        // then
        assertThat(results.getTotalElements()).isEqualTo(2);
        assertThat(results.getContent()).extracting(NoShowHistoryResult::marked).containsExactly(false, true);
        assertThat(results.getContent().get(0).memberName()).isEqualTo("홍길동");
    }

    @Test
    void 다른_예약의_노쇼_이력은_포함되지_않는다() {
        // given
        Restaurant restaurant = restaurant();
        Reservation reservation = reservationFor(restaurant);
        Reservation otherReservation = reservationFor(restaurant);
        ReservationParticipant participant = participant(reservation, member("10"), 2);
        ReservationParticipant otherParticipant = participant(otherReservation, member("11"), 1);
        noShowHistoryRepository.saveAndFlush(NoShowHistory.marked(participant.getId(), 1L, NOW));
        noShowHistoryRepository.saveAndFlush(NoShowHistory.marked(otherParticipant.getId(), 1L, NOW));

        // when
        Page<NoShowHistoryResult> results = noShowQueryRepository
                .findHistoriesByReservationId(reservation.getId(), PageRequest.of(0, 20));

        // then
        assertThat(results.getContent()).extracting(NoShowHistoryResult::participationId)
                .containsExactly(participant.getId());
    }

    @Test
    void 식당별_노쇼_고객을_회원_단위로_집계한다() {
        // given
        Restaurant restaurant = restaurant();
        Member member = member("10");
        Reservation firstReservation = reservationFor(restaurant);
        Reservation secondReservation = reservationFor(restaurant);
        ReservationParticipant firstNoShow = participant(firstReservation, member, 2);
        firstNoShow.markNoShow();
        reservationParticipantRepository.saveAndFlush(firstNoShow);
        noShowHistoryRepository.saveAndFlush(NoShowHistory.marked(firstNoShow.getId(), 1L, NOW));

        ReservationParticipant secondNoShow = participant(secondReservation, member, 1);
        secondNoShow.markNoShow();
        reservationParticipantRepository.saveAndFlush(secondNoShow);
        noShowHistoryRepository.saveAndFlush(NoShowHistory.marked(secondNoShow.getId(), 1L, NOW.plusSeconds(3600)));

        // when
        Page<NoShowCustomerResult> results = noShowQueryRepository
                .findNoShowCustomers(restaurant.getId(), null, null, PageRequest.of(0, 20));

        // then
        assertThat(results.getContent()).hasSize(1);
        NoShowCustomerResult result = results.getContent().get(0);
        assertThat(result.memberId()).isEqualTo(member.getId());
        assertThat(result.noShowCount()).isEqualTo(2);
        assertThat(result.latestNoShowAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(result.reservationId()).isEqualTo(secondReservation.getId());
        assertThat(result.partySize()).isEqualTo(1);
    }

    @Test
    void 노쇼_해제로_현재_RESERVED인_참여자는_집계에서_제외된다() {
        // given
        Restaurant restaurant = restaurant();
        Reservation reservation = reservationFor(restaurant);
        ReservationParticipant participant = participant(reservation, member("10"), 2);
        participant.markNoShow();
        participant.unmarkNoShow();
        reservationParticipantRepository.saveAndFlush(participant);
        noShowHistoryRepository.saveAndFlush(NoShowHistory.marked(participant.getId(), 1L, NOW));
        noShowHistoryRepository.saveAndFlush(NoShowHistory.unmarked(participant.getId(), 1L, NOW.plusSeconds(60)));

        // when
        Page<NoShowCustomerResult> results = noShowQueryRepository
                .findNoShowCustomers(restaurant.getId(), null, null, PageRequest.of(0, 20));

        // then
        assertThat(results.getContent()).isEmpty();
    }

    @Test
    void 기간_필터를_벗어난_노쇼_처리는_집계에서_제외된다() {
        // given
        Restaurant restaurant = restaurant();
        Reservation reservation = reservationFor(restaurant);
        ReservationParticipant participant = participant(reservation, member("10"), 2);
        participant.markNoShow();
        reservationParticipantRepository.saveAndFlush(participant);
        noShowHistoryRepository.saveAndFlush(NoShowHistory.marked(participant.getId(), 1L, NOW));

        // when
        Page<NoShowCustomerResult> outOfRange = noShowQueryRepository.findNoShowCustomers(
                restaurant.getId(), NOW.plusSeconds(3600), NOW.plusSeconds(7200), PageRequest.of(0, 20));
        Page<NoShowCustomerResult> inRange = noShowQueryRepository.findNoShowCustomers(
                restaurant.getId(), NOW.minusSeconds(60), NOW.plusSeconds(60), PageRequest.of(0, 20));

        // then
        assertThat(outOfRange.getContent()).isEmpty();
        assertThat(inRange.getContent()).hasSize(1);
    }

    private Restaurant restaurant() {
        return restaurantRepository.saveAndFlush(
                Restaurant.create(1L, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000));
    }

    private Reservation reservationFor(Restaurant restaurant) {
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), 4));
        TimeSlot timeSlot = timeSlotRepository.saveAndFlush(
                TimeSlot.create(table.getId(), NOW.minusSeconds(7200), NOW.minusSeconds(3600)));
        return reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 1L));
    }

    private Member member(String suffix) {
        return memberRepository.saveAndFlush(
                Member.createMember(suffix + "@example.com", "hash", "홍길동", "0100000" + suffix));
    }

    private ReservationParticipant participant(Reservation reservation, Member member, int partySize) {
        return reservationParticipantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), member.getId(), partySize));
    }
}
