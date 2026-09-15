package com.bobfull.restaurant.restaurant.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.restaurant.restaurant.infrastructure.cache.RestaurantSearchCacheStore;
import com.bobfull.restaurant.restaurant.presentation.request.RestaurantUpdateRequest;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.restaurant.application.service.RestaurantService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;

/**
 * Issue #62 시나리오 B/C/D/F: 실제 Redis Cache를 적용한 뒤 Cold(최초 조회)/Warm(반복 Hit)/Mixed
 * (여러 key 혼합)/무효화(Restaurant 변경) 효과를 실제 MySQL+Redis로 측정하는 선택적 통합
 * 테스트다. BOBFULL_MYSQL_PERF_TEST=true + BOBFULL_TEST_REDIS_HOST/PORT가 있을 때만 실행하며,
 * 개발 DB·개발 Redis가 아닌 별도 인스턴스를 사용한다.
 *
 * <p>{@link RestaurantSearchNoCacheBaselineInvestigationTest}가 만든 No Cache 기준선과 같은
 * Fixture 규모·같은 keyword 검색 조건으로 비교 가능하게 구성했다.</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_PERF_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.data.redis.host=${BOBFULL_TEST_REDIS_HOST}",
        "spring.data.redis.port=${BOBFULL_TEST_REDIS_PORT}",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "restaurant.search-cache.ttl-seconds=60",
        "payment.expiration.enabled=false",
        "outbox.chat-room.enabled=false",
        "outbox.email.enabled=false",
        "jwt.secret=search-cache-effect-secret-key-please-keep-it-long-enough",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-search-cache-effect-api-secret",
        "portone.store-id=portone-search-cache-effect-store-id",
        "portone.webhook-secret=d2hzZWNfc2VhcmNoLWNhY2hlLWVmZmVjdA=="
})
class RestaurantSearchCacheEffectInvestigationTest {

    private static final int RESTAURANT_COUNT = 5_000;
    private static final int CONCURRENT_THREADS = 30;
    private static final int REQUESTS_PER_THREAD = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private RestaurantSearchCacheStore restaurantSearchCacheStore;

    @Autowired
    private RestaurantService restaurantService;

    @Autowired
    private RestaurantRepository restaurantRepository;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void isolateCacheNamespace() {
        restaurantSearchCacheStore.bumpVersion();
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM shared_table WHERE restaurant_id IN "
                    + "(SELECT restaurant_id FROM restaurant WHERE name LIKE 'perf-cache-%')");
            statement.execute("DELETE FROM restaurant WHERE name LIKE 'perf-cache-%'");
        }
    }

    @Test
    void Cold_Miss_이후_Warm_Hit로_반복하면_DB_쿼리가_더이상_발생하지_않는다() throws Exception {
        seedRestaurants();
        URI uri = searchUri("맛집");
        Statistics statistics = statistics();

        statistics.clear();
        long coldStart = System.nanoTime();
        assertThat(get(uri)).isEqualTo(200);
        long coldElapsedMs = (System.nanoTime() - coldStart) / 1_000_000;
        long coldQueryCount = statistics.getPrepareStatementCount();

        statistics.clear();
        List<Long> warmLatenciesMs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            assertThat(get(uri)).isEqualTo(200);
            warmLatenciesMs.add((System.nanoTime() - start) / 1_000_000);
        }
        long warmQueryCount = statistics.getPrepareStatementCount();

        System.out.printf("[Issue62-CacheEffect] Cold: %dms, 쿼리 수=%d%n", coldElapsedMs, coldQueryCount);
        System.out.printf("[Issue62-CacheEffect] Warm(N=20) 평균=%dms, 쿼리 수=%d%n",
                warmLatenciesMs.stream().mapToLong(Long::longValue).sum() / warmLatenciesMs.size(), warmQueryCount);

        assertThat(coldQueryCount).isGreaterThan(0);
        assertThat(warmQueryCount).isEqualTo(0);
    }

    @Test
    void Warm_Hit_동시_반복_요청은_DB_Connection_Pool을_거치지_않는다() throws Exception {
        seedRestaurants();
        URI uri = searchUri("한식");
        assertThat(get(uri)).isEqualTo(200);

        HikariPoolMXBean poolMXBean = hikariDataSource().getHikariPoolMXBean();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>());
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
        for (int t = 0; t < CONCURRENT_THREADS; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int r = 0; r < REQUESTS_PER_THREAD; r++) {
                        long start = System.nanoTime();
                        get(uri);
                        latencies.add((System.nanoTime() - start) / 1_000_000);
                    }
                } catch (Exception ignored) {
                    // 측정 종료
                }
            });
        }
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        poolMonitor.interrupt();
        poolMonitor.join(1000);

        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(null);
        System.out.printf("[Issue62-CacheEffect] Warm 동시(thread=%d x %d) 최대 active=%d, 최대 awaiting=%d, "
                        + "p50=%dms p95=%dms max=%dms%n",
                CONCURRENT_THREADS, REQUESTS_PER_THREAD, maxActive.get(), maxAwaiting.get(),
                sorted.get(sorted.size() / 2), sorted.get((int) (sorted.size() * 0.95) - 1), sorted.get(sorted.size() - 1));

        assertThat(maxAwaiting.get()).isEqualTo(0);
    }

    @Test
    void Mixed_시나리오는_반복된_key만_Hit되고_새_key는_Miss로_DB를_조회한다() throws Exception {
        seedRestaurants();
        List<String> keywords = List.of("맛집", "한식", "일식", "카페", "제주");
        Statistics statistics = statistics();

        statistics.clear();
        for (String keyword : keywords) {
            assertThat(get(searchUri(keyword))).isEqualTo(200);
        }
        long firstPassQueryCount = statistics.getPrepareStatementCount();

        statistics.clear();
        for (String keyword : keywords) {
            assertThat(get(searchUri(keyword))).isEqualTo(200);
        }
        long secondPassQueryCount = statistics.getPrepareStatementCount();

        System.out.printf("[Issue62-CacheEffect] Mixed 1차(전부 Miss, %d개 key) 쿼리 수=%d, 2차(전부 Hit) 쿼리 수=%d%n",
                keywords.size(), firstPassQueryCount, secondPassQueryCount);

        assertThat(firstPassQueryCount).isGreaterThan(0);
        assertThat(secondPassQueryCount).isEqualTo(0);
    }

    @Test
    void Restaurant_수정_후_같은_검색_결과가_최신값으로_갱신된다() throws Exception {
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(1L, "perf-cache-무효화식당", "제주시", "한식", "설명", "무효화키워드", 10000));

        URI uri = searchUri("무효화키워드");
        assertThat(get(uri)).isEqualTo(200);
        String beforeBody = getBody(uri);
        assertThat(beforeBody).contains("10000");

        restaurantService.update(1L, restaurant.getId(),
                new RestaurantUpdateRequest("perf-cache-무효화식당", "설명", "무효화키워드", 20000, null));

        String afterBody = getBody(uri);
        assertThat(afterBody).contains("20000");
        assertThat(afterBody).doesNotContain("\"depositPerPerson\":10000");
    }

    private URI searchUri(String keyword) throws Exception {
        return URI.create("http://localhost:" + port + "/api/restaurants?keyword="
                + java.net.URLEncoder.encode(keyword, "UTF-8") + "&page=0&size=20");
    }

    private int get(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    private String getBody(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
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
                    statement.setString(3, i % 2 == 0 ? "한식" : "일식");
                    statement.setString(4, "설명 " + i);
                    statement.setString(5, i % 3 == 0 ? "카페,제주" : "키워드" + i);
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
