package com.bobfull.restaurant.sharedtable.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableBulkRequest;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableBulkResponse;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableResponse;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Issue #138: 식당 행 비관적 락이 실제 MySQL에서도 동시 일괄 등록의 표시 번호 발급을
 * 직렬화하는지 검증하는 선택적 통합 테스트다. BOBFULL_MYSQL_CONCURRENCY_TEST=true 일 때만 실행한다.
 *
 * <p><b>주의</b>: {@code spring.jpa.hibernate.ddl-auto=create-drop}이라 실행할 때마다 대상 DB의
 * 모든 테이블을 지운다. {@code BOBFULL_TEST_MYSQL_URL}은 반드시 개발 DB가 아닌 별도 스키마를
 * 가리켜야 한다.</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_CONCURRENCY_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payment.expiration.enabled=false",
        "jwt.secret=shared-table-concurrency-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-shared-table-concurrency-test-api-secret",
        "portone.store-id=portone-shared-table-concurrency-test-store-id",
        "portone.webhook-secret=d2hzZWNfc2hhcmVkX3RhYmxlX2NvbmN1cnJlbmN5"
})
class SharedTableConcurrencyIntegrationTest {

    @Autowired private SharedTableService sharedTableService;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private RestaurantRepository restaurantRepository;

    @AfterEach
    void cleanUp() {
        sharedTableRepository.deleteAll();
        restaurantRepository.deleteAll();
    }

    @Test
    void 같은_식당에_동시_일괄_등록_요청이_들어와도_표시_번호가_중복되지_않고_연속으로_발급된다() throws Exception {
        Long ownerMemberId = 1L;
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(ownerMemberId, "동시성 테스트 식당", "제주시", "한식", "설명", "키워드", 10000));

        List<AttemptResult> results = raceTwo(
                () -> sharedTableService.registerBulk(
                        ownerMemberId, restaurant.getId(), new SharedTableBulkRequest(4, 3)),
                () -> sharedTableService.registerBulk(
                        ownerMemberId, restaurant.getId(), new SharedTableBulkRequest(6, 2))
        );

        assertThat(successCount(results)).isEqualTo(2);

        List<Integer> displayNumbers = results.stream()
                .map(AttemptResult::response)
                .flatMap(response -> response.tables().stream())
                .map(SharedTableResponse::displayNumber)
                .sorted()
                .toList();

        assertThat(displayNumbers).hasSize(5);
        assertThat(displayNumbers).doesNotHaveDuplicates();
        assertThat(displayNumbers).containsExactly(1, 2, 3, 4, 5);
        assertThat(sharedTableRepository.findAllByRestaurantIdAndDeletedAtIsNull(restaurant.getId()))
                .hasSize(5);
    }

    private List<AttemptResult> raceTwo(
            Callable<SharedTableBulkResponse> first, Callable<SharedTableBulkResponse> second
    ) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AttemptResult> firstFuture = executor.submit(() -> attempt(start, first));
            Future<AttemptResult> secondFuture = executor.submit(() -> attempt(start, second));
            start.countDown();
            return List.of(firstFuture.get(10, TimeUnit.SECONDS), secondFuture.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private AttemptResult attempt(CountDownLatch start, Callable<SharedTableBulkResponse> call)
            throws InterruptedException {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            return AttemptResult.succeeded(call.call());
        } catch (Exception exception) {
            throw new IllegalStateException("예상하지 못한 예외로 동시성 시도가 실패했습니다.", exception);
        }
    }

    private long successCount(List<AttemptResult> results) {
        return results.stream().filter(AttemptResult::success).count();
    }

    private record AttemptResult(boolean success, SharedTableBulkResponse response) {
        static AttemptResult succeeded(SharedTableBulkResponse response) {
            return new AttemptResult(true, response);
        }
    }
}
