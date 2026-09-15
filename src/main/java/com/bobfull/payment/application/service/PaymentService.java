package com.bobfull.payment.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.payment.application.command.CreateReadyPaymentCommand;
import com.bobfull.payment.application.result.CreateReadyPaymentResult;
import com.bobfull.payment.application.port.PaymentHoldPort;
import com.bobfull.payment.application.port.ReadyPaymentPort;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 도메인이 전달한 계산 결과로 READY Payment를 생성·저장한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentService implements ReadyPaymentPort, PaymentHoldPort {

    private static final Duration READY_PAYMENT_EXPIRATION = Duration.ofMinutes(10);

    private final PaymentRepository paymentRepository;
    private final Clock clock;

    @Override
    @Transactional
    public CreateReadyPaymentResult createReadyPayment(CreateReadyPaymentCommand command) {
        Instant expiresAt = clock.instant().plus(READY_PAYMENT_EXPIRATION);
        Payment payment = Payment.createReady(
                UUID.randomUUID().toString(),
                command.memberId(),
                command.timeSlotId(),
                command.reservationId(),
                command.purpose(),
                command.partySize(),
                command.amount(),
                expiresAt
        );

        try {
            return CreateReadyPaymentResult.from(paymentRepository.saveAndFlush(payment));
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(PaymentErrorCode.DUPLICATE_PAYMENT_ID);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActiveReadyPayment(Long timeSlotId, PaymentPurpose purpose) {
        return paymentRepository.existsByTimeSlotIdAndPurposeAndStatusAndExpiresAtAfter(
                timeSlotId, purpose, PaymentStatus.READY, clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public int sumActiveReadyPartySize(Long timeSlotId) {
        return paymentRepository.sumPartySizeByTimeSlotIdAndStatusAndExpiresAtAfter(
                timeSlotId, PaymentStatus.READY, clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Integer> sumActiveReadyPartySizeByTimeSlotIds(Collection<Long> timeSlotIds) {
        if (timeSlotIds.isEmpty()) {
            return Map.of();
        }
        return paymentRepository
                .sumPartySizeByTimeSlotIdsAndStatusAndExpiresAtAfter(timeSlotIds, PaymentStatus.READY, clock.instant())
                .stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> ((Number) row[1]).intValue()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActiveJoinReadyPayment(Long reservationId, Long memberId) {
        return paymentRepository.existsByReservationIdAndMemberIdAndPurposeAndStatusAndExpiresAtAfter(
                reservationId, memberId, PaymentPurpose.JOIN, PaymentStatus.READY, clock.instant());
    }
}
