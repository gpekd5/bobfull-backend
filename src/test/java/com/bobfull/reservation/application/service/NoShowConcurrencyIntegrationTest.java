package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.infrastructure.repository.NoShowHistoryRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Instant;
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
 * PR #133 리뷰에서 지적된 노쇼 처리 동시성 문제(비관적 락 없이 조회 → 상태 확인 → 전이하면
 * 동시 요청이 둘 다 검증을 통과해 NoShowHistory가 중복 기록될 수 있음)가 실제 MySQL에서
 * {@code findWithLockByIdAndReservationId}의 PESSIMISTIC_WRITE 락으로 직렬화되는지 검증하는
 * 선택적 통합 테스트다. BOBFULL_MYSQL_CONCURRENCY_TEST=true 일 때만 실행한다.
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
        "jwt.secret=no-show-concurrency-test-secret-key-please-keep-this-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-no-show-concurrency-test-api-secret",
        "portone.store-id=portone-no-show-concurrency-test-store-id",
        "portone.webhook-secret=d2hzZWNfbm8tc2hvdy1jb25jdXJyZW5jeQ=="
})
class NoShowConcurrencyIntegrationTest {

    @Autowired private NoShowService noShowService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;
    @Autowired private NoShowHistoryRepository noShowHistoryRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private MemberRepository memberRepository;

    @AfterEach
    void cleanUp() {
        noShowHistoryRepository.deleteAll();
        participantRepository.deleteAll();
        reservationRepository.deleteAll();
        timeSlotRepository.deleteAll();
        sharedTableRepository.deleteAll();
        restaurantRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 같은_참여자에_동시_노쇼_처리_요청이_들어오면_하나만_성공하고_이력이_한_건만_남는다() throws Exception {
        Long ownerMemberId = 1L;
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(ownerMemberId, "동시성 테스트 식당", "제주시", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), 4));
        TimeSlot timeSlot = timeSlotRepository.saveAndFlush(TimeSlot.create(
                table.getId(), Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)));
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 99L));
        Member member = memberRepository.saveAndFlush(
                Member.createMember("noshow-race@example.com", "hash", "홍길동", "01099990000"));
        ReservationParticipant participant = participantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), member.getId(), 2));

        List<AttemptResult> results = raceTwo(
                () -> noShowService.markNoShow(ownerMemberId, reservation.getId(), participant.getId()),
                () -> noShowService.markNoShow(ownerMemberId, reservation.getId(), participant.getId())
        );

        assertThat(successCount(results)).isEqualTo(1);
        assertThat(failureCodes(results)).containsExactly(ReservationErrorCode.INVALID_STATE);
        assertThat(noShowHistoryRepository.findAll()).hasSize(1);
        assertThat(participantRepository.findById(participant.getId()).orElseThrow().getParticipationStatus())
                .isEqualTo(com.bobfull.reservation.domain.entity.ParticipationStatus.NO_SHOW);
    }

    private List<AttemptResult> raceTwo(Callable<?> first, Callable<?> second) throws Exception {
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

    private AttemptResult attempt(CountDownLatch start, Callable<?> call) throws InterruptedException {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            call.call();
            return AttemptResult.succeeded();
        } catch (CustomException exception) {
            return AttemptResult.failed(exception.getErrorCode());
        } catch (Exception exception) {
            throw new IllegalStateException("예상하지 못한 예외로 동시성 시도가 실패했습니다.", exception);
        }
    }

    private long successCount(List<AttemptResult> results) {
        return results.stream().filter(AttemptResult::success).count();
    }

    private List<Object> failureCodes(List<AttemptResult> results) {
        return results.stream().filter(result -> !result.success()).map(AttemptResult::errorCode).toList();
    }

    private record AttemptResult(boolean success, Object errorCode) {
        static AttemptResult succeeded() {
            return new AttemptResult(true, null);
        }

        static AttemptResult failed(Object errorCode) {
            return new AttemptResult(false, errorCode);
        }
    }
}
