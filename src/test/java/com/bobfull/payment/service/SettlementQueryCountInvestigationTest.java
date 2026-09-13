package com.bobfull.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.common.response.PageResponse;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.payment.dto.ExpectedSettlementResponse;
import com.bobfull.payment.dto.SettlementReservationResponse;
import com.bobfull.payment.entity.Payment;
import com.bobfull.payment.entity.PaymentPurpose;
import com.bobfull.payment.entity.Refund;
import com.bobfull.payment.entity.RefundStatus;
import com.bobfull.payment.repository.PaymentRepository;
import com.bobfull.payment.repository.RefundRepository;
import com.bobfull.reservation.domain.entity.Reservation;
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
import java.time.ZoneId;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import jakarta.persistence.EntityManagerFactory;

/**
 * Issue #65: 정산 조회(SettlementQueryService)의 실제 SQL 실행 횟수를 Hibernate Statistics로
 * 측정하는 선택적 통합 테스트다. BOBFULL_MYSQL_PERF_TEST=true 일 때만 실행하며, 개발 DB가 아닌
 * 별도 스키마(BOBFULL_TEST_MYSQL_URL)를 사용한다.
 *
 * <p>이 테스트는 Issue #61 Track B의 {@code AvailableDiningSessionQueryCountInvestigationTest}와
 * 동일한 관례를 따른다. 목적은 개선 전/후 비교가 아니라, 정산 목록 조회(getReservationSettlements)와
 * 정산 총액 조회(getExpectedSettlement)가 각각 몇 개의 SQL을 실행하는지, 그리고 그 값이 예약 건수가
 * 늘어나도 고정인지(배치 조회로 묶여 있는지) 실측으로 확인하는 것이다.</p>
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
        "jwt.secret=settlement-query-perf-test-secret-key-please-keep-this-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-settlement-query-perf-test-api-secret",
        "portone.store-id=portone-settlement-query-perf-test-store-id",
        "portone.webhook-secret=d2hzZWNfc2V0dGxlbWVudC1xdWVyeS1wZXJm"
})
class SettlementQueryCountInvestigationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long OWNER_MEMBER_ID = 1L;

    @Autowired private SettlementQueryService settlementQueryService;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void cleanUp() {
        refundRepository.deleteAll();
        paymentRepository.deleteAll();
        reservationRepository.deleteAll();
        timeSlotRepository.deleteAll();
        sharedTableRepository.deleteAll();
        restaurantRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 정산목록조회는_예약건수와_무관하게_고정된_SQL_횟수를_실행한다() {
        int reservationCount = 20;
        Fixture fixture = seed(reservationCount);

        Statistics statistics = statistics();
        statistics.clear();

        Pageable pageable = PageRequest.of(0, reservationCount);
        PageResponse<SettlementReservationResponse> response = settlementQueryService.getReservationSettlements(
                OWNER_MEMBER_ID, fixture.restaurant.getId(), fixture.startDate, fixture.endDate, pageable);

        long queryCount = statistics.getPrepareStatementCount();
        System.out.printf(
                "[Issue65] 정산목록 조회(예약 %d건)에 실행된 SQL PreparedStatement 수=%d%n",
                reservationCount, queryCount);

        assertThat(response.content()).hasSize(reservationCount);
        // Issue #65(Before, 실측): 식당 소유 검증(1) + findSettlementReservations의 content/count
        // 쿼리(2, Page<Reservation> 반환에 Spring Data가 count 쿼리를 자동 추가) + TimeSlot 배치
        // 조회(1) + Payment 배치 조회(1) + Refund 배치 조회(1) = 6. 예약 건수가 늘어나도(N+1이
        // 아니라 고정 배치 조회 구조라) 이 값은 그대로여야 한다.
        //
        // 주의(실측 중 발견): Spring Data JPA는 content 건수가 요청한 pageSize보다 적으면(즉
        // 마지막 페이지임이 content만으로 확정되면) count 쿼리를 생략하는 최적화를 적용한다.
        // 여기서는 SettlementController의 실제 기본 페이지 크기(@PageableDefault size=20)와
        // 동일하게 pageSize=reservationCount(20)로 맞춰 content가 페이지를 정확히 채우게 해
        // count 쿼리가 항상 실행되는 경로를 고정했다. pageSize를 reservationCount보다 크게 주면
        // (예: +10) count 쿼리가 생략되어 5회로 줄어드는 것을 별도로 확인했다 — 즉 이 5~6이라는
        // 수치는 호출자가 넘기는 pageSize와 실제 건수의 관계에 따라 달라질 수 있다.
        assertThat(queryCount).isEqualTo(6L);
    }

    @Test
    void 정산총액조회는_예약건수가_늘어나도_단일_집계쿼리로_고정된다() {
        int reservationCount = 50;
        Fixture fixture = seed(reservationCount);

        Statistics statistics = statistics();
        statistics.clear();

        ExpectedSettlementResponse response = settlementQueryService.getExpectedSettlement(
                OWNER_MEMBER_ID, fixture.restaurant.getId(), fixture.startDate, fixture.endDate);

        long queryCount = statistics.getPrepareStatementCount();
        System.out.printf(
                "[Issue65] 정산총액 조회(예약 %d건)에 실행된 SQL PreparedStatement 수=%d%n",
                reservationCount, queryCount);

        assertThat(response.totalPaidAmount()).isPositive();
        // Issue #65(Before, 실측): 식당 소유 검증(1) + paymentRepository.sumSettlementAmounts의
        // 단일 집계 쿼리(1) = 2. Payment/Refund를 reservation 단위로 반복 조회하지 않고 DB 집계
        // 함수(sum)로 계산하므로, reservationCount를 20에서 50으로 늘려도 이 값은 그대로여야 한다.
        assertThat(queryCount).isEqualTo(2L);
    }

    private Fixture seed(int reservationCount) {
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(OWNER_MEMBER_ID, "정산성능측정 식당", "서울시", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), 4));
        Member member = memberRepository.saveAndFlush(
                Member.createMember("settlement-perf-query@example.com", "hash", "홍길동", "01099990002"));

        LocalDate startDate = LocalDate.now(SEOUL).plusDays(1);
        LocalDate endDate = startDate.plusDays(2);
        Instant base = startDate.atStartOfDay(SEOUL).toInstant().plusSeconds(3600L);

        for (int i = 0; i < reservationCount; i++) {
            TimeSlot timeSlot = timeSlotRepository.saveAndFlush(TimeSlot.create(
                    table.getId(), base.plusSeconds(i * 1800L), base.plusSeconds(i * 1800L + 900L)));
            Reservation reservation = reservationRepository.saveAndFlush(
                    Reservation.create(timeSlot.getId(), member.getId()));
            Payment payment = Payment.createReady(
                    "settlement-perf-" + reservationCount + "-" + i,
                    member.getId(), timeSlot.getId(), reservation.getId(), PaymentPurpose.JOIN, 2,
                    BigDecimal.valueOf(30000), base.minusSeconds(600L));
            payment.complete(base.minusSeconds(300L));
            if (i % 2 == 0) {
                payment.markRefunded();
                paymentRepository.saveAndFlush(payment);
                refundRepository.saveAndFlush(Refund.create(payment, BigDecimal.valueOf(30000), RefundStatus.COMPLETED,
                        base.minusSeconds(200L), base.minusSeconds(100L),
                        "settlement-perf-refund-" + reservationCount + "-" + i, "test reason"));
            } else {
                paymentRepository.saveAndFlush(payment);
            }
        }

        return new Fixture(restaurant, startDate, endDate);
    }

    private Statistics statistics() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        return sessionFactory.getStatistics();
    }

    private record Fixture(Restaurant restaurant, LocalDate startDate, LocalDate endDate) {
    }
}
