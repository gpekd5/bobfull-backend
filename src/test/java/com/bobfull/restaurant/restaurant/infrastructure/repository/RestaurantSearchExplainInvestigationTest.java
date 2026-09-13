package com.bobfull.restaurant.restaurant.infrastructure.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Issue #61 Track A: 식당 검색(RestaurantSearchRepositoryImpl.search)이 실제로 생성하는 SQL을
 * MySQL EXPLAIN ANALYZE로 직접 실행해 실행 계획과 실제 처리 행 수를 확인하는 선택적 통합
 * 테스트다. BOBFULL_MYSQL_PERF_TEST=true 일 때만 실행하며, 개발 DB가 아닌 별도 스키마
 * (BOBFULL_TEST_MYSQL_URL)를 사용한다. 결과 판정을 이 테스트의 assert로 하지 않고
 * System.out으로 출력해 docs/110-records/evidence/v3/61-search-query/README.md에 근거로 남긴다
 * (H2가 아닌 실제 MySQL 실행 계획만 근거로 인정한다는 Issue 원칙). 옵티마이저의 EXPLAIN
 * 추정 rows는 ORDER BY+LIMIT 조합에서 실제 스캔 행 수와 크게 다를 수 있어 EXPLAIN ANALYZE의
 * actual rows/loops를 함께 확인한다.
 *
 * <p>Hibernate saveAll 대신 배치 JDBC INSERT로 대량 데이터를 빠르게 적재해 옵티마이저가
 * 실제 규모(식당 5천 건, 각 1개 합석 테이블·2개 회차)에서 어떤 접근 방식을 선택하는지 확인한다.</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_PERF_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=update",
        "payment.expiration.enabled=false",
        "jwt.secret=search-explain-perf-test-secret-key-please-keep-this-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-search-explain-perf-test-api-secret",
        "portone.store-id=portone-search-explain-perf-test-store-id",
        "portone.webhook-secret=d2hzZWNfc2VhcmNoLWV4cGxhaW4tcGVyZg=="
})
class RestaurantSearchExplainInvestigationTest {

    private static final int RESTAURANT_COUNT = 5_000;
    private static final int MATCHING_KEYWORD_EVERY_N = 250;
    private static final int DATE_SPREAD_DAYS = 30;
    private static final Instant BASE_DATE = Instant.parse("2026-08-11T00:00:00Z");

    @Autowired private DataSource dataSource;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM time_slot WHERE shared_table_id IN "
                    + "(SELECT shared_table_id FROM shared_table WHERE restaurant_id IN "
                    + "(SELECT restaurant_id FROM restaurant WHERE name LIKE 'perf-explain-%'))");
            statement.execute("DELETE FROM shared_table WHERE restaurant_id IN "
                    + "(SELECT restaurant_id FROM restaurant WHERE name LIKE 'perf-explain-%')");
            statement.execute("DELETE FROM restaurant WHERE name LIKE 'perf-explain-%'");
        }
    }

    @Test
    void 식당_검색_필터_조합별_실제_MySQL_실행계획을_출력한다() throws Exception {
        seedRestaurantsWithTablesAndTimeSlots();

        try (Connection connection = dataSource.getConnection()) {
            explain(connection, "기본(필터 없음)",
                    "SELECT DISTINCT r.restaurant_id FROM restaurant r "
                            + "WHERE r.deleted_at IS NULL AND r.status = 'ACTIVE' "
                            + "ORDER BY r.restaurant_id ASC LIMIT 20");

            explain(connection, "keyword LIKE 검색(선두 와일드카드, 실제 매치 20건/5천건)",
                    "SELECT DISTINCT r.restaurant_id FROM restaurant r "
                            + "WHERE r.deleted_at IS NULL AND r.status = 'ACTIVE' "
                            + "AND (LOWER(r.name) LIKE LOWER('%맛집%') OR LOWER(r.keyword) LIKE LOWER('%맛집%')) "
                            + "ORDER BY r.restaurant_id ASC LIMIT 20");

            explain(connection, "category 등치 필터(1/20 선택도)",
                    "SELECT DISTINCT r.restaurant_id FROM restaurant r "
                            + "WHERE r.deleted_at IS NULL AND r.status = 'ACTIVE' AND r.category = 'perf-category-5' "
                            + "ORDER BY r.restaurant_id ASC LIMIT 20");

            explain(connection, "date 필터(3-way join, BETWEEN, 1/30 선택도)",
                    "SELECT DISTINCT r.restaurant_id FROM restaurant r, shared_table st, time_slot ts "
                            + "WHERE r.deleted_at IS NULL AND r.status = 'ACTIVE' "
                            + "AND st.restaurant_id = r.restaurant_id AND st.deleted_at IS NULL "
                            + "AND ts.shared_table_id = st.shared_table_id AND ts.deleted_at IS NULL "
                            + "AND ts.start_at >= '2026-08-11 02:00:00' AND ts.start_at < '2026-08-12 02:00:00' "
                            + "ORDER BY r.restaurant_id ASC LIMIT 20");

            explain(connection, "time 필터(hour/minute 함수 래핑, date 없음, 1/24 선택도)",
                    "SELECT DISTINCT r.restaurant_id FROM restaurant r, shared_table st, time_slot ts "
                            + "WHERE r.deleted_at IS NULL AND r.status = 'ACTIVE' "
                            + "AND st.restaurant_id = r.restaurant_id AND st.deleted_at IS NULL "
                            + "AND ts.shared_table_id = st.shared_table_id AND ts.deleted_at IS NULL "
                            + "AND hour(ts.start_at) = 2 AND minute(ts.start_at) = 0 "
                            + "ORDER BY r.restaurant_id ASC LIMIT 20");

            // Issue #61 최소 조합 보완(date+time / 정렬 / pagination). raw Before/After는
            // docs/110-records/evidence/v3/61-search-query/README.md와 raw/explain-*-trackA.txt에 기록됨.
            explain(connection, "date + time 필터(정확 시각 일치)",
                    "SELECT DISTINCT r.restaurant_id FROM restaurant r, shared_table st, time_slot ts "
                            + "WHERE r.deleted_at IS NULL AND r.status = 'ACTIVE' "
                            + "AND st.restaurant_id = r.restaurant_id AND st.deleted_at IS NULL "
                            + "AND ts.shared_table_id = st.shared_table_id AND ts.deleted_at IS NULL "
                            + "AND ts.start_at = '2026-08-13 02:00:00' "
                            + "ORDER BY r.restaurant_id ASC LIMIT 20");

            explain(connection, "정렬 조건(name ASC)",
                    "SELECT DISTINCT r.restaurant_id, r.name FROM restaurant r "
                            + "WHERE r.deleted_at IS NULL AND r.status = 'ACTIVE' "
                            + "ORDER BY r.name ASC LIMIT 20");

            explain(connection, "pagination 2페이지(id ASC, OFFSET 20)",
                    "SELECT DISTINCT r.restaurant_id FROM restaurant r "
                            + "WHERE r.deleted_at IS NULL AND r.status = 'ACTIVE' "
                            + "ORDER BY r.restaurant_id ASC LIMIT 20 OFFSET 20");
        }
    }

    private void explain(Connection connection, String label, String sql) throws Exception {
        System.out.println("\n[Issue61-TrackA] ==== " + label + " ====");
        System.out.println("SQL: " + sql);
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("EXPLAIN ANALYZE " + sql)) {
            while (resultSet.next()) {
                System.out.println(resultSet.getString(1));
            }
        }
    }

    private void seedRestaurantsWithTablesAndTimeSlots() throws Exception {
        Instant now = Instant.now();
        String restaurantSql = "INSERT INTO restaurant "
                + "(owner_member_id, name, address, category, description, keyword, deposit_per_person, status, created_at, updated_at) "
                + "VALUES (1, ?, ?, ?, ?, ?, 10000, 'ACTIVE', ?, ?)";
        String sharedTableSql = "INSERT INTO shared_table "
                + "(restaurant_id, display_number, capacity, status, created_at, updated_at) "
                + "VALUES (?, 1, 4, 'ACTIVE', ?, ?)";
        String timeSlotSql = "INSERT INTO time_slot (shared_table_id, start_at, end_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?)";

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement restaurantStatement =
                            connection.prepareStatement(restaurantSql, Statement.RETURN_GENERATED_KEYS);
                    PreparedStatement sharedTableStatement =
                            connection.prepareStatement(sharedTableSql, Statement.RETURN_GENERATED_KEYS);
                    PreparedStatement timeSlotStatement = connection.prepareStatement(timeSlotSql)) {
                for (int i = 0; i < RESTAURANT_COUNT; i++) {
                    boolean matchesKeyword = i % MATCHING_KEYWORD_EVERY_N == 0;
                    String name = matchesKeyword ? "perf-explain-제주맛집" + i : "perf-explain-" + i;
                    restaurantStatement.setString(1, name);
                    restaurantStatement.setString(2, "제주시 " + i + "번지");
                    restaurantStatement.setString(3, "perf-category-" + (i % 20));
                    restaurantStatement.setString(4, "설명 " + i);
                    restaurantStatement.setString(5, "키워드" + i);
                    restaurantStatement.setTimestamp(6, Timestamp.from(now));
                    restaurantStatement.setTimestamp(7, Timestamp.from(now));
                    restaurantStatement.executeUpdate();
                    long restaurantId;
                    try (ResultSet keys = restaurantStatement.getGeneratedKeys()) {
                        keys.next();
                        restaurantId = keys.getLong(1);
                    }

                    sharedTableStatement.setLong(1, restaurantId);
                    sharedTableStatement.setTimestamp(2, Timestamp.from(now));
                    sharedTableStatement.setTimestamp(3, Timestamp.from(now));
                    sharedTableStatement.executeUpdate();
                    long sharedTableId;
                    try (ResultSet keys = sharedTableStatement.getGeneratedKeys()) {
                        keys.next();
                        sharedTableId = keys.getLong(1);
                    }

                    int dayOffset = i % DATE_SPREAD_DAYS;
                    int hourOffset = i % 24;
                    Instant day = BASE_DATE.plusSeconds(dayOffset * 86400L);
                    for (int slot = 0; slot < 2; slot++) {
                        Instant startAt = day.plusSeconds(hourOffset * 3600L + slot * 1800L);
                        timeSlotStatement.setLong(1, sharedTableId);
                        timeSlotStatement.setTimestamp(2, Timestamp.from(startAt));
                        timeSlotStatement.setTimestamp(3, Timestamp.from(startAt.plusSeconds(1800L)));
                        timeSlotStatement.setTimestamp(4, Timestamp.from(now));
                        timeSlotStatement.setTimestamp(5, Timestamp.from(now));
                        timeSlotStatement.addBatch();
                    }
                    if (i % 500 == 499) {
                        timeSlotStatement.executeBatch();
                        connection.commit();
                    }
                }
                timeSlotStatement.executeBatch();
            }
            connection.commit();
        }
    }
}
