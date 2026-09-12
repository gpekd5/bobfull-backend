package com.bobfull.timeslot.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.member.entity.Member;
import com.bobfull.member.repository.MemberRepository;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.entity.Restaurant;
import com.bobfull.restaurant.repository.RestaurantRepository;
import com.bobfull.sharedtable.entity.SharedTable;
import com.bobfull.sharedtable.repository.SharedTableRepository;
import com.bobfull.timeslot.dto.AvailableDiningSessionListResponse;
import com.bobfull.timeslot.entity.TimeSlot;
import com.bobfull.timeslot.repository.TimeSlotRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Issue #61 Track B: 예약 가능 회차 조회(getAvailableDiningSessions)의 실제 SQL 실행 횟수를
 * Hibernate Statistics로 측정하는 선택적 통합 테스트다. BOBFULL_MYSQL_PERF_TEST=true 일 때만
 * 실행하며, 개발 DB가 아닌 별도 스키마(BOBFULL_TEST_MYSQL_URL)를 사용한다.
 *
 * <p>수정 전(Before, Issue #61)에는 TimeSlotService.toAvailableDiningSessionResponse와
 * AvailableCapacityCalculator.calculate가 동일한 활성 Reservation 조회·참여자 합계 조회를 각각
 * 독립적으로 실행해 TimeSlot 20건에 123개 쿼리(평균 6.15개/TimeSlot)가 발생했다.
 * AvailableCapacityCalculator.calculateWithKnownParticipantCount로 중복 조회를 제거한 뒤에는
 * 83개 쿼리(평균 4.15개/TimeSlot, 3 + TIME_SLOT_COUNT*4)로 줄었다
 * (docs/110-records/evidence/v3/61-search-query/README.md).</p>
 *
 * <p>Issue #235에서는 회차별 반복 조회 4종(활성 예약·참여자 합계·CLOSED 여부·READY 선점 합계)을
 * 전부 배치 쿼리로 바꿔, 쿼리 수가 TimeSlot 건수와 무관한 고정값(7)이 되도록 다시 줄였다
 * (docs/110-records/evidence/v3/restaurant-view-hotpath/README.md). 이 테스트는 그 개선이 되돌아가지
 * 않도록 고정한다.</p>
 *
 * <p>{@code spring.jpa.hibernate.ddl-auto=update}이므로 대상 스키마의 기존 테이블을 지우지 않는다.
 * 그래도 개발 DB가 아닌 별도 스키마를 가리켜야 한다(seed/cleanUp이 restaurant 등 공용 테이블을
 * 전체 삭제한다).</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_PERF_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "payment.expiration.enabled=false",
        "jwt.secret=search-query-perf-test-secret-key-please-keep-this-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-search-query-perf-test-api-secret",
        "portone.store-id=portone-search-query-perf-test-store-id",
        "portone.webhook-secret=d2hzZWNfc2VhcmNoLXF1ZXJ5LXBlcmY="
})
class AvailableDiningSessionQueryCountInvestigationTest {

    private static final int TIME_SLOT_COUNT = 20;

    @Autowired private TimeSlotService timeSlotService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void cleanUp() {
        participantRepository.deleteAll();
        reservationRepository.deleteAll();
        timeSlotRepository.deleteAll();
        sharedTableRepository.deleteAll();
        restaurantRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 회차별_활성예약_참여자합계_조회가_TimeSlotService와_AvailableCapacityCalculator에서_중복_실행되지_않는다() {
        Long ownerMemberId = 1L;
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(ownerMemberId, "성능측정 식당", "제주시", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), 4));
        Member member = memberRepository.saveAndFlush(
                Member.createMember("perf-query-count@example.com", "hash", "홍길동", "01099990001"));

        LocalDate targetDate = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        Instant base = targetDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().plusSeconds(3600L);
        for (int i = 0; i < TIME_SLOT_COUNT; i++) {
            TimeSlot timeSlot = timeSlotRepository.saveAndFlush(
                    TimeSlot.create(table.getId(), base.plusSeconds(i * 3600L), base.plusSeconds(i * 3600L + 1800L)));
            Reservation reservation = reservationRepository.saveAndFlush(
                    Reservation.create(timeSlot.getId(), member.getId()));
            participantRepository.saveAndFlush(
                    ReservationParticipant.create(reservation.getId(), member.getId(), 2));
        }

        Statistics statistics = statistics();
        statistics.clear();

        AvailableDiningSessionListResponse response = timeSlotService.getAvailableDiningSessions(
                restaurant.getId(), targetDate, null);

        long queryCount = statistics.getPrepareStatementCount();
        System.out.printf(
                "[Issue61-TrackB] TimeSlot=%d 건 조회에 실행된 SQL PreparedStatement 수=%d (TimeSlot당 평균=%.2f)%n",
                TIME_SLOT_COUNT, queryCount, (double) queryCount / TIME_SLOT_COUNT);

        assertThat(response.content()).hasSize(TIME_SLOT_COUNT);
        // Issue #235(After): 활성 예약·CLOSED 여부·참여자 합계·READY 선점 합계를 TimeSlot 건수와
        // 무관하게 각각 배치 쿼리 1회로 묶었다. 외부 조회(식당·SharedTable·TimeSlot 목록) 3회 +
        // 배치 조회 4회 = 7회로, TIME_SLOT_COUNT를 20에서 아무리 늘려도 이 값은 그대로여야 한다.
        assertThat(queryCount).isEqualTo(7L);
    }

    private Statistics statistics() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        return sessionFactory.getStatistics();
    }
}
