package com.bobfull.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.admin.dto.AdminNoShowResult;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.reservation.domain.entity.NoShowHistory;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.infrastructure.repository.NoShowHistoryRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * AdminNoShowRepositoryImpl은 다른 JpaRepository에 합성되는 Fragment가 아니라 별도 Bean이라
 * @DataJpaTest의 기본 스캔 대상이 아니다 — @Import로 명시해 EntityManager를 주입받게 한다.
 */
@DataJpaTest
@Import(AdminNoShowRepositoryImpl.class)
class AdminNoShowRepositoryImplTest {

    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");

    @Autowired private AdminNoShowRepository adminNoShowRepository;
    @Autowired private NoShowHistoryRepository noShowHistoryRepository;
    @Autowired private ReservationParticipantRepository participantRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private MemberRepository memberRepository;

    @Test
    void 노쇼_처리_이력을_조회한다() {
        Restaurant restaurant = restaurant();
        Member member = member("10");
        ReservationParticipant participant = noShowParticipant(restaurant, member, 2, NOW);

        Page<AdminNoShowResult> result = adminNoShowRepository.searchNoShows(null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        AdminNoShowResult item = result.getContent().get(0);
        assertThat(item.memberId()).isEqualTo(member.getId());
        assertThat(item.memberName()).isEqualTo("홍길동");
        assertThat(item.restaurantId()).isEqualTo(restaurant.getId());
        assertThat(item.restaurantName()).isEqualTo("밥풀식당");
        assertThat(item.participationId()).isEqualTo(participant.getId());
        assertThat(item.partySize()).isEqualTo(2);
    }

    @Test
    void 노쇼_해제_이력은_포함되지_않는다() {
        Restaurant restaurant = restaurant();
        Member member = member("10");
        ReservationParticipant participant = noShowParticipant(restaurant, member, 2, NOW);
        participant.unmarkNoShow();
        participantRepository.saveAndFlush(participant);
        noShowHistoryRepository.saveAndFlush(NoShowHistory.unmarked(participant.getId(), 1L, NOW.plusSeconds(60)));

        Page<AdminNoShowResult> result = adminNoShowRepository.searchNoShows(null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void memberId_필터가_적용된다() {
        Restaurant restaurant = restaurant();
        Member first = member("10");
        Member second = member("11");
        noShowParticipant(restaurant, first, 2, NOW);
        noShowParticipant(restaurant, second, 1, NOW);

        Page<AdminNoShowResult> result = adminNoShowRepository.searchNoShows(
                second.getId(), null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AdminNoShowResult::memberId).containsExactly(second.getId());
    }

    @Test
    void 처리_해제_재처리를_반복해도_최신_이력_1건만_조회된다() {
        Restaurant restaurant = restaurant();
        Member member = member("10");
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), 4));
        TimeSlot timeSlot = timeSlotRepository.saveAndFlush(
                TimeSlot.create(table.getId(), NOW.minusSeconds(7200), NOW.minusSeconds(3600)));
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 1L));
        ReservationParticipant participant = participantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), member.getId(), 2));

        // 처리 → 해제 → 재처리: marked=true 이력이 2건 남지만 현재 상태는 NO_SHOW 1건이다.
        participant.markNoShow();
        participantRepository.saveAndFlush(participant);
        noShowHistoryRepository.saveAndFlush(NoShowHistory.marked(participant.getId(), 1L, NOW));
        participant.unmarkNoShow();
        participantRepository.saveAndFlush(participant);
        noShowHistoryRepository.saveAndFlush(NoShowHistory.unmarked(participant.getId(), 1L, NOW.plusSeconds(60)));
        participant.markNoShow();
        participantRepository.saveAndFlush(participant);
        NoShowHistory latest = noShowHistoryRepository.saveAndFlush(
                NoShowHistory.marked(participant.getId(), 1L, NOW.plusSeconds(120)));

        Page<AdminNoShowResult> result = adminNoShowRepository.searchNoShows(null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).noShowHistoryId()).isEqualTo(latest.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void restaurantId_필터가_적용된다() {
        Restaurant first = restaurant();
        Restaurant second = restaurantRepository.saveAndFlush(
                Restaurant.create(1L, "다른식당", "제주시", "한식", "설명", "키워드", 10000));
        Member member = member("10");
        noShowParticipant(first, member, 2, NOW);
        ReservationParticipant secondParticipant = noShowParticipant(second, member, 1, NOW.plusSeconds(60));

        Page<AdminNoShowResult> result = adminNoShowRepository.searchNoShows(
                null, second.getId(), PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AdminNoShowResult::participationId)
                .containsExactly(secondParticipant.getId());
    }

    private Restaurant restaurant() {
        return restaurantRepository.saveAndFlush(
                Restaurant.create(1L, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000));
    }

    private Member member(String suffix) {
        return memberRepository.saveAndFlush(
                Member.createMember(suffix + "@example.com", "hash", "홍길동", "0100000" + suffix));
    }

    private ReservationParticipant noShowParticipant(Restaurant restaurant, Member member, int partySize, Instant processedAt) {
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), 4));
        TimeSlot timeSlot = timeSlotRepository.saveAndFlush(
                TimeSlot.create(table.getId(), NOW.minusSeconds(7200), NOW.minusSeconds(3600)));
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 1L));
        ReservationParticipant participant = participantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), member.getId(), partySize));
        participant.markNoShow();
        participantRepository.saveAndFlush(participant);
        noShowHistoryRepository.saveAndFlush(NoShowHistory.marked(participant.getId(), 1L, processedAt));
        return participant;
    }
}
