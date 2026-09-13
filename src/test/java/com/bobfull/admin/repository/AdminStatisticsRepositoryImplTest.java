package com.bobfull.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.admin.dto.AdminMemberNoShowRateResult;
import com.bobfull.admin.dto.AdminRestaurantStatisticsResult;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
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
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AdminStatisticsRepositoryImpl은 다른 JpaRepository에 합성되는 Fragment가 아니라 별도 Bean이라
 * @DataJpaTest의 기본 스캔 대상이 아니다 — @Import로 명시해 EntityManager를 주입받게 한다.
 */
@DataJpaTest
@Import(AdminStatisticsRepositoryImpl.class)
class AdminStatisticsRepositoryImplTest {

    @Autowired private AdminStatisticsRepository adminStatisticsRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;

    @Test
    void 식당별로_전체_예약수와_확정_예약수를_집계한다() {
        Restaurant restaurant = restaurantRepository.save(
                Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot timeSlot = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        Reservation confirmed = reservationRepository.save(Reservation.create(timeSlot.getId(), 10L));
        confirmed.confirm();
        reservationRepository.saveAndFlush(confirmed);
        TimeSlot timeSlot2 = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-01T12:00:00Z"), Instant.parse("2026-08-01T14:00:00Z")));
        reservationRepository.save(Reservation.create(timeSlot2.getId(), 11L));

        var result = adminStatisticsRepository.aggregateRestaurantStatistics(null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        AdminRestaurantStatisticsResult item = result.getContent().get(0);
        assertThat(item.totalReservationCount()).isEqualTo(2);
        assertThat(item.confirmedReservationCount()).isEqualTo(1);
    }

    @Test
    void 회원별로_노쇼_건수를_집계하고_취소된_참여는_제외한다() {
        Member member = memberRepository.save(Member.createMember("member@example.com", "hash", "홍길동", "01011112222"));
        Restaurant restaurant = restaurantRepository.save(
                Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot timeSlot = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        Reservation reservation1 = reservationRepository.save(Reservation.create(timeSlot.getId(), member.getId()));
        ReservationParticipant noShow = participantRepository.save(
                ReservationParticipant.create(reservation1.getId(), member.getId(), 1));
        ReflectionTestUtils.setField(noShow, "participationStatus", ParticipationStatus.NO_SHOW);
        participantRepository.saveAndFlush(noShow);

        TimeSlot timeSlot2 = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-02T09:00:00Z"), Instant.parse("2026-08-02T11:00:00Z")));
        Reservation reservation2 = reservationRepository.save(Reservation.create(timeSlot2.getId(), member.getId()));
        ReservationParticipant cancelled = participantRepository.save(
                ReservationParticipant.create(reservation2.getId(), member.getId(), 1));
        ReflectionTestUtils.setField(cancelled, "participationStatus", ParticipationStatus.CANCELLED);
        participantRepository.saveAndFlush(cancelled);

        var result = adminStatisticsRepository.aggregateMemberNoShowRates(PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        AdminMemberNoShowRateResult item = result.getContent().get(0);
        assertThat(item.memberId()).isEqualTo(member.getId());
        assertThat(item.totalReservationCount()).isEqualTo(1);
        assertThat(item.noShowCount()).isEqualTo(1);
    }
}
