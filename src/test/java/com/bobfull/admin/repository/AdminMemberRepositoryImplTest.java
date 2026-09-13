package com.bobfull.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.admin.dto.AdminMemberResult;
import com.bobfull.member.domain.entity.MemberRole;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
class AdminMemberRepositoryImplTest {

    @Autowired private MemberRepository memberRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;

    @Test
    void 키워드로_이름_또는_이메일을_검색한다() {
        memberRepository.save(Member.createMember("hong@example.com", "hash", "홍길동", "01011112222"));
        memberRepository.save(Member.createMember("kim@example.com", "hash", "김철수", "01033334444"));

        var result = memberRepository.searchMembers("홍길동", null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AdminMemberResult::name).containsExactly("홍길동");
    }

    @Test
    void role로_필터링한다() {
        memberRepository.save(Member.createMember("member@example.com", "hash", "회원", "01011112222"));
        memberRepository.save(Member.createOwner("owner@example.com", "hash", "사장", "01033334444", "1234567890"));

        var result = memberRepository.searchMembers(null, MemberRole.OWNER, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AdminMemberResult::role).containsExactly(MemberRole.OWNER);
    }

    @Test
    void noShowCount은_NO_SHOW_참여자_건수로_계산된다() {
        Member member = memberRepository.save(Member.createMember("noshow@example.com", "hash", "노쇼자", "01011112222"));
        Restaurant restaurant = restaurantRepository.save(
                Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot timeSlot = timeSlotRepository.save(TimeSlot.create(
                table.getId(), Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        Reservation reservation = reservationRepository.save(Reservation.create(timeSlot.getId(), member.getId()));
        ReservationParticipant participant = participantRepository.save(
                ReservationParticipant.create(reservation.getId(), member.getId(), 2));
        ReflectionTestUtils.setField(participant, "participationStatus", ParticipationStatus.NO_SHOW);
        participantRepository.saveAndFlush(participant);

        Optional<AdminMemberResult> result = memberRepository.findMemberDetail(member.getId());

        assertThat(result).isPresent();
        assertThat(result.get().noShowCount()).isEqualTo(1L);
    }

    @Test
    void deleted_필터가_true이면_소프트_삭제된_회원만_반환한다() {
        Member active = memberRepository.save(Member.createMember("active@example.com", "hash", "활성", "01011112222"));
        Member deleted = memberRepository.save(Member.createMember("deleted@example.com", "hash", "탈퇴", "01033334444"));
        ReflectionTestUtils.setField(deleted, "deletedAt", Instant.parse("2026-08-01T00:00:00Z"));
        memberRepository.saveAndFlush(deleted);

        var result = memberRepository.searchMembers(null, null, true, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AdminMemberResult::memberId).containsExactly(deleted.getId());
    }

    @Test
    void 존재하지_않는_memberId_상세조회는_빈_결과를_반환한다() {
        Optional<AdminMemberResult> result = memberRepository.findMemberDetail(999L);

        assertThat(result).isEmpty();
    }
}
