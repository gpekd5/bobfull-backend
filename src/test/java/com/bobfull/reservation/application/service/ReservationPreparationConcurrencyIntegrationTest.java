package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.payment.entity.Payment;
import com.bobfull.payment.entity.PaymentPurpose;
import com.bobfull.payment.entity.PaymentStatus;
import com.bobfull.payment.repository.PaymentRepository;
import com.bobfull.reservation.presentation.dto.ReservationPrepareRequest;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
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
 * Issue #36: TimeSlot 행 잠금이 실제 MySQL에서도 CREATE·JOIN 경쟁을 직렬화하는지 검증하는
 * 선택적 통합 테스트다. BOBFULL_MYSQL_CONCURRENCY_TEST=true 일 때만 실행한다(ADR 0001).
 *
 * <p><b>주의</b>: {@code spring.jpa.hibernate.ddl-auto=create-drop}이라 실행할 때마다 대상 DB의
 * 모든 테이블을 지우고 다시 만든다. {@code BOBFULL_TEST_MYSQL_URL}은 반드시 로컬 개발용 DB(예:
 * {@code bobfull})가 아닌 별도 스키마(예: {@code bobfull_concurrency_test})를 가리켜야 한다.
 * 개발 DB를 가리키면 실행할 때마다 회원·식당·예약 등 실제 데이터가 전부 삭제된다(2026-08-03에
 * 실제로 발생한 사고).</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_CONCURRENCY_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payment.expiration.enabled=false",
        "jwt.secret=reservation-seat-concurrency-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-reservation-seat-concurrency-test-api-secret",
        "portone.store-id=portone-reservation-seat-concurrency-test-store-id",
        "portone.webhook-secret=d2hzZWNfcmVzZXJ2YXRpb24tc2VhdC1jb25jdXJyZW5jeQ=="
})
class ReservationPreparationConcurrencyIntegrationTest {

    @Autowired private ReservationPreparationService preparationService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private AvailableCapacityCalculator availableCapacityCalculator;

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAll();
        participantRepository.deleteAll();
        reservationRepository.deleteAll();
        timeSlotRepository.deleteAll();
        sharedTableRepository.deleteAll();
        restaurantRepository.deleteAll();
    }

    @Test
    void 같은_회차에_동시_CREATE_준비_요청이_들어오면_유효한_CREATE_READY_Payment가_한_건만_생성되고_Reservation은_아직_생성되지_않는다()
            throws Exception {
        TimeSlot timeSlot = timeSlot(4);

        List<AttemptResult> results = raceTwo(
                () -> preparationService.prepare(10L, new ReservationPrepareRequest(PaymentPurpose.CREATE, timeSlot.getId(), 1)),
                () -> preparationService.prepare(11L, new ReservationPrepareRequest(PaymentPurpose.CREATE, timeSlot.getId(), 1))
        );

        assertThat(successCount(results)).isEqualTo(1);
        assertThat(failureCodes(results)).containsExactly(ReservationErrorCode.ACTIVE_RESERVATION_ALREADY_EXISTS);
        // CREATE 준비는 READY Payment만 만들고, 실제 Reservation은 결제 완료 시점에 생성된다(ADR 0001).
        assertThat(reservationRepository.count()).isZero();
        List<Payment> readyPayments = paymentRepository.findAll().stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.READY)
                .toList();
        assertThat(readyPayments).hasSize(1);
        assertThat(readyPayments.get(0).getPurpose()).isEqualTo(PaymentPurpose.CREATE);
    }

    @Test
    void 마지막_좌석에_동시_JOIN_요청이_들어오면_하나만_성공하고_실제_참여_인원과_선점_인원의_합이_정원을_넘지_않는다() throws Exception {
        int capacity = 4;
        TimeSlot timeSlot = timeSlot(capacity);
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 1L));
        // 이미 capacity-1명이 확정 참여 중 → 잔여 좌석 1석만 남은 상태를 재현한다.
        for (long memberId = 1L; memberId <= capacity - 1; memberId++) {
            participantRepository.saveAndFlush(ReservationParticipant.create(reservation.getId(), memberId, 1));
        }

        List<AttemptResult> results = raceTwo(
                () -> preparationService.prepare(20L, new ReservationPrepareRequest(PaymentPurpose.JOIN, reservation.getId(), 1)),
                () -> preparationService.prepare(21L, new ReservationPrepareRequest(PaymentPurpose.JOIN, reservation.getId(), 1))
        );

        assertThat(successCount(results)).isEqualTo(1);
        assertThat(failureCodes(results)).containsExactly(ReservationErrorCode.INSUFFICIENT_REMAINING_CAPACITY);

        // 하드코딩한 산술식이 아니라, 실제 DB에서 참여 인원·선점 인원·잔여 좌석을 다시 조회해 검증한다.
        int actualConfirmedParticipants = participantRepository.sumPartySize(
                reservation.getId(), ParticipationStatus.RESERVED);
        long actualReadyHoldPartySize = paymentRepository.findAll().stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.READY)
                .filter(payment -> payment.getTimeSlotId().equals(timeSlot.getId()))
                .mapToLong(Payment::getPartySize)
                .sum();
        int actualAvailableCapacity = availableCapacityCalculator.calculate(timeSlot.getId(), capacity);
        long successfulReadyPayments = paymentRepository.findAll().stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.READY)
                .count();

        assertThat(actualConfirmedParticipants).isEqualTo(capacity - 1);
        assertThat(actualReadyHoldPartySize).isEqualTo(1);
        assertThat(actualConfirmedParticipants + actualReadyHoldPartySize).isEqualTo(capacity);
        assertThat(actualAvailableCapacity).isZero();
        assertThat(successfulReadyPayments).isEqualTo(1);
    }

    @Test
    void 같은_회원의_중복_JOIN_요청_중_하나만_성공한다() throws Exception {
        TimeSlot timeSlot = timeSlot(4);
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 1L));
        participantRepository.saveAndFlush(ReservationParticipant.create(reservation.getId(), 1L, 1));

        List<AttemptResult> results = raceTwo(
                () -> preparationService.prepare(30L, new ReservationPrepareRequest(PaymentPurpose.JOIN, reservation.getId(), 1)),
                () -> preparationService.prepare(30L, new ReservationPrepareRequest(PaymentPurpose.JOIN, reservation.getId(), 1))
        );

        assertThat(successCount(results)).isEqualTo(1);
        assertThat(failureCodes(results)).containsExactly(ReservationErrorCode.ACTIVE_RESERVATION_ALREADY_EXISTS);
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    private TimeSlot timeSlot(int capacity) {
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(1L, "동시성 테스트 식당", "제주시", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), capacity));
        return timeSlotRepository.saveAndFlush(TimeSlot.create(
                table.getId(), Instant.parse("2030-08-01T02:00:00Z"), Instant.parse("2030-08-01T04:00:00Z")));
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
