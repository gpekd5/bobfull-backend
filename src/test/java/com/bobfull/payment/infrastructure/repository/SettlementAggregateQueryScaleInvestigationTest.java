package com.bobfull.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.payment.presentation.response.ExpectedSettlementResponse;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.application.service.SettlementQueryService;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Issue #65: {@code PaymentRepository.sumSettlementAmounts}(3-way join: payment ⋈ time_slot
 * ⋈ shared_table, refund은 LEFT JOIN)가 system-wide 규모가 커질수록 실제로 병목이 되는지
 * 실측하고, 인덱스 추가 후 재측정해 회귀를 고정하는 통합 테스트다.
 *
 * <p>이 쿼리가 거치는 조인 경로는 앞선 두 Issue #65 커밋과 다르다.
 * {@code idx_payment_reservation_id}는 {@code PaymentRepository
 * .findAllByReservationIdInAndPaidAtIsNotNull}이 거치는 {@code payment.reservation_id}를,
 * {@code idx_reservation_time_slot_id}는 {@code ReservationRepository
 * .findSettlementReservations}가 거치는 {@code reservation.time_slot_id}를 고쳤을 뿐,
 * {@code sumSettlementAmounts}가 조인 키로 쓰는 {@code payment.time_slot_id}에는 여전히
 * 인덱스가 없었다. 실제 AWS 부하 테스트에서 이 엔드포인트 하나만 20 req/s로 호출했는데도
 * median 6.63s / p95 7.51s / p99 7.67s의 지연이 나온 것이 이 세 번째 결함이다.</p>
 *
 * <p><b>Before(인덱스 추가 전, 실측)</b>: {@code payment} 테이블이 EXPLAIN
 * {@code type=ALL, key=null}(전체 스캔)이었고, system-wide noise payment
 * 약 13,860건 적재 후 {@code getExpectedSettlement} 지연이 뚜렷하게 늘었다. <b>After(이 커밋에서
 * {@code idx_payment_time_slot_id} 추가)</b>: {@code payment}가 {@code type=ref,
 * key=idx_payment_time_slot_id}로 바뀌어 noise 규모가 커져도 지연이 늘지 않았다. 이 테스트는
 * 그 이후 회귀를 막기 위해 {@code payment}의 EXPLAIN이 다시 전체 스캔으로 돌아가지 않는지를
 * 고정 assertion으로 남긴다.</p>
 *
 * <p>이 테스트는 대상 식당의 실제 정산 대상 Payment는 소수(40건, 절반은 COMPLETED Refund)로
 * 고정한 채, 그 식당과 무관한 다른 식당들의 shared_table/time_slot/payment 행을 system-wide
 * 규모(noise payment 약 13,860건, 실제 AWS 인스턴스에 누적된 약 13,861건과 비슷한 규모)로
 * 늘려가며 {@code getExpectedSettlement}의 지연과, {@code sumSettlementAmounts}와 동일한 SQL의
 * EXPLAIN 실행 계획을 before/after 비교한다. noise Payment는 {@code PaymentPurpose.CREATE}로
 * 만들어 reservation_id가 필요 없다 — 이 쿼리는 reservation 테이블을 전혀 거치지 않기 때문이다.</p>
 *
 * <p>BOBFULL_MYSQL_PERF_TEST=true 일 때만 실행하며, 개발 DB가 아닌 별도 스키마
 * (BOBFULL_TEST_MYSQL_URL, 예: bobfull_perf_test_settlement_agg)를 사용한다. noise 행은
 * {@code SettlementQueryScaleInvestigationTest}/{@code SettlementReservationQueryScaleInvestigationTest}와
 * 동일한 관례로 JDBC 배치 INSERT로 적재한다.</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_PERF_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=update",
        "payment.expiration.enabled=false",
        "jwt.secret=settlement-agg-scale-perf-test-secret-key-please-keep-this-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-settlement-agg-scale-perf-test-api-secret",
        "portone.store-id=portone-settlement-agg-scale-perf-test-store-id",
        "portone.webhook-secret=d2hzZWNfc2V0dGxlbWVudC1hZ2ctc2NhbGUtcGVyZg=="
})
class SettlementAggregateQueryScaleInvestigationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long OWNER_MEMBER_ID = 1L;
    private static final int TARGET_PAYMENT_COUNT = 40;
    private static final BigDecimal TARGET_PAYMENT_AMOUNT = BigDecimal.valueOf(30000);

    /** system-wide noise 다른 식당(=shared_table restaurant_id) 수. */
    private static final int NOISE_SHARED_TABLE_COUNT = 1386;
    /** shared_table 1개당 만드는 time_slot(=payment) 수.
     * 총 noise payment 행 = 1,386 * 10 = 13,860건 — 실제 AWS 인스턴스에 누적된
     * 약 13,861건과 비슷한 규모(인위적으로 부풀리지 않은 현실적 규모)다. */
    private static final int TIME_SLOTS_PER_SHARED_TABLE = 10;
    /** noise Payment 중 Refund가 딸린 비율(1/4 = 실서비스에서 흔한 부분 환불 이력 비율 가정). */
    private static final int NOISE_REFUND_EVERY_N = 4;
    /** target 식당의 실제 restaurant_id/shared_table_id/time_slot_id(작은 auto-increment 값)와
     *  절대 겹치지 않도록 큰 오프셋을 쓴다. */
    private static final long NOISE_RESTAURANT_ID_OFFSET = 900_000_000L;
    private static final long NOISE_SHARED_TABLE_ID_OFFSET = 900_000_000L;
    private static final long NOISE_TIME_SLOT_ID_OFFSET = 900_000_000L;
    private static final int BATCH_SIZE = 1000;
    private static final int MEASURE_ITERATIONS = 5;

    @Autowired private SettlementQueryService settlementQueryService;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private DataSource dataSource;

    @AfterEach
    void cleanUp() {
        // 이 스키마는 이 테스트 전용 throwaway 스키마다. deleteAllInBatch()는 WHERE 없이
        // 테이블 전체를 지우므로, 엔티티로 만든 target 행과 JDBC로 직접 넣은 noise 행을
        // 구분하지 않고 한 번에 지운다(10,000+ 건 규모에서 deleteAll()보다 훨씬 빠르다).
        refundRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        timeSlotRepository.deleteAllInBatch();
        sharedTableRepository.deleteAllInBatch();
        restaurantRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void Payment_time_slot_id_인덱스_부재가_system_wide_규모에서_지연에_영향을_주는지_측정한다() throws Exception {
        Fixture fixture = seedTargetRestaurant();

        long smallScalePaymentCount = countRows("payment");
        System.out.printf("[Issue65-Agg-Scale] noise 적재 전 system-wide payment 행 수=%d%n", smallScalePaymentCount);

        Latency smallLatency = measure(fixture, "noise 적재 전(작은 테이블)");
        List<ExplainRow> smallExplain = explainSumSettlementAmounts(fixture, "noise 적재 전(작은 테이블)");

        long start = System.currentTimeMillis();
        seedSystemWideNoise();
        long seedMs = System.currentTimeMillis() - start;

        long largeScalePaymentCount = countRows("payment");
        System.out.printf(
                "[Issue65-Agg-Scale] noise 적재 완료(%d ms) 후 system-wide payment 행 수=%d, target 식당 자체 Payment 행 수=%d%n",
                seedMs, largeScalePaymentCount, TARGET_PAYMENT_COUNT);

        Latency largeLatency = measure(fixture, "noise 적재 후(대규모 테이블)");
        List<ExplainRow> largeExplain = explainSumSettlementAmounts(fixture, "noise 적재 후(대규모 테이블)");

        System.out.println("\n[Issue65-Agg-Scale] ==== 지연 비교 요약 ====");
        System.out.printf("getExpectedSettlement: 작은 테이블 평균=%.2fms, 대규모 테이블 평균=%.2fms (배율=%.2fx)%n",
                smallLatency.avgMs, largeLatency.avgMs, largeLatency.avgMs / Math.max(smallLatency.avgMs, 0.001));

        System.out.println("\n[Issue65-Agg-Scale] ==== EXPLAIN 비교(작은 테이블 vs 대규모 테이블) ====");
        System.out.println("작은 테이블: " + smallExplain);
        System.out.println("대규모 테이블: " + largeExplain);

        // 결과 정합성: noise 행은 target 식당과 무관한 restaurant_id/shared_table_id를 쓰므로,
        // system-wide 규모가 커져도 대상 식당의 지급 예정 금액은 동일해야 한다. 이 assert는
        // 지연(성능)에 대한 것이 아니라 noise 적재가 순수하게 "다른 데이터"이며 대상 식당 정산
        // 결과를 오염시키지 않았음을 확인하는 정합성 체크다.
        assertThat(largeLatency.response.totalPaidAmount()).isEqualByComparingTo(smallLatency.response.totalPaidAmount());
        assertThat(largeLatency.response.totalRefundedAmount()).isEqualByComparingTo(smallLatency.response.totalRefundedAmount());
        assertThat(largeLatency.response.expectedSettlementAmount()).isEqualByComparingTo(smallLatency.response.expectedSettlementAmount());

        // 회귀 고정: idx_payment_time_slot_id 추가 이후 payment 테이블이 다시 전체
        // 스캔(type=ALL)으로 나오면 안 된다. 인덱스가 삭제·이름 변경되거나 쿼리 형태가 바뀌어
        // 다시 풀스캔이 되면 이 assertion이 실패해 알려준다.
        List<String> largePaymentTypes = largeExplain.stream()
                .filter(row -> "p".equalsIgnoreCase(row.table()))
                .map(ExplainRow::type)
                .toList();
        assertThat(largePaymentTypes).as("payment 테이블 조회가 다시 전체 스캔이 되면 안 된다").doesNotContain("ALL");
    }

    private Latency measure(Fixture fixture, String label) {
        // JIT/커넥션 풀 워밍업(측정에서 제외).
        settlementQueryService.getExpectedSettlement(
                OWNER_MEMBER_ID, fixture.restaurant.getId(), fixture.startDate, fixture.endDate);

        List<Long> ms = new ArrayList<>();
        ExpectedSettlementResponse lastResponse = null;

        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long t0 = System.nanoTime();
            lastResponse = settlementQueryService.getExpectedSettlement(
                    OWNER_MEMBER_ID, fixture.restaurant.getId(), fixture.startDate, fixture.endDate);
            long t1 = System.nanoTime();
            ms.add((t1 - t0) / 1_000_000L);
        }

        double avg = ms.stream().mapToLong(Long::longValue).average().orElse(0);
        System.out.printf("[Issue65-Agg-Scale] %s: getExpectedSettlement 반복 측정(ms)=%s, 평균=%.2fms%n", label, ms, avg);

        return new Latency(avg, lastResponse);
    }

    /**
     * {@code PaymentRepository.sumSettlementAmounts}가 생성하는 것과 동일한 SQL(payment ⋈
     * time_slot ⋈ shared_table, refund는 LEFT JOIN, restaurant_id + paid_at not null +
     * start_at 범위 필터)의 EXPLAIN을 확인한다. 4-way join이므로 EXPLAIN 결과에 테이블별로
     * 여러 행이 나올 수 있어 전부 수집한다.
     */
    private List<ExplainRow> explainSumSettlementAmounts(Fixture fixture, String label) throws Exception {
        Instant startAt = fixture.startDate.atStartOfDay(SEOUL).toInstant();
        Instant endAt = fixture.endDate.plusDays(1).atStartOfDay(SEOUL).toInstant();
        String sql = "SELECT p.* FROM payment p "
                + "JOIN time_slot ts ON p.time_slot_id = ts.time_slot_id "
                + "JOIN shared_table st ON ts.shared_table_id = st.shared_table_id "
                + "LEFT JOIN refund f ON f.payment_id = p.payment_id "
                + "WHERE st.restaurant_id = " + fixture.restaurant.getId()
                + " AND p.paid_at IS NOT NULL"
                + " AND ts.start_at >= '" + Timestamp.from(startAt) + "'"
                + " AND ts.start_at < '" + Timestamp.from(endAt) + "'";

        List<ExplainRow> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            System.out.println("\n[Issue65-Agg-Scale] ==== EXPLAIN: sumSettlementAmounts(" + label + ") ====");
            System.out.println("SQL: " + sql);
            try (ResultSet resultSet = statement.executeQuery("EXPLAIN " + sql)) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (resultSet.next()) {
                    StringBuilder row = new StringBuilder();
                    String table = null;
                    String type = null;
                    String key = null;
                    String rowsEstimate = null;
                    String extra = null;
                    for (int col = 1; col <= columnCount; col++) {
                        String columnName = metaData.getColumnLabel(col);
                        String value = resultSet.getString(col);
                        row.append(columnName).append('=').append(value).append(' ');
                        if ("table".equalsIgnoreCase(columnName)) {
                            table = value;
                        } else if ("type".equalsIgnoreCase(columnName)) {
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
                    System.out.printf("[Issue65-Agg-Scale] 요약: table=%s, type=%s, key=%s, rows(추정)=%s, Extra=%s%s%n",
                            table, type, key, rowsEstimate, extra,
                            "ALL".equalsIgnoreCase(type) ? "  <-- FULL TABLE SCAN" : "");
                    rows.add(new ExplainRow(table, type == null ? "" : type.toUpperCase(), key, rowsEstimate, extra));
                }
            }
        }
        return rows;
    }

    private long countRows(String tableName) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /**
     * 대상 식당: 실제 {@code getExpectedSettlement} 조회 대상이 되는 40건의 Payment를 엔티티
     * 생성 편의 메서드로 만든다(절반은 COMPLETED Refund가 딸림). {@code sumSettlementAmounts}는
     * reservation 테이블을 전혀 거치지 않으므로 {@code PaymentPurpose.CREATE}(reservationId
     * 불필요)로 만들어 Reservation 엔티티 생성을 생략한다.
     */
    private Fixture seedTargetRestaurant() {
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(OWNER_MEMBER_ID, "정산성능측정(Aggregate) 식당", "서울시", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), 4));
        Member member = memberRepository.saveAndFlush(
                Member.createMember("settlement-agg-scale-perf@example.com", "hash", "홍길동", "01099990005"));

        LocalDate startDate = LocalDate.now(SEOUL).plusDays(1);
        LocalDate endDate = startDate.plusDays(2);
        Instant base = startDate.atStartOfDay(SEOUL).toInstant().plusSeconds(3600L);

        for (int i = 0; i < TARGET_PAYMENT_COUNT; i++) {
            TimeSlot timeSlot = timeSlotRepository.saveAndFlush(TimeSlot.create(
                    table.getId(), base.plusSeconds(i * 1800L), base.plusSeconds(i * 1800L + 900L)));
            Payment payment = Payment.createReady(
                    "settlement-agg-scale-" + i,
                    member.getId(), timeSlot.getId(), null, PaymentPurpose.CREATE, 2,
                    TARGET_PAYMENT_AMOUNT, base.minusSeconds(600L));
            payment.complete(base.minusSeconds(300L));
            if (i % 2 == 0) {
                payment.markRefunded();
                paymentRepository.saveAndFlush(payment);
                refundRepository.saveAndFlush(Refund.create(payment, TARGET_PAYMENT_AMOUNT, RefundStatus.COMPLETED,
                        base.minusSeconds(200L), base.minusSeconds(100L),
                        "settlement-agg-scale-refund-" + i, "test reason"));
            } else {
                paymentRepository.saveAndFlush(payment);
            }
        }

        return new Fixture(restaurant, startDate, endDate);
    }

    /**
     * target 식당과 무관한 system-wide shared_table/time_slot/payment noise 행을 JDBC 배치
     * INSERT로 적재한다. restaurant_id/shared_table_id/time_slot_id는 target의 실제(작은) 값과
     * 절대 겹치지 않는 큰 오프셋 범위를 쓴다. shared_table/time_slot은 명시적 ID로 배치
     * INSERT하고, payment는 refund의 FK로 생성 키가 즉시 필요해 행 단위 executeUpdate +
     * Statement.RETURN_GENERATED_KEYS로 넣는다(SettlementQueryScaleInvestigationTest와 동일한
     * 관례). time_slot.start_at은 넓은 날짜 범위(과거 1년~미래 1년)에 걸쳐 분산시켜
     * "system-wide 전체 기간"을 시뮬레이션한다.
     */
    private void seedSystemWideNoise() throws Exception {
        Instant now = Instant.now();
        String sharedTableSql = "INSERT INTO shared_table "
                + "(shared_table_id, restaurant_id, display_number, capacity, status, created_at, updated_at) "
                + "VALUES (?, ?, 1, 4, 'ACTIVE', ?, ?)";
        String timeSlotSql = "INSERT INTO time_slot "
                + "(time_slot_id, shared_table_id, start_at, end_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        String paymentSql = "INSERT INTO payment "
                + "(portone_payment_id, member_id, time_slot_id, reservation_id, payment_purpose, party_size, "
                + "amount, currency, payment_status, expires_at, paid_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, NULL, 'CREATE', 2, 30000.00, 'KRW', 'PAID', ?, ?, ?, ?)";
        String refundSql = "INSERT INTO refund "
                + "(payment_id, amount, refund_status, requested_at, completed_at, idempotency_key, request_reason, "
                + "created_at, updated_at) "
                + "VALUES (?, 30000.00, 'COMPLETED', ?, ?, ?, 'noise', ?, ?)";

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement sharedTableStatement = connection.prepareStatement(sharedTableSql);
                    PreparedStatement timeSlotStatement = connection.prepareStatement(timeSlotSql);
                    PreparedStatement paymentStatement =
                            connection.prepareStatement(paymentSql, Statement.RETURN_GENERATED_KEYS);
                    PreparedStatement refundStatement = connection.prepareStatement(refundSql)) {

                int pendingSharedTables = 0;
                int pendingTimeSlots = 0;
                int pendingRefunds = 0;

                for (int st = 0; st < NOISE_SHARED_TABLE_COUNT; st++) {
                    long sharedTableId = NOISE_SHARED_TABLE_ID_OFFSET + st;
                    long restaurantId = NOISE_RESTAURANT_ID_OFFSET + st;

                    sharedTableStatement.setLong(1, sharedTableId);
                    sharedTableStatement.setLong(2, restaurantId);
                    sharedTableStatement.setTimestamp(3, Timestamp.from(now));
                    sharedTableStatement.setTimestamp(4, Timestamp.from(now));
                    sharedTableStatement.addBatch();
                    pendingSharedTables++;

                    for (int ts = 0; ts < TIME_SLOTS_PER_SHARED_TABLE; ts++) {
                        long sequence = (long) st * TIME_SLOTS_PER_SHARED_TABLE + ts;
                        long timeSlotId = NOISE_TIME_SLOT_ID_OFFSET + sequence;

                        // 과거 1년 ~ 미래 1년에 걸쳐 넓게 분산(system-wide 전체 기간 시뮬레이션).
                        long spreadSeconds = ((sequence * 6173L) % (730L * 24 * 3600)) - (365L * 24 * 3600);
                        Instant slotStart = now.plusSeconds(spreadSeconds);
                        Instant slotEnd = slotStart.plusSeconds(3600L);

                        timeSlotStatement.setLong(1, timeSlotId);
                        timeSlotStatement.setLong(2, sharedTableId);
                        timeSlotStatement.setTimestamp(3, Timestamp.from(slotStart));
                        timeSlotStatement.setTimestamp(4, Timestamp.from(slotEnd));
                        timeSlotStatement.setTimestamp(5, Timestamp.from(now));
                        timeSlotStatement.setTimestamp(6, Timestamp.from(now));
                        timeSlotStatement.addBatch();
                        pendingTimeSlots++;

                        paymentStatement.setString(1, "noise-agg-payment-" + sequence);
                        paymentStatement.setLong(2, 1_000_000L + (sequence % 100_000));
                        paymentStatement.setLong(3, timeSlotId);
                        paymentStatement.setTimestamp(4, Timestamp.from(now.minusSeconds(3600L)));
                        paymentStatement.setTimestamp(5, Timestamp.from(now.minusSeconds(900L)));
                        paymentStatement.setTimestamp(6, Timestamp.from(now));
                        paymentStatement.setTimestamp(7, Timestamp.from(now));
                        paymentStatement.executeUpdate();

                        if (sequence % NOISE_REFUND_EVERY_N == 0) {
                            long paymentId;
                            try (ResultSet keys = paymentStatement.getGeneratedKeys()) {
                                keys.next();
                                paymentId = keys.getLong(1);
                            }
                            refundStatement.setLong(1, paymentId);
                            refundStatement.setTimestamp(2, Timestamp.from(now.minusSeconds(1200L)));
                            refundStatement.setTimestamp(3, Timestamp.from(now.minusSeconds(600L)));
                            refundStatement.setString(4, "noise-agg-refund-" + sequence);
                            refundStatement.setTimestamp(5, Timestamp.from(now));
                            refundStatement.setTimestamp(6, Timestamp.from(now));
                            refundStatement.addBatch();
                            pendingRefunds++;
                        }
                    }

                    if (pendingSharedTables >= BATCH_SIZE || st == NOISE_SHARED_TABLE_COUNT - 1) {
                        sharedTableStatement.executeBatch();
                        pendingSharedTables = 0;
                    }
                    if (pendingTimeSlots >= BATCH_SIZE || st == NOISE_SHARED_TABLE_COUNT - 1) {
                        timeSlotStatement.executeBatch();
                        if (pendingRefunds > 0) {
                            refundStatement.executeBatch();
                            pendingRefunds = 0;
                        }
                        pendingTimeSlots = 0;
                        connection.commit();
                    }
                }
                connection.commit();
            }
        }
    }

    private record Fixture(Restaurant restaurant, LocalDate startDate, LocalDate endDate) {
    }

    private record Latency(double avgMs, ExpectedSettlementResponse response) {
    }

    private record ExplainRow(String table, String type, String key, String rows, String extra) {
    }
}
