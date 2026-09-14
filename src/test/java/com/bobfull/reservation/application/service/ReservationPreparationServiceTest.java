package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.payment.application.dto.CreateReadyPaymentCommand;
import com.bobfull.payment.application.dto.CreateReadyPaymentResult;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.application.port.PaymentHoldReader;
import com.bobfull.payment.application.port.ReadyPaymentCreator;
import com.bobfull.reservation.presentation.dto.ReservationAvailabilityResponse;
import com.bobfull.reservation.presentation.dto.ReservationPrepareRequest;
import com.bobfull.reservation.presentation.dto.ReservationPrepareResponse;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.application.port.ReservationTargetReader;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationPreparationServiceTest {

    @Mock private ReservationTargetReader reservationTargetReader;
    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationParticipantRepository reservationParticipantRepository;
    @Mock private PaymentHoldReader paymentHoldReader;
    @Mock private ReadyPaymentCreator readyPaymentCreator;
    @Mock private AvailableCapacityCalculator availableCapacityCalculator;

    private ReservationPreparationService service() {
        return new ReservationPreparationService(reservationTargetReader, reservationRepository,
                reservationParticipantRepository, paymentHoldReader, readyPaymentCreator, availableCapacityCalculator);
    }

    @Test
    void CREATE_예약_가능_여부는_잠금_없이_대상을_조회한다() {
        // given
        given(reservationTargetReader.read(200L, false)).willReturn(target());
        given(reservationRepository.existsByTimeSlotIdAndReservationStatusIn(any(), any())).willReturn(false);
        given(paymentHoldReader.existsActiveReadyPayment(200L, PaymentPurpose.CREATE)).willReturn(false);
        given(availableCapacityCalculator.calculate(200L, 4)).willReturn(4);

        // when
        ReservationAvailabilityResponse response = service().checkAvailability(1L, PaymentPurpose.CREATE, 200L, 2);

        // then
        assertThat(response.availableCapacity()).isEqualTo(4);
        verify(reservationTargetReader).read(200L, false);
        verify(reservationTargetReader, never()).read(200L, true);
    }

    @Test
    void CREATE_결제_준비는_잠금_조회_결과로_READY_결제를_생성한다() {
        // given
        given(reservationTargetReader.read(200L, true)).willReturn(target());
        given(reservationRepository.existsByTimeSlotIdAndReservationStatusIn(any(), any())).willReturn(false);
        given(paymentHoldReader.existsActiveReadyPayment(200L, PaymentPurpose.CREATE)).willReturn(false);
        given(availableCapacityCalculator.calculate(200L, 4)).willReturn(4);
        given(readyPaymentCreator.createReadyPayment(any())).willReturn(readyPayment("payment-id", 20000));

        // when
        ReservationPrepareResponse response = service().prepare(1L, new ReservationPrepareRequest(PaymentPurpose.CREATE, 200L, 2));

        // then
        ArgumentCaptor<CreateReadyPaymentCommand> captor = ArgumentCaptor.forClass(CreateReadyPaymentCommand.class);
        verify(readyPaymentCreator).createReadyPayment(captor.capture());
        assertThat(captor.getValue().timeSlotId()).isEqualTo(200L);
        assertThat(captor.getValue().reservationId()).isNull();
        assertThat(captor.getValue().amount()).isEqualByComparingTo(BigDecimal.valueOf(20000));
        assertThat(response.paymentId()).isEqualTo("payment-id");
    }

    @Test
    void CREATE_partySize가_테이블_정원을_초과하면_예외가_발생한다() {
        // given
        given(reservationTargetReader.read(200L, false)).willReturn(target());

        // when
        Throwable result = catchThrowable(() -> service().checkAvailability(1L, PaymentPurpose.CREATE, 200L, 5));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_PARTY_SIZE);
    }

    @Test
    void CREATE_활성_예약이_있으면_예외가_발생한다() {
        // given
        given(reservationTargetReader.read(200L, false)).willReturn(target());
        given(reservationRepository.existsByTimeSlotIdAndReservationStatusIn(any(), any())).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> service().checkAvailability(1L, PaymentPurpose.CREATE, 200L, 2));

        // then
        assertThat(((CustomException) result).getErrorCode())
                .isEqualTo(ReservationErrorCode.ACTIVE_RESERVATION_ALREADY_EXISTS);
    }

    @Test
    void CREATE_식사_종료로_CLOSED된_회차는_재예약이_차단된다() {
        // given: 이 회차의 유일한 예약이 CLOSED(식사 종료)여도 재예약을 막아야 한다(Issue #175 회귀 수정)
        given(reservationTargetReader.read(200L, false)).willReturn(target());
        ArgumentCaptor<List<ReservationStatus>> statusesCaptor = ArgumentCaptor.forClass(List.class);
        given(reservationRepository.existsByTimeSlotIdAndReservationStatusIn(eq(200L), statusesCaptor.capture()))
                .willReturn(true);

        // when
        Throwable result = catchThrowable(() -> service().checkAvailability(1L, PaymentPurpose.CREATE, 200L, 2));

        // then
        assertThat(((CustomException) result).getErrorCode())
                .isEqualTo(ReservationErrorCode.ACTIVE_RESERVATION_ALREADY_EXISTS);
        assertThat(statusesCaptor.getValue()).contains(ReservationStatus.CLOSED);
    }

    @Test
    void JOIN_예약_가능_여부는_대상_정원과_잔여_인원을_사용한다() {
        // given
        Reservation reservation = reservation(10L, 200L);
        given(reservationRepository.findById(10L)).willReturn(Optional.of(reservation));
        given(reservationTargetReader.read(200L, false)).willReturn(target());
        given(reservationParticipantRepository.existsByReservationIdAndMemberId(10L, 2L)).willReturn(false);
        given(paymentHoldReader.existsActiveJoinReadyPayment(10L, 2L)).willReturn(false);
        given(availableCapacityCalculator.calculate(200L, 4)).willReturn(2);

        // when
        ReservationAvailabilityResponse response = service().checkAvailability(2L, PaymentPurpose.JOIN, 10L, 2);

        // then
        assertThat(response.availableCapacity()).isEqualTo(2);
    }

    @Test
    void JOIN_모집이_마감된_예약은_예외가_발생한다() {
        // given
        Reservation reservation = reservation(10L, 200L);
        ReflectionTestUtils.setField(reservation, "recruitmentStatus", RecruitmentStatus.CLOSED);
        given(reservationRepository.findById(10L)).willReturn(Optional.of(reservation));
        given(reservationTargetReader.read(200L, false)).willReturn(target());

        // when
        Throwable result = catchThrowable(() -> service().checkAvailability(2L, PaymentPurpose.JOIN, 10L, 1));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_STATE);
    }

    @Test
    void JOIN_활성_READY_결제가_있으면_예외가_발생한다() {
        // given
        Reservation reservation = reservation(10L, 200L);
        given(reservationRepository.findById(10L)).willReturn(Optional.of(reservation));
        given(reservationTargetReader.read(200L, false)).willReturn(target());
        given(reservationParticipantRepository.existsByReservationIdAndMemberId(10L, 2L)).willReturn(false);
        given(paymentHoldReader.existsActiveJoinReadyPayment(10L, 2L)).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> service().checkAvailability(2L, PaymentPurpose.JOIN, 10L, 1));

        // then
        assertThat(((CustomException) result).getErrorCode())
                .isEqualTo(ReservationErrorCode.ACTIVE_RESERVATION_ALREADY_EXISTS);
    }

    @Test
    void JOIN_partySize가_잔여_인원을_초과하면_예외가_발생한다() {
        // given
        Reservation reservation = reservation(10L, 200L);
        given(reservationRepository.findById(10L)).willReturn(Optional.of(reservation));
        given(reservationTargetReader.read(200L, false)).willReturn(target());
        given(reservationParticipantRepository.existsByReservationIdAndMemberId(10L, 2L)).willReturn(false);
        given(paymentHoldReader.existsActiveJoinReadyPayment(10L, 2L)).willReturn(false);
        given(availableCapacityCalculator.calculate(200L, 4)).willReturn(1);

        // when
        Throwable result = catchThrowable(() -> service().checkAvailability(2L, PaymentPurpose.JOIN, 10L, 2));

        // then
        assertThat(((CustomException) result).getErrorCode())
                .isEqualTo(ReservationErrorCode.INSUFFICIENT_REMAINING_CAPACITY);
    }

    @Test
    void JOIN_결제_준비는_예약과_회차_잠금_순서를_유지한다() {
        // given
        Reservation reservation = reservation(10L, 200L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationTargetReader.read(200L, true)).willReturn(target());
        given(reservationParticipantRepository.existsByReservationIdAndMemberId(10L, 2L)).willReturn(false);
        given(paymentHoldReader.existsActiveJoinReadyPayment(10L, 2L)).willReturn(false);
        given(availableCapacityCalculator.calculate(200L, 4)).willReturn(2);
        given(readyPaymentCreator.createReadyPayment(any())).willReturn(readyPayment("payment-id", 10000));

        // when
        service().prepare(2L, new ReservationPrepareRequest(PaymentPurpose.JOIN, 10L, 1));

        // then
        verify(reservationRepository).findWithLockById(10L);
        verify(reservationTargetReader).read(200L, true);
    }

    @Test
    void JOIN_같은_회원의_두번째_READY_결제_준비는_거절된다() {
        // given
        Reservation reservation = reservation(10L, 200L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationTargetReader.read(200L, true)).willReturn(target());
        given(reservationParticipantRepository.existsByReservationIdAndMemberId(10L, 2L)).willReturn(false);
        given(paymentHoldReader.existsActiveJoinReadyPayment(10L, 2L)).willReturn(false).willReturn(true);
        given(availableCapacityCalculator.calculate(200L, 4)).willReturn(2);
        given(readyPaymentCreator.createReadyPayment(any())).willReturn(readyPayment("payment-id", 10000));

        // when
        service().prepare(2L, new ReservationPrepareRequest(PaymentPurpose.JOIN, 10L, 1));
        Throwable result = catchThrowable(() -> service().prepare(2L,
                new ReservationPrepareRequest(PaymentPurpose.JOIN, 10L, 1)));

        // then
        assertThat(((CustomException) result).getErrorCode())
                .isEqualTo(ReservationErrorCode.ACTIVE_RESERVATION_ALREADY_EXISTS);
        verify(readyPaymentCreator).createReadyPayment(any());
    }

    private ReservationTargetReader.ReservationTarget target() {
        return new ReservationTargetReader.ReservationTarget(200L, 4, 10000);
    }

    private CreateReadyPaymentResult readyPayment(String paymentId, int amount) {
        return new CreateReadyPaymentResult(paymentId, PaymentStatus.READY, BigDecimal.valueOf(amount),
                Instant.parse("2026-07-25T08:10:00Z"));
    }

    private Reservation reservation(Long id, Long timeSlotId) {
        Reservation reservation = Reservation.create(timeSlotId, 1L);
        ReflectionTestUtils.setField(reservation, "id", id);
        return reservation;
    }
}
