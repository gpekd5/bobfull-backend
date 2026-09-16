package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.common.response.PageResponse;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.payment.presentation.response.ExpectedSettlementResponse;
import com.bobfull.payment.presentation.response.SettlementReservationResponse;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Issue #65: {@code Payment.reservation_id}(및 {@code RefundRepository
 * .findAllByPayment_ReservationIdIn}이 조인으로 거치는 같은 컬럼)에 인덱스가 없어 system-wide
 * Payment 규모가 커질수록 전체 스캔이 되는 실제 병목을 실측하고, 인덱스 추가 후 같은 조건으로
 * 재측정해 회귀를 고정하는 통합 테스트다.
 *
 * <p>{@link SettlementQueryCountInvestigationTest}는 예약 건수가 늘어나도 SQL "횟수"가
 * 고정임을 확인했을 뿐, 개별 쿼리의 "지연"이 시스템 전체 Payment 행 수와 무관하게 빠른지는
 * 확인하지 않았다. 이 테스트는 대상 식당의 정산 대상 예약은 소수(40건)로 고정한 채, 그 식당과
 * 무관한 system-wide noise Payment/Refund 행을 대량(10만 건)으로 늘려가며 같은 두 서비스
 * 메서드의 지연을 비교하고, 실제 생성 SQL과 동일한 조건의 EXPLAIN 실행 계획을 확인한다.</p>
 *
 * <p><b>Before(인덱스 추가 전, 실측)</b>: {@code findAllByReservationIdInAndPaidAtIsNotNull}과
 * {@code findAllByPayment_ReservationIdIn}이 거치는 payment 조회 모두 EXPLAIN
 * {@code type=ALL, key=null, rows≈99535}(전체 스캔)이었고, noise 10만 건 적재 후
 * {@code getReservationSettlements} 지연이 2.68배(15.20ms→40.80ms), {@code getExpectedSettlement}이
 * 2.91배(4.60ms→13.40ms) 늘었다. <b>After(이 커밋에서 {@code idx_payment_reservation_id} 추가)</b>:
 * 두 쿼리 모두 {@code type=range, key=idx_payment_reservation_id, rows=40}(대상 예약 건수만큼만
 * 스캔)으로 바뀌었다. 이 테스트는 그 이후 회귀를 막기 위해 두 EXPLAIN 결과가 다시 전체 스캔으로
 * 돌아가지 않는지(type이 ALL이 아닌지)를 고정 assertion으로 남긴다.</p>
 *
 * <p>BOBFULL_MYSQL_PERF_TEST=true 일 때만 실행하며, 개발 DB가 아닌 별도 스키마
 * (BOBFULL_TEST_MYSQL_URL, 예: bobfull_perf_test_scale)를 사용한다. noise 행은
 * RestaurantSearchExplainInvestigationTest(Issue #61 Track A)와 동일한 관례로 JDBC 배치
 * INSERT로 적재한다(Hibernate saveAndFlush 반복은 10만 건 규모에서 비현실적으로 느리다).</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_PERF_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=update",
        "payment.expiration.enabled=false",
        "jwt.secret=settlement-scale-perf-test-secret-key-please-keep-this-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-settlement-scale-perf-test-api-secret",
        "portone.store-id=portone-settlement-scale-perf-test-store-id",
        "portone.webhook-secret=d2hzZWNfc2V0dGxlbWVudC1zY2FsZS1wZXJm"
})
class SettlementQueryScaleInvestigationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long OWNER_MEMBER_ID = 1L;
    private static final int TARGET_RESERVATION_COUNT = 40;

    /** system-wide noise Payment 행 수(다른 식당/예약과 무관한 이력 시뮬레이션). */
    private static final int NOISE_PAYMENT_COUNT = 100_000;
    /** noise Payment 중 Refund가 딸린 비율(1/4 = 실서비스에서 흔한 부분 환불 이력 비율 가정). */
    private static final int NOISE_REFUND_EVERY_N = 4;
    /** target 식당의 실제 reservation_id(작은 auto-increment 값)와 절대 겹치지 않도록 큰 오프셋을 쓴다. */
    private static final long NOISE_RESERVATION_ID_OFFSET = 900_000_000L;
    private static final long NOISE_TIME_SLOT_ID_OFFSET = 800_000_000L;
    private static final int BATCH_SIZE = 1000;
    private static final int MEASURE_ITERATIONS = 5;

    @Autowired private SettlementQueryService settlementQueryService;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private DataSource dataSource;

    @AfterEach
    void cleanUp() {
        // 이 스키마는 이 테스트 전용 throwaway 스키마다. deleteAllInBatch()로 대량 noise 행도
        // 한 번의 DELETE로 빠르게 지운다(기본 deleteAll()은 엔티티를 전부 로드 후 개별 삭제해
        // 10만 건 규모에서 비현실적으로 느리다).
        refundRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        timeSlotRepository.deleteAllInBatch();
        sharedTableRepository.deleteAllInBatch();
        restaurantRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void Payment_reservation_id_인덱스_부재가_system_wide_규모에서_지연에_영향을_주는지_측정한다() throws Exception {
        Fixture fixture = seedTargetRestaurant();

        long smallScaleCount = countPayments();
        System.out.printf("[Issue65-Scale] noise 적재 전 system-wide Payment 행 수=%d%n", smallScaleCount);

        Latency smallLatency = measure(fixture, "noise 적재 전(작은 테이블)");

        long start = System.currentTimeMillis();
        seedSystemWideNoise();
        long seedMs = System.currentTimeMillis() - start;

        long largeScaleCount = countPayments();
        System.out.printf(
                "[Issue65-Scale] noise 적재 완료(%d ms) 후 system-wide Payment 행 수=%d, target 식당 자체 Payment 행 수=%d%n",
                seedMs, largeScaleCount, fixture.reservationIds.size());

        Latency largeLatency = measure(fixture, "noise 적재 후(대규모 테이블)");

        System.out.println("\n[Issue65-Scale] ==== 지연 비교 요약 ====");
        System.out.printf("getReservationSettlements: 작은 테이블 평균=%.2fms, 대규모 테이블 평균=%.2fms (배율=%.2fx)%n",
                smallLatency.reservationSettlementsAvgMs, largeLatency.reservationSettlementsAvgMs,
                largeLatency.reservationSettlementsAvgMs / Math.max(smallLatency.reservationSettlementsAvgMs, 0.001));
        System.out.printf("getExpectedSettlement: 작은 테이블 평균=%.2fms, 대규모 테이블 평균=%.2fms (배율=%.2fx)%n",
                smallLatency.expectedSettlementAvgMs, largeLatency.expectedSettlementAvgMs,
                largeLatency.expectedSettlementAvgMs / Math.max(smallLatency.expectedSettlementAvgMs, 0.001));

        // 결과 정합성: noise 행은 target 식당의 Reservation/TimeSlot과 연결되지 않으므로(예약
        // 자체를 만들지 않음), system-wide 규모가 커져도 응답 내용은 동일해야 한다. 이 assert는
        // 지연(성능)에 대한 것이 아니라 noise 적재가 순수하게 "다른 데이터"이며 대상 식당 정산
        // 결과를 오염시키지 않았음을 확인하는 정합성 체크다.
        assertThat(largeLatency.response.content()).hasSize(smallLatency.response.content().size());
        assertThat(largeLatency.expected.totalPaidAmount()).isEqualByComparingTo(smallLatency.expected.totalPaidAmount());
        assertThat(largeLatency.expected.totalRefundedAmount()).isEqualByComparingTo(smallLatency.expected.totalRefundedAmount());

        explainRepositoryQueries(fixture.reservationIds);
    }

    private Latency measure(Fixture fixture, String label) {
        Pageable pageable = PageRequest.of(0, TARGET_RESERVATION_COUNT);

        // JIT/커넥션 풀 워밍업(측정에서 제외).
        settlementQueryService.getReservationSettlements(
                OWNER_MEMBER_ID, fixture.restaurant.getId(), fixture.startDate, fixture.endDate, pageable);
        settlementQueryService.getExpectedSettlement(
                OWNER_MEMBER_ID, fixture.restaurant.getId(), fixture.startDate, fixture.endDate);

        List<Long> reservationSettlementsMs = new ArrayList<>();
        List<Long> expectedSettlementMs = new ArrayList<>();
        PageResponse<SettlementReservationResponse> lastResponse = null;
        ExpectedSettlementResponse lastExpected = null;

        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long t0 = System.nanoTime();
            lastResponse = settlementQueryService.getReservationSettlements(
                    OWNER_MEMBER_ID, fixture.restaurant.getId(), fixture.startDate, fixture.endDate, pageable);
            long t1 = System.nanoTime();
            reservationSettlementsMs.add((t1 - t0) / 1_000_000L);

            long t2 = System.nanoTime();
            lastExpected = settlementQueryService.getExpectedSettlement(
                    OWNER_MEMBER_ID, fixture.restaurant.getId(), fixture.startDate, fixture.endDate);
            long t3 = System.nanoTime();
            expectedSettlementMs.add((t3 - t2) / 1_000_000L);
        }

        double avgReservationSettlements = reservationSettlementsMs.stream().mapToLong(Long::longValue).average().orElse(0);
        double avgExpectedSettlement = expectedSettlementMs.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.printf("[Issue65-Scale] %s: getReservationSettlements 반복 측정(ms)=%s, 평균=%.2fms%n",
                label, reservationSettlementsMs, avgReservationSettlements);
        System.out.printf("[Issue65-Scale] %s: getExpectedSettlement 반복 측정(ms)=%s, 평균=%.2fms%n",
                label, expectedSettlementMs, avgExpectedSettlement);

        return new Latency(avgReservationSettlements, avgExpectedSettlement, lastResponse, lastExpected);
    }

    private void explainRepositoryQueries(List<Long> targetReservationIds) throws Exception {
        String idList = targetReservationIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        // PaymentRepository.findAllByReservationIdInAndPaidAtIsNotNull이 생성하는 것과 동일한
        // WHERE 조건(payment 단일 테이블, reservation_id IN (...) AND paid_at IS NOT NULL)이다.
        String paymentLookupSql = "SELECT * FROM payment WHERE reservation_id IN (" + idList + ") AND paid_at IS NOT NULL";

        // RefundRepository.findAllByPayment_ReservationIdIn(@EntityGraph payment)이 생성하는
        // 것과 동일한 조인 조건(refund와 payment를 payment_id로 조인 후 payment.reservation_id로
        // 필터)이다.
        String refundJoinSql = "SELECT r.* FROM refund r INNER JOIN payment p ON r.payment_id = p.payment_id "
                + "WHERE p.reservation_id IN (" + idList + ")";

        try (Connection connection = dataSource.getConnection()) {
            List<String> paymentLookupTypes = explain(connection, "PaymentRepository.findAllByReservationIdInAndPaidAtIsNotNull", paymentLookupSql);
            List<String> refundJoinTypes = explain(connection, "RefundRepository.findAllByPayment_ReservationIdIn", refundJoinSql);

            // 회귀 고정: idx_payment_reservation_id 추가 이후 두 쿼리 모두 전체 스캔(type=ALL)으로
            // 돌아가면 안 된다. 인덱스가 삭제·이름 변경되거나 쿼리 형태가 바뀌어 다시 풀스캔이
            // 되면 이 assertion이 실패해 알려준다.
            assertThat(paymentLookupTypes).as("payment 조회가 다시 전체 스캔이 되면 안 된다").doesNotContain("ALL");
            assertThat(refundJoinTypes).as("refund→payment 조인이 다시 전체 스캔이 되면 안 된다").doesNotContain("ALL");
        }
    }

    private List<String> explain(Connection connection, String label, String sql) throws Exception {
        List<String> types = new ArrayList<>();
        System.out.println("\n[Issue65-Scale] ==== EXPLAIN: " + label + " ====");
        System.out.println("SQL: " + sql);
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("EXPLAIN " + sql)) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            while (resultSet.next()) {
                StringBuilder row = new StringBuilder();
                String type = null;
                String key = null;
                String rowsEstimate = null;
                String extra = null;
                for (int col = 1; col <= columnCount; col++) {
                    String columnName = metaData.getColumnLabel(col);
                    String value = resultSet.getString(col);
                    row.append(columnName).append('=').append(value).append(' ');
                    if ("type".equalsIgnoreCase(columnName)) {
                        type = value;
                    } else if ("key".equalsIgnoreCase(columnName)) {
                        key = value;
                    } else if ("rows".equalsIgnoreCase(columnName)) {
                        rowsEstimate = value;
                    } else if ("Extra".equalsIgnoreCase(columnName)) {
                        extra = value;
                    }
                }
                System.out.println(row);
                System.out.printf("[Issue65-Scale] 요약: type=%s, key=%s, rows(추정)=%s, Extra=%s%s%n",
                        type, key, rowsEstimate, extra,
                        "ALL".equalsIgnoreCase(type) ? "  <-- FULL TABLE SCAN" : "");
                types.add(type == null ? "" : type.toUpperCase());
            }
        }
        return types;
    }

    private long countPayments() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM payment")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /** 대상 식당: 실제 정산 조회 대상이 되는 40건의 예약을 엔티티 생성 편의 메서드로 만든다. */
    private Fixture seedTargetRestaurant() {
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(OWNER_MEMBER_ID, "정산성능측정(대규모) 식당", "서울시", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), 4));
        Member member = memberRepository.saveAndFlush(
                Member.createMember("settlement-scale-perf@example.com", "hash", "홍길동", "01099990003"));

        LocalDate startDate = LocalDate.now(SEOUL).plusDays(1);
        LocalDate endDate = startDate.plusDays(2);
        Instant base = startDate.atStartOfDay(SEOUL).toInstant().plusSeconds(3600L);

        List<Long> reservationIds = new ArrayList<>();
        for (int i = 0; i < TARGET_RESERVATION_COUNT; i++) {
            TimeSlot timeSlot = timeSlotRepository.saveAndFlush(TimeSlot.create(
                    table.getId(), base.plusSeconds(i * 1800L), base.plusSeconds(i * 1800L + 900L)));
            Reservation reservation = reservationRepository.saveAndFlush(
                    Reservation.create(timeSlot.getId(), member.getId()));
            reservationIds.add(reservation.getId());
            Payment payment = Payment.createReady(
                    "settlement-scale-" + i,
                    member.getId(), timeSlot.getId(), reservation.getId(), PaymentPurpose.JOIN, 2,
                    BigDecimal.valueOf(30000), base.minusSeconds(600L));
            payment.complete(base.minusSeconds(300L));
            if (i % 2 == 0) {
                payment.markRefunded();
                paymentRepository.saveAndFlush(payment);
                refundRepository.saveAndFlush(Refund.create(payment, BigDecimal.valueOf(30000), RefundStatus.COMPLETED,
                        base.minusSeconds(200L), base.minusSeconds(100L),
                        "settlement-scale-refund-" + i, "test reason"));
            } else {
                paymentRepository.saveAndFlush(payment);
            }
        }

        return new Fixture(restaurant, startDate, endDate, reservationIds);
    }

    /**
     * target 식당과 무관한 system-wide Payment/Refund noise 행을 JDBC 배치 INSERT로 적재한다.
     * reservation_id는 target의 실제(작은) reservation_id와 절대 겹치지 않는 큰 오프셋 범위를 쓴다.
     */
    private void seedSystemWideNoise() throws Exception {
        Instant now = Instant.now();
        String paymentSql = "INSERT INTO payment "
                + "(portone_payment_id, member_id, time_slot_id, reservation_id, payment_purpose, party_size, "
                + "amount, currency, payment_status, expires_at, paid_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'JOIN', 2, 30000.00, 'KRW', 'PAID', ?, ?, ?, ?)";
        String refundSql = "INSERT INTO refund "
                + "(payment_id, amount, refund_status, requested_at, completed_at, idempotency_key, request_reason, "
                + "created_at, updated_at) "
                + "VALUES (?, 30000.00, 'COMPLETED', ?, ?, ?, 'noise', ?, ?)";

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement paymentStatement =
                            connection.prepareStatement(paymentSql, Statement.RETURN_GENERATED_KEYS);
                    PreparedStatement refundStatement = connection.prepareStatement(refundSql)) {
                int pendingRefunds = 0;
                for (int i = 0; i < NOISE_PAYMENT_COUNT; i++) {
                    long reservationId = NOISE_RESERVATION_ID_OFFSET + i;
                    paymentStatement.setString(1, "noise-payment-" + i);
                    paymentStatement.setLong(2, 1_000_000L + i);
                    paymentStatement.setLong(3, NOISE_TIME_SLOT_ID_OFFSET + i);
                    paymentStatement.setLong(4, reservationId);
                    paymentStatement.setTimestamp(5, Timestamp.from(now.minusSeconds(3600L)));
                    paymentStatement.setTimestamp(6, Timestamp.from(now.minusSeconds(1800L)));
                    paymentStatement.setTimestamp(7, Timestamp.from(now));
                    paymentStatement.setTimestamp(8, Timestamp.from(now));
                    paymentStatement.executeUpdate();

                    if (i % NOISE_REFUND_EVERY_N == 0) {
                        long paymentId;
                        try (ResultSet keys = paymentStatement.getGeneratedKeys()) {
                            keys.next();
                            paymentId = keys.getLong(1);
                        }
                        refundStatement.setLong(1, paymentId);
                        refundStatement.setTimestamp(2, Timestamp.from(now.minusSeconds(1200L)));
                        refundStatement.setTimestamp(3, Timestamp.from(now.minusSeconds(600L)));
                        refundStatement.setString(4, "noise-refund-" + i);
                        refundStatement.setTimestamp(5, Timestamp.from(now));
                        refundStatement.setTimestamp(6, Timestamp.from(now));
                        refundStatement.addBatch();
                        pendingRefunds++;
                    }

                    if (i % BATCH_SIZE == BATCH_SIZE - 1) {
                        if (pendingRefunds > 0) {
                            refundStatement.executeBatch();
                            pendingRefunds = 0;
                        }
                        connection.commit();
                    }
                }
                if (pendingRefunds > 0) {
                    refundStatement.executeBatch();
                }
                connection.commit();
            }
        }
    }

    private record Fixture(Restaurant restaurant, LocalDate startDate, LocalDate endDate, List<Long> reservationIds) {
    }

    private record Latency(double reservationSettlementsAvgMs, double expectedSettlementAvgMs,
                            PageResponse<SettlementReservationResponse> response, ExpectedSettlementResponse expected) {
    }
}
