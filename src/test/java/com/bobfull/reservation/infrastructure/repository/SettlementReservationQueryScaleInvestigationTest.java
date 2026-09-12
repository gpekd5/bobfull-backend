package com.bobfull.reservation.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.common.response.PageResponse;
import com.bobfull.member.entity.Member;
import com.bobfull.member.repository.MemberRepository;
import com.bobfull.payment.dto.SettlementReservationResponse;
import com.bobfull.payment.entity.Payment;
import com.bobfull.payment.entity.PaymentPurpose;
import com.bobfull.payment.repository.PaymentRepository;
import com.bobfull.payment.repository.RefundRepository;
import com.bobfull.payment.service.SettlementQueryService;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.restaurant.entity.Restaurant;
import com.bobfull.restaurant.repository.RestaurantRepository;
import com.bobfull.sharedtable.entity.SharedTable;
import com.bobfull.sharedtable.repository.SharedTableRepository;
import com.bobfull.timeslot.entity.TimeSlot;
import com.bobfull.timeslot.repository.TimeSlotRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Issue #65: {@code ReservationRepository.findSettlementReservations}(3-way join:
 * reservation ⋈ time_slot ⋈ shared_table, {@code shared_table.restaurant_id}로 필터 후
 * {@code time_slot.start_at} 범위로 필터)가 system-wide 규모가 커질수록 실제로 병목이 되는지
 * 실측하고, 인덱스 추가 후 재측정해 회귀를 고정하는 통합 테스트다.
 *
 * <p>{@code shared_table}에는 {@code idx_shared_table_restaurant_id(restaurant_id)}가 이미 있고
 * {@code time_slot}에는 {@code uk_time_slot_active_start(shared_table_id, active_start_at)}가
 * 있어(생성 컬럼이지만 leftmost prefix인 {@code shared_table_id}로 실제 인덱스를 탄다) 이 두
 * 테이블은 대상 식당 행만으로 이미 좁혀졌다. 하지만 {@code Reservation.time_slot_id}에는
 * 인덱스가 전혀 없었다.</p>
 *
 * <p><b>Before(인덱스 추가 전, 실측)</b>: {@code reservation} 테이블만 EXPLAIN
 * {@code type=ALL, key=null}(hash join buffer로 전체 스캔)이었고, system-wide noise
 * time_slot/reservation 4만 건 적재 후 rows 추정치가 40→40146으로, {@code
 * getReservationSettlements} 지연이 1.89배(14.00ms→26.40ms) 늘었다. <b>After(이 커밋에서
 * {@code idx_reservation_time_slot_id} 추가)</b>: {@code reservation}도
 * {@code type=ref, key=idx_reservation_time_slot_id, rows=1}로 바뀌어 noise 4만 건이 있어도
 * 지연이 늘지 않았다(오히려 16.20ms→12.80ms, 정상 범위 변동). 이 테스트는 그 이후 회귀를 막기
 * 위해 {@code reservation}의 EXPLAIN이 다시 전체 스캔으로 돌아가지 않는지를 고정 assertion으로
 * 남긴다.</p>
 *
 * <p>이 테스트는 대상 식당의 실제 정산 대상 예약은 소수(40건)로 고정한 채, 그 식당과 무관한
 * 다른 식당들의 shared_table/time_slot/reservation 행을 system-wide 규모(수만 건)로 늘려가며
 * {@code findSettlementReservations}와 동일한 SQL의 EXPLAIN 실행 계획, 그리고
 * {@code SettlementQueryService.getReservationSettlements}의 지연을 before/after 비교한다.
 * noise 행에는 대응하는 Payment 행을 만들지 않는다 — {@code amountsByReservation}은
 * {@code findSettlementReservations}가 반환한(항상 대상 식당 소유인) reservation_id만으로
 * 조회하므로 noise Payment 유무와 무관하며, 그 경로는 이미 Issue #65 앞선 커밋
 * ({@code idx_payment_reservation_id})에서 인덱스가 확인됐다. 이 테스트의 noise는 오직
 * {@code findSettlementReservations}가 거치는 shared_table/time_slot/reservation 3-way join을
 * system-wide 규모로 늘리는 데에만 집중한다.</p>
 *
 * <p>BOBFULL_MYSQL_PERF_TEST=true 일 때만 실행하며, 개발 DB가 아닌 별도 스키마
 * (BOBFULL_TEST_MYSQL_URL, 예: bobfull_perf_test_timeslot)를 사용한다. noise 행은
 * {@code SettlementQueryScaleInvestigationTest}와 동일한 관례로 JDBC 배치 INSERT로 적재한다.</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_PERF_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=update",
        "payment.expiration.enabled=false",
        "jwt.secret=settlement-reservation-scale-perf-test-secret-key-please-keep",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-settlement-reservation-scale-perf-test-secret",
        "portone.store-id=portone-settlement-reservation-scale-perf-test-store-id",
        "portone.webhook-secret=d2hzZWNfc2V0dGxlbWVudC1yZXNlcnZhdGlvbi1zY2FsZQ=="
})
class SettlementReservationQueryScaleInvestigationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long OWNER_MEMBER_ID = 1L;
    private static final int TARGET_RESERVATION_COUNT = 40;

    /** system-wide noise 다른 식당(=shared_table restaurant_id) 수. */
    private static final int NOISE_SHARED_TABLE_COUNT = 5_000;
    /** shared_table 1개당 만드는 time_slot(=reservation) 수. 총 noise 행 = 5,000 * 8 = 40,000건. */
    private static final int TIME_SLOTS_PER_SHARED_TABLE = 8;
    /** target의 실제 restaurant_id/shared_table_id/time_slot_id/reservation_id(작은 auto-increment 값)와
     *  절대 겹치지 않도록 큰 오프셋을 쓴다. */
    private static final long NOISE_RESTAURANT_ID_OFFSET = 900_000_000L;
    private static final long NOISE_SHARED_TABLE_ID_OFFSET = 900_000_000L;
    private static final long NOISE_TIME_SLOT_ID_OFFSET = 900_000_000L;
    private static final long NOISE_RESERVATION_ID_OFFSET = 900_000_000L;
    private static final long NOISE_MEMBER_ID_OFFSET = 900_000_000L;
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
    void cleanUp() throws Exception {
        // noise 행은 JDBC로 직접 넣었으므로(영속성 컨텍스트가 모름) deleteAllInBatch() 전에
        // 먼저 JDBC로도 지워 남는 행이 없게 한다. 이 스키마는 이 테스트 전용 throwaway 스키마다.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM reservation WHERE reservation_id >= " + NOISE_RESERVATION_ID_OFFSET);
            statement.executeUpdate("DELETE FROM time_slot WHERE time_slot_id >= " + NOISE_TIME_SLOT_ID_OFFSET);
            statement.executeUpdate("DELETE FROM shared_table WHERE shared_table_id >= " + NOISE_SHARED_TABLE_ID_OFFSET);
        }
        refundRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        timeSlotRepository.deleteAllInBatch();
        sharedTableRepository.deleteAllInBatch();
        restaurantRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void TimeSlot_start_at와_Reservation_time_slot_id_인덱스_부재가_system_wide_규모에서_영향을_주는지_측정한다() throws Exception {
        Fixture fixture = seedTargetRestaurant();

        long smallScaleTimeSlotCount = countRows("time_slot");
        long smallScaleReservationCount = countRows("reservation");
        System.out.printf("[Issue65-Reservation-Scale] noise 적재 전 system-wide time_slot 행 수=%d, reservation 행 수=%d%n",
                smallScaleTimeSlotCount, smallScaleReservationCount);

        Latency smallLatency = measure(fixture, "noise 적재 전(작은 테이블)");
        List<ExplainRow> smallExplain = explainSettlementQuery(fixture.restaurant.getId(), fixture.startDate, fixture.endDate,
                "noise 적재 전(작은 테이블)");

        long start = System.currentTimeMillis();
        seedSystemWideNoise();
        long seedMs = System.currentTimeMillis() - start;

        long largeScaleTimeSlotCount = countRows("time_slot");
        long largeScaleReservationCount = countRows("reservation");
        System.out.printf(
                "[Issue65-Reservation-Scale] noise 적재 완료(%d ms) 후 system-wide time_slot 행 수=%d, reservation 행 수=%d, "
                        + "target 식당 자체 reservation 행 수=%d%n",
                seedMs, largeScaleTimeSlotCount, largeScaleReservationCount, fixture.reservationIds.size());

        Latency largeLatency = measure(fixture, "noise 적재 후(대규모 테이블)");
        List<ExplainRow> largeExplain = explainSettlementQuery(fixture.restaurant.getId(), fixture.startDate, fixture.endDate,
                "noise 적재 후(대규모 테이블)");

        System.out.println("\n[Issue65-Reservation-Scale] ==== 지연 비교 요약 ====");
        System.out.printf("getReservationSettlements: 작은 테이블 평균=%.2fms, 대규모 테이블 평균=%.2fms (배율=%.2fx)%n",
                smallLatency.avgMs, largeLatency.avgMs, largeLatency.avgMs / Math.max(smallLatency.avgMs, 0.001));

        System.out.println("\n[Issue65-Reservation-Scale] ==== EXPLAIN 비교(작은 테이블 vs 대규모 테이블) ====");
        System.out.println("작은 테이블: " + smallExplain);
        System.out.println("대규모 테이블: " + largeExplain);

        // 결과 정합성: noise 행은 target 식당과 무관한 restaurant_id/shared_table_id를 쓰므로,
        // system-wide 규모가 커져도 대상 식당의 정산 목록 응답 내용은 동일해야 한다. 이 assert는
        // 지연(성능)에 대한 것이 아니라 noise 적재가 순수하게 "다른 데이터"이며 대상 식당 조회
        // 결과를 오염시키지 않았음을 확인하는 정합성 체크다.
        assertThat(largeLatency.response.content()).hasSize(smallLatency.response.content().size());

        // 회귀 고정: idx_reservation_time_slot_id 추가 이후 reservation 테이블이 다시 전체
        // 스캔(type=ALL)으로 나오면 안 된다. Before(인덱스 추가 전, 실측): noise 4만 건에서
        // type=ALL, key=null, rows≈40146(reservation 테이블 전체를 hash join buffer로 스캔),
        // getReservationSettlements 지연 1.89배(14.00ms→26.40ms). idx_reservation_time_slot_id
        // 추가 후에는 reservation도 shared_table/time_slot과 마찬가지로 인덱스를 타야 한다.
        List<String> largeReservationTypes = largeExplain.stream()
                .filter(row -> "r".equalsIgnoreCase(row.table()))
                .map(ExplainRow::type)
                .toList();
        assertThat(largeReservationTypes).as("reservation 테이블 조회가 다시 전체 스캔이 되면 안 된다").doesNotContain("ALL");
    }

    private Latency measure(Fixture fixture, String label) {
        Pageable pageable = PageRequest.of(0, TARGET_RESERVATION_COUNT);

        // JIT/커넥션 풀 워밍업(측정에서 제외).
        settlementQueryService.getReservationSettlements(
                OWNER_MEMBER_ID, fixture.restaurant.getId(), fixture.startDate, fixture.endDate, pageable);

        List<Long> ms = new ArrayList<>();
        PageResponse<SettlementReservationResponse> lastResponse = null;

        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long t0 = System.nanoTime();
            lastResponse = settlementQueryService.getReservationSettlements(
                    OWNER_MEMBER_ID, fixture.restaurant.getId(), fixture.startDate, fixture.endDate, pageable);
            long t1 = System.nanoTime();
            ms.add((t1 - t0) / 1_000_000L);
        }

        double avg = ms.stream().mapToLong(Long::longValue).average().orElse(0);
        System.out.printf("[Issue65-Reservation-Scale] %s: getReservationSettlements 반복 측정(ms)=%s, 평균=%.2fms%n",
                label, ms, avg);

        return new Latency(avg, lastResponse);
    }

    /**
     * {@code ReservationRepository.findSettlementReservations}가 생성하는 것과 동일한 SQL
     * (reservation ⋈ time_slot ⋈ shared_table, restaurant_id + start_at 범위 필터)의 EXPLAIN을
     * 확인한다. 3-way join이므로 EXPLAIN 결과에 테이블별로 여러 행이 나올 수 있어 전부 수집한다.
     */
    private List<ExplainRow> explainSettlementQuery(Long restaurantId, LocalDate startDate, LocalDate endDate, String label)
            throws Exception {
        Instant startAt = startDate.atStartOfDay(SEOUL).toInstant();
        Instant endAt = endDate.plusDays(1).atStartOfDay(SEOUL).toInstant();
        String sql = "SELECT r.* FROM reservation r "
                + "JOIN time_slot ts ON r.time_slot_id = ts.time_slot_id "
                + "JOIN shared_table st ON ts.shared_table_id = st.shared_table_id "
                + "WHERE st.restaurant_id = " + restaurantId
                + " AND ts.start_at >= '" + Timestamp.from(startAt) + "'"
                + " AND ts.start_at < '" + Timestamp.from(endAt) + "'";

        List<ExplainRow> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            System.out.println("\n[Issue65-Reservation-Scale] ==== EXPLAIN: findSettlementReservations(" + label + ") ====");
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
                    System.out.printf("[Issue65-Reservation-Scale] 요약: table=%s, type=%s, key=%s, rows(추정)=%s, Extra=%s%s%n",
                            table, type, key, rowsEstimate, extra,
                            "ALL".equalsIgnoreCase(type) ? "  <-- FULL TABLE SCAN" : "");
                    rows.add(new ExplainRow(table, type, key, rowsEstimate, extra));
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

    /** 대상 식당: 실제 정산 조회 대상이 되는 40건의 예약을 엔티티 생성 편의 메서드로 만든다. */
    private Fixture seedTargetRestaurant() {
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(OWNER_MEMBER_ID, "정산성능측정(TimeSlot 규모) 식당", "서울시", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), 4));
        Member member = memberRepository.saveAndFlush(
                Member.createMember("settlement-reservation-scale-perf@example.com", "hash", "홍길동", "01099990004"));

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
                    "settlement-reservation-scale-" + i,
                    member.getId(), timeSlot.getId(), reservation.getId(), PaymentPurpose.JOIN, 2,
                    BigDecimal.valueOf(30000), base.minusSeconds(600L));
            payment.complete(base.minusSeconds(300L));
            paymentRepository.saveAndFlush(payment);
        }

        return new Fixture(restaurant, startDate, endDate, reservationIds);
    }

    /**
     * target 식당과 무관한 system-wide shared_table/time_slot/reservation noise 행을 JDBC 배치
     * INSERT로 적재한다. restaurant_id/shared_table_id는 target의 실제(작은) 값과 절대 겹치지
     * 않는 큰 오프셋 범위를 쓴다. time_slot.start_at은 넓은 날짜 범위(과거 1년~미래 1년)에 걸쳐
     * 분산시켜, "system-wide 전체 기간"을 시뮬레이션한다. active_start_at은 생성 컬럼이라
     * INSERT 대상에서 제외한다(insertable=false).
     */
    private void seedSystemWideNoise() throws Exception {
        Instant now = Instant.now();
        String sharedTableSql = "INSERT INTO shared_table "
                + "(shared_table_id, restaurant_id, display_number, capacity, status, created_at, updated_at) "
                + "VALUES (?, ?, 1, 4, 'ACTIVE', ?, ?)";
        String timeSlotSql = "INSERT INTO time_slot "
                + "(time_slot_id, shared_table_id, start_at, end_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        String reservationSql = "INSERT INTO reservation "
                + "(reservation_id, time_slot_id, creator_member_id, reservation_status, recruitment_status, "
                + "created_at, updated_at) "
                + "VALUES (?, ?, ?, 'CONFIRMED', 'CLOSED', ?, ?)";

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement sharedTableStatement = connection.prepareStatement(sharedTableSql);
                    PreparedStatement timeSlotStatement = connection.prepareStatement(timeSlotSql);
                    PreparedStatement reservationStatement = connection.prepareStatement(reservationSql)) {

                int pendingSharedTables = 0;
                int pendingTimeSlots = 0;
                int pendingReservations = 0;

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
                        long reservationId = NOISE_RESERVATION_ID_OFFSET + sequence;
                        long memberId = NOISE_MEMBER_ID_OFFSET + (sequence % 1000);

                        // 과거 1년 ~ 미래 1년에 걸쳐 넓게 분산(system-wide 전체 기간 시뮬레이션).
                        // target 날짜 범위(내일부터 이틀)와도 실제로 겹칠 수 있게 한다.
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

                        reservationStatement.setLong(1, reservationId);
                        reservationStatement.setLong(2, timeSlotId);
                        reservationStatement.setLong(3, memberId);
                        reservationStatement.setTimestamp(4, Timestamp.from(now));
                        reservationStatement.setTimestamp(5, Timestamp.from(now));
                        reservationStatement.addBatch();
                        pendingReservations++;
                    }

                    if (pendingSharedTables >= BATCH_SIZE || st == NOISE_SHARED_TABLE_COUNT - 1) {
                        sharedTableStatement.executeBatch();
                        pendingSharedTables = 0;
                    }
                    if (pendingTimeSlots >= BATCH_SIZE || st == NOISE_SHARED_TABLE_COUNT - 1) {
                        timeSlotStatement.executeBatch();
                        reservationStatement.executeBatch();
                        pendingTimeSlots = 0;
                        pendingReservations = 0;
                        connection.commit();
                    }
                }
                connection.commit();
            }
        }
    }

    private record Fixture(Restaurant restaurant, LocalDate startDate, LocalDate endDate, List<Long> reservationIds) {
    }

    private record Latency(double avgMs, PageResponse<SettlementReservationResponse> response) {
    }

    private record ExplainRow(String table, String type, String key, String rows, String extra) {
    }
}
