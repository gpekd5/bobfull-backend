package com.bobfull.restaurant.restaurant.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Issue #62 시나리오 A: #61 After 코드 기준 {@code GET /api/restaurants}를 실제 HTTP로 반복
 * 호출해 Redis Cache 없이도 지연시간·DB Query 수·DB Connection Pool이 어떤 수준인지 측정하는
 * No Cache 기준선 선택적 통합 테스트다. BOBFULL_MYSQL_PERF_TEST=true 일 때만 실행하며,
 * 개발 DB가 아닌 별도 스키마(BOBFULL_TEST_MYSQL_URL)를 사용한다. 이 기준선이 이미 낮은
 * 지연·낮은 Pool 대기를 보이면 Cache 적용 가치가 없다는 근거가 되고(Issue 원칙: 병목이 없으면
 * 미도입 허용), 반대로 Pool 대기·높은 p95가 보이면 Cache 적용을 검토할 근거가 된다.
 *
 * <p>새 테스트 의존성 추가 없이 JDK 내장 {@link HttpClient}로 실제 HTTP round trip을 측정한다.</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_PERF_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "payment.expiration.enabled=false",
        "outbox.chat-room.enabled=false",
        "outbox.email.enabled=false",
        "jwt.secret=search-cache-no-cache-baseline-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-search-cache-baseline-api-secret",
        "portone.store-id=portone-search-cache-baseline-store-id",
        "portone.webhook-secret=d2hzZWNfc2VhcmNoLWNhY2hlLWJhc2VsaW5l"
})
class RestaurantSearchNoCacheBaselineInvestigationTest {

    private static final int RESTAURANT_COUNT = 5_000;
    private static final int SEQUENTIAL_WARMUP = 5;
    private static final int SEQUENTIAL_MEASURED = 50;
    private static final int CONCURRENT_THREADS = 30;
    private static final int REQUESTS_PER_THREAD = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM shared_table WHERE restaurant_id IN "
                    + "(SELECT restaurant_id FROM restaurant WHERE name LIKE 'perf-cache-%')");
            statement.execute("DELETE FROM restaurant WHERE name LIKE 'perf-cache-%'");
        }
    }

    @Test
    void No_Cache_기준선_반복_검색_요청의_지연시간과_DB_풀_사용량을_측정한다() throws Exception {
        seedRestaurants();

        URI uri = URI.create("http://localhost:" + port + "/api/restaurants?keyword="
                + java.net.URLEncoder.encode("맛집", "UTF-8") + "&page=0&size=20");

        for (int i = 0; i < SEQUENTIAL_WARMUP; i++) {
            get(uri);
        }

        Statistics statistics = statistics();
        statistics.clear();

        List<Long> sequentialLatenciesMs = new ArrayList<>();
        for (int i = 0; i < SEQUENTIAL_MEASURED; i++) {
            long start = System.nanoTime();
            int statusCode = get(uri);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            sequentialLatenciesMs.add(elapsedMs);
            assertThat(statusCode).isEqualTo(200);
        }
        long sequentialQueryCount = statistics.getPrepareStatementCount();

        printPercentiles("순차 반복(동일 조건, N=" + SEQUENTIAL_MEASURED + ")", sequentialLatenciesMs);
        System.out.printf("[Issue62-NoCacheBaseline] 순차 반복 총 SQL PreparedStatement 수=%d (요청당 평균=%.2f)%n",
                sequentialQueryCount, (double) sequentialQueryCount / SEQUENTIAL_MEASURED);

        HikariPoolMXBean poolMXBean = hikariDataSource().getHikariPoolMXBean();
        System.out.printf("[Issue62-NoCacheBaseline] 순차 반복 종료 후 Pool: active=%d idle=%d awaiting=%d total=%d%n",
                poolMXBean.getActiveConnections(), poolMXBean.getIdleConnections(),
                poolMXBean.getThreadsAwaitingConnection(), poolMXBean.getTotalConnections());

        statistics.clear();
        List<Long> concurrentLatenciesMs = runConcurrentBurst(uri, poolMXBean);
        long concurrentQueryCount = statistics.getPrepareStatementCount();
        int totalConcurrentRequests = CONCURRENT_THREADS * REQUESTS_PER_THREAD;

        printPercentiles("동시 반복(동일 조건, thread=" + CONCURRENT_THREADS + " x " + REQUESTS_PER_THREAD + ")",
                concurrentLatenciesMs);
        System.out.printf("[Issue62-NoCacheBaseline] 동시 반복 총 SQL PreparedStatement 수=%d (요청당 평균=%.2f)%n",
                concurrentQueryCount, (double) concurrentQueryCount / totalConcurrentRequests);
    }

    private int get(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    private List<Long> runConcurrentBurst(URI uri, HikariPoolMXBean poolMXBean) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger maxAwaiting = new AtomicInteger(0);
        AtomicInteger maxActive = new AtomicInteger(0);

        Thread poolMonitor = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 200; i++) {
                    maxAwaiting.getAndUpdate(current -> Math.max(current, poolMXBean.getThreadsAwaitingConnection()));
                    maxActive.getAndUpdate(current -> Math.max(current, poolMXBean.getActiveConnections()));
                    Thread.sleep(5);
                }
            } catch (InterruptedException ignored) {
                // 측정 종료
            }
        });
        poolMonitor.start();

        try {
            for (int t = 0; t < CONCURRENT_THREADS; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int r = 0; r < REQUESTS_PER_THREAD; r++) {
                            long start = System.nanoTime();
                            get(uri);
                            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                            latencies.add(elapsedMs);
                        }
                    } catch (Exception ignored) {
                        // 측정 종료
                    }
                });
            }
            startLatch.countDown();
            executor.shutdown();
            boolean finished = executor.awaitTermination(60, TimeUnit.SECONDS);
            assertThat(finished).isTrue();
        } finally {
            poolMonitor.interrupt();
            poolMonitor.join(1000);
        }

        System.out.printf("[Issue62-NoCacheBaseline] 동시 반복 중 관측된 최대 active=%d, 최대 awaiting(대기)=%d, Pool 총량=%d%n",
                maxActive.get(), maxAwaiting.get(), poolMXBean.getTotalConnections());
        return latencies;
    }

    private void printPercentiles(String label, List<Long> latenciesMs) {
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        long p50 = percentile(sorted, 50);
        long p95 = percentile(sorted, 95);
        long p99 = percentile(sorted, 99);
        long max = sorted.get(sorted.size() - 1);
        System.out.printf("[Issue62-NoCacheBaseline] %s : p50=%dms p95=%dms p99=%dms max=%dms n=%d%n",
                label, p50, p95, p99, max, sorted.size());
    }

    private long percentile(List<Long> sortedLatencies, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedLatencies.size()) - 1;
        index = Math.max(0, Math.min(index, sortedLatencies.size() - 1));
        return sortedLatencies.get(index);
    }

    private HikariDataSource hikariDataSource() {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            return hikariDataSource;
        }
        throw new IllegalStateException("DataSource가 HikariDataSource가 아닙니다: " + dataSource.getClass());
    }

    private Statistics statistics() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        return sessionFactory.getStatistics();
    }

    private void seedRestaurants() throws Exception {
        Instant now = Instant.now();
        String sql = "INSERT INTO restaurant "
                + "(owner_member_id, name, address, category, description, keyword, deposit_per_person, status, created_at, updated_at) "
                + "VALUES (1, ?, ?, ?, ?, ?, 10000, 'ACTIVE', ?, ?)";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < RESTAURANT_COUNT; i++) {
                    boolean matchesKeyword = i % 250 == 0;
                    String name = matchesKeyword ? "perf-cache-제주맛집" + i : "perf-cache-" + i;
                    statement.setString(1, name);
                    statement.setString(2, "제주시 " + i + "번지");
                    statement.setString(3, "perf-category-" + (i % 20));
                    statement.setString(4, "설명 " + i);
                    statement.setString(5, "키워드" + i);
                    statement.setTimestamp(6, Timestamp.from(now));
                    statement.setTimestamp(7, Timestamp.from(now));
                    statement.addBatch();
                    if (i % 1000 == 999) {
                        statement.executeBatch();
                    }
                }
                statement.executeBatch();
            }
            connection.commit();
        }
    }
}
