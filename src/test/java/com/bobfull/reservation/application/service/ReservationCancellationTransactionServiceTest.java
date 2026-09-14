package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.notification.infrastructure.outbox.EmailOutboxEventService;
import com.bobfull.reservation.domain.CancellationScope;
import com.bobfull.reservation.presentation.dto.ReservationCancellationRequest;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import com.bobfull.reservation.application.port.ReservationCapacityReader;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationCancellationTransactionServiceTest {

    private static final Long TIME_SLOT_ID = 200L;
    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
    private static final Instant CANCELLABLE_START_AT = Instant.parse("2026-08-08T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final List<ParticipationStatus> OCCUPYING_STATUSES =
            List.of(ParticipationStatus.RESERVED, ParticipationStatus.CANCEL_REQUESTED);

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationParticipantRepository reservationParticipantRepository;

    @Mock
    private ReservationCapacityReader reservationCapacityReader;

    @Mock
    private TimeSlotRepository timeSlotRepository;

    @Mock
    private SharedTableRepository sharedTableRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private EmailOutboxEventService emailOutboxEventService;

    private ReservationCancellationTransactionService service() {
        return new ReservationCancellationTransactionService(
                reservationRepository, reservationParticipantRepository, reservationCapacityReader,
                timeSlotRepository, sharedTableRepository, restaurantRepository, CLOCK, emailOutboxEventService);
    }

    @Test
    void 예약을_찾을_수_없으면_RESERVATION_ID_NOT_FOUND를_반환한다() {
        // given
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(
                () -> service().accept(1L, 10L, new ReservationCancellationRequest("사유")));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_ID_NOT_FOUND);
    }

    @Test
    void 이미_취소된_예약이면_RESERVATION_ALREADY_CANCELLED를_반환한다() {
        // given
        Reservation reservation = reservation(10L, 1L);
        reservation.cancel();
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));

        // when
        Throwable result = catchThrowable(
                () -> service().accept(1L, 10L, new ReservationCancellationRequest("사유")));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_ALREADY_CANCELLED);
    }

    @Test
    void 이미_취소_접수된_CANCELLING_예약이면_RESERVATION_ALREADY_CANCELLED를_반환한다() {
        // given: 다른 참여자의 취소 접수로 이미 CANCELLING인 상태에서 또 취소를 시도
        Reservation reservation = reservation(10L, 1L);
        reservation.startCancelling();
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));

        // when
        Throwable result = catchThrowable(
                () -> service().accept(1L, 10L, new ReservationCancellationRequest("사유")));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_ALREADY_CANCELLED);
    }

    @Test
    void 식사_시작_2시간_이내면_CANCELLATION_DEADLINE_PASSED를_반환한다() {
        // given
        Reservation reservation = reservation(10L, 1L);
        ReservationParticipant participant = participant(20L, 10L, 1L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationParticipantRepository.findByReservationIdAndMemberId(10L, 1L))
                .willReturn(Optional.of(participant));
        given(reservationCapacityReader.readTimeSlotStartAt(TIME_SLOT_ID))
                .willReturn(Instant.parse("2026-08-08T01:30:00Z"));

        // when
        Throwable result = catchThrowable(
                () -> service().accept(1L, 10L, new ReservationCancellationRequest("사유")));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.CANCELLATION_DEADLINE_PASSED);
    }

    @Test
    void 취소_기한_경계_시각에는_취소를_접수할_수_있다() {
        // given: startAt - 2h == now
        Reservation reservation = reservation(10L, 1L);
        ReservationParticipant participant = participant(20L, 10L, 1L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationCapacityReader.readTimeSlotStartAt(TIME_SLOT_ID))
                .willReturn(Instant.parse("2026-08-08T02:00:00Z"));
        given(reservationParticipantRepository.findByReservationIdAndMemberId(10L, 1L))
                .willReturn(Optional.of(participant));
        given(reservationParticipantRepository.findAllByReservationIdAndParticipationStatus(10L, ParticipationStatus.RESERVED))
                .willReturn(List.of(participant));

        // when
        ReservationCancellationTransactionService.CancellationAcceptance acceptance =
                service().accept(1L, 10L, new ReservationCancellationRequest("사유"));

        // then
        assertThat(acceptance.scope()).isEqualTo(CancellationScope.RESERVATION);
    }

    @Test
    void 본인_참여를_찾을_수_없으면_PARTICIPATION_NOT_FOUND를_반환한다() {
        // given: Reservation에 참여자가 하나도 없는 데이터 정합성 붕괴 상황(정상 흐름에서는 발생하지 않음)
        Reservation reservation = reservation(10L, 1L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationParticipantRepository.findByReservationIdAndMemberId(10L, 2L)).willReturn(Optional.empty());
        given(reservationParticipantRepository.existsByReservationId(10L)).willReturn(false);

        // when
        Throwable result = catchThrowable(
                () -> service().accept(2L, 10L, new ReservationCancellationRequest("사유")));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.PARTICIPATION_NOT_FOUND);
    }

    @Test
    void 본인_참여가_아니면_ACCESS_DENIED를_반환한다() {
        // given: 예약에 다른 회원의 참여는 있지만 요청자 본인의 참여는 없음
        Reservation reservation = reservation(10L, 1L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationParticipantRepository.findByReservationIdAndMemberId(10L, 2L)).willReturn(Optional.empty());
        given(reservationParticipantRepository.existsByReservationId(10L)).willReturn(true);

        // when
        Throwable result = catchThrowable(
                () -> service().accept(2L, 10L, new ReservationCancellationRequest("사유")));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void 이미_취소된_참여자는_PARTICIPATION_ALREADY_CANCELLED를_반환한다() {
        // given
        Reservation reservation = reservation(10L, 1L);
        ReservationParticipant participant = participant(21L, 10L, 2L);
        participant.requestCancel("이전 취소");
        participant.completeCancel(NOW.minusSeconds(60));
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationParticipantRepository.findByReservationIdAndMemberId(10L, 2L)).willReturn(Optional.of(participant));

        // when
        Throwable result = catchThrowable(
                () -> service().accept(2L, 10L, new ReservationCancellationRequest("사유")));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.PARTICIPATION_ALREADY_CANCELLED);
    }

    @Test
    void NO_SHOW_참여자는_CANCELLATION_NOT_ALLOWED를_반환한다() {
        // given
        Reservation reservation = reservation(10L, 1L);
        ReservationParticipant participant = participant(21L, 10L, 2L);
        ReflectionTestUtils.setField(participant, "participationStatus", ParticipationStatus.NO_SHOW);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationParticipantRepository.findByReservationIdAndMemberId(10L, 2L)).willReturn(Optional.of(participant));

        // when
        Throwable result = catchThrowable(
                () -> service().accept(2L, 10L, new ReservationCancellationRequest("사유")));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.CANCELLATION_NOT_ALLOWED);
    }

    @Test
    void 최초_예약자가_취소를_접수하면_예약이_CANCELLING되고_유효_참여자_전원이_CANCEL_REQUESTED된다() {
        // given
        Reservation reservation = reservation(10L, 1L);
        ReservationParticipant creator = participant(20L, 10L, 1L);
        ReservationParticipant other = participant(21L, 10L, 2L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationCapacityReader.readTimeSlotStartAt(TIME_SLOT_ID)).willReturn(CANCELLABLE_START_AT);
        given(reservationParticipantRepository.findByReservationIdAndMemberId(10L, 1L)).willReturn(Optional.of(creator));
        given(reservationParticipantRepository.findAllByReservationIdAndParticipationStatus(10L, ParticipationStatus.RESERVED))
                .willReturn(List.of(creator, other));

        // when
        ReservationCancellationTransactionService.CancellationAcceptance acceptance =
                service().accept(1L, 10L, new ReservationCancellationRequest("개인 사정"));

        // then
        assertThat(acceptance.scope()).isEqualTo(CancellationScope.RESERVATION);
        assertThat(acceptance.actingParticipantId()).isEqualTo(20L);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CANCELLING);
        assertThat(creator.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        assertThat(other.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        assertThat(creator.getCancelReason()).isEqualTo("개인 사정");
        assertThat(other.getCancelReason()).isEqualTo("개인 사정");

        assertThat(acceptance.refundCommand().reservationParticipantIds()).containsExactlyInAnyOrder(20L, 21L);
        assertThat(acceptance.refundCommand().requesterMemberId()).isEqualTo(1L);
    }

    @Test
    void 모집이_OPEN이면_기준_미달이_되더라도_접수_시점에는_Reservation_상태를_바꾸지_않는다() {
        // given: CONFIRMED + OPEN. 이 참여자가 취소돼도 모집이 OPEN이므로 캐스케이드 대상이 아니다.
        // Reservation의 RECRUITING/CONFIRMED 재계산은 환불 완료 후 recalculateAfterCompletion에서 수행한다.
        Reservation reservation = reservation(10L, 1L);
        reservation.confirm();
        ReservationParticipant participant = participant(22L, 10L, 3L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationCapacityReader.readTimeSlotStartAt(TIME_SLOT_ID)).willReturn(CANCELLABLE_START_AT);
        given(reservationParticipantRepository.findByReservationIdAndMemberId(10L, 3L)).willReturn(Optional.of(participant));

        // when
        ReservationCancellationTransactionService.CancellationAcceptance acceptance =
                service().accept(3L, 10L, new ReservationCancellationRequest("사유"));

        // then
        assertThat(acceptance.scope()).isEqualTo(CancellationScope.PARTICIPATION);
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.OPEN);
        verify(reservationCapacityReader, never()).readTableCapacity(any());
        verify(reservationParticipantRepository, never()).sumPartySizeByStatuses(any(), any());
    }

    @Test
    void recalculateAfterCompletion은_기준_미달이면_RECRUITING으로_되돌린다() {
        // given: capacity 4, threshold 3. 환불 완료 후 남은 인원 2명으로 기준 미달.
        // Issue #264: 잠금 없는 SUM 집계 대신 잠금 조회 목록을 합산하므로 목록으로 준비한다.
        Reservation reservation = reservation(10L, 1L);
        reservation.confirm();
        given(reservationCapacityReader.readTableCapacity(TIME_SLOT_ID)).willReturn(4);
        given(reservationParticipantRepository.findAllWithLockByReservationIdAndParticipationStatusIn(10L, OCCUPYING_STATUSES))
                .willReturn(List.of(participant(22L, 10L, 3L), participant(23L, 10L, 4L)));

        // when
        service().recalculateAfterCompletion(reservation);

        // then
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.RECRUITING);
    }

    @Test
    void recalculateAfterCompletion은_기준_이상이면_CONFIRMED를_유지한다() {
        // given: capacity 4, threshold 3. 환불 완료 후 남은 인원 3명으로 기준 충족.
        // Issue #264: 잠금 없는 SUM 집계 대신 잠금 조회 목록을 합산하므로 목록으로 준비한다.
        Reservation reservation = reservation(10L, 1L);
        reservation.confirm();
        given(reservationCapacityReader.readTableCapacity(TIME_SLOT_ID)).willReturn(4);
        given(reservationParticipantRepository.findAllWithLockByReservationIdAndParticipationStatusIn(10L, OCCUPYING_STATUSES))
                .willReturn(List.of(participant(22L, 10L, 3L), participant(23L, 10L, 4L), participant(24L, 10L, 5L)));

        // when
        service().recalculateAfterCompletion(reservation);

        // then
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void 모집이_CLOSED이고_기준_미달이면_재오픈하지_않고_본인과_남은_참여자를_한번에_취소_접수한다() {
        // given: CONFIRMED + CLOSED, capacity 4, threshold 3. 취소 전 잔여 3명(본인 partySize 1 포함)이라
        // 취소 후 2명으로 기준 미달 -> 본인+나머지 참여자를 한 번의 RefundRequestCommand로 묶어 예약 전체를 취소 접수한다.
        Reservation reservation = reservation(10L, 1L);
        reservation.confirm();
        reservation.closeRecruitment();
        ReservationParticipant cancelling = participant(23L, 10L, 4L);
        ReservationParticipant remaining = participant(24L, 10L, 1L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationCapacityReader.readTimeSlotStartAt(TIME_SLOT_ID)).willReturn(CANCELLABLE_START_AT);
        given(reservationParticipantRepository.findByReservationIdAndMemberId(10L, 4L)).willReturn(Optional.of(cancelling));
        given(reservationCapacityReader.readTableCapacity(TIME_SLOT_ID)).willReturn(4);
        given(reservationParticipantRepository.sumPartySizeByStatuses(10L, OCCUPYING_STATUSES)).willReturn(3);
        given(reservationParticipantRepository.findAllByReservationIdAndParticipationStatus(10L, ParticipationStatus.RESERVED))
                .willReturn(List.of(cancelling, remaining));

        // when
        ReservationCancellationTransactionService.CancellationAcceptance acceptance =
                service().accept(4L, 10L, new ReservationCancellationRequest("사유"));

        // then
        assertThat(acceptance.scope()).isEqualTo(CancellationScope.RESERVATION);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CANCELLING);
        assertThat(reservation.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
        assertThat(cancelling.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        assertThat(remaining.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        assertThat(acceptance.refundCommand().reservationParticipantIds()).containsExactlyInAnyOrder(23L, 24L);
    }

    @Test
    void 모집이_CLOSED이고_기준_이상이면_CONFIRMED_CLOSED를_유지한다() {
        // given: capacity 4, threshold 3. 취소 전 잔여 4명(본인 partySize 1 포함)이라 취소 후 3명으로 기준 충족.
        Reservation reservation = reservation(10L, 1L);
        reservation.confirm();
        reservation.closeRecruitment();
        ReservationParticipant participant = participant(25L, 10L, 4L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationCapacityReader.readTimeSlotStartAt(TIME_SLOT_ID)).willReturn(CANCELLABLE_START_AT);
        given(reservationParticipantRepository.findByReservationIdAndMemberId(10L, 4L)).willReturn(Optional.of(participant));
        given(reservationCapacityReader.readTableCapacity(TIME_SLOT_ID)).willReturn(4);
        given(reservationParticipantRepository.sumPartySizeByStatuses(10L, OCCUPYING_STATUSES)).willReturn(4);

        // when
        service().accept(4L, 10L, new ReservationCancellationRequest("사유"));

        // then
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
        verify(reservationParticipantRepository, never())
                .findAllByReservationIdAndParticipationStatus(10L, ParticipationStatus.RESERVED);
    }

    @Test
    void acceptByOwner_예약을_찾을_수_없으면_RESERVATION_ID_NOT_FOUND를_반환한다() {
        // given
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> service().acceptByOwner(1L, 10L, "식당 내부 사정"));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_ID_NOT_FOUND);
    }

    @Test
    void acceptByOwner_TimeSlot을_찾을_수_없으면_RESERVATION_ID_NOT_FOUND를_반환한다() {
        // given
        Reservation reservation = reservation(10L, 5L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(TIME_SLOT_ID)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> service().acceptByOwner(1L, 10L, "식당 내부 사정"));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_ID_NOT_FOUND);
    }

    @Test
    void acceptByOwner_본인_식당이_아니면_ACCESS_DENIED를_반환한다() {
        // given
        Reservation reservation = reservation(10L, 5L);
        TimeSlot timeSlot = timeSlot(100L);
        SharedTable sharedTable = sharedTable(1000L);
        Restaurant restaurant = restaurant(2000L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(TIME_SLOT_ID)).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1000L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> service().acceptByOwner(1L, 10L, "식당 내부 사정"));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void acceptByOwner_이미_취소된_예약이면_INVALID_STATE를_반환한다() {
        // given
        Reservation reservation = reservation(10L, 5L);
        reservation.startCancelling();
        reservation.cancel();
        givenOwnedChain(reservation, 1L);

        // when
        Throwable result = catchThrowable(() -> service().acceptByOwner(1L, 10L, "식당 내부 사정"));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_STATE);
    }

    @Test
    void acceptByOwner_이미_취소_접수된_CANCELLING_예약이면_INVALID_STATE를_반환한다() {
        // given
        Reservation reservation = reservation(10L, 5L);
        reservation.startCancelling();
        givenOwnedChain(reservation, 1L);

        // when
        Throwable result = catchThrowable(() -> service().acceptByOwner(1L, 10L, "식당 내부 사정"));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_STATE);
    }

    @Test
    void acceptByOwner_CLOSED_예약이면_INVALID_STATE를_반환하고_참여자_전이나_환불_명령을_만들지_않는다() {
        // given: 식사 종료(Issue #175)로 CLOSED된 예약은 OWNER 취소로도 다시 CANCELLING될 수 없다
        Reservation reservation = reservation(10L, 5L);
        reservation.confirm();
        reservation.close();
        givenOwnedChain(reservation, 1L);

        // when
        Throwable result = catchThrowable(() -> service().acceptByOwner(1L, 10L, "식당 내부 사정"));

        // then
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_STATE);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CLOSED);
        verify(reservationParticipantRepository, never())
                .findAllByReservationIdAndParticipationStatus(10L, ParticipationStatus.RESERVED);
    }

    @Test
    void acceptByOwner가_성공하면_예약이_CANCELLING되고_유효_참여자_전원이_CANCEL_REQUESTED되며_NO_SHOW는_제외된다() {
        // given
        Reservation reservation = reservation(10L, 5L);
        givenOwnedChain(reservation, 1L);
        ReservationParticipant creator = participant(20L, 10L, 5L);
        ReservationParticipant other = participant(21L, 10L, 6L);
        given(reservationParticipantRepository.findAllByReservationIdAndParticipationStatus(10L, ParticipationStatus.RESERVED))
                .willReturn(List.of(creator, other));

        // when
        ReservationCancellationTransactionService.OwnerCancellationAcceptance acceptance =
                service().acceptByOwner(1L, 10L, "식당 내부 사정");

        // then
        assertThat(acceptance.reservationId()).isEqualTo(10L);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CANCELLING);
        assertThat(creator.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        assertThat(other.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        assertThat(creator.getCancelReason()).isEqualTo("식당 내부 사정");
        assertThat(other.getCancelReason()).isEqualTo("식당 내부 사정");

        ReservationCancellationRefundPort.RefundRequestCommand command = acceptance.refundCommand();
        assertThat(command.reservationId()).isEqualTo(10L);
        assertThat(command.reservationParticipantIds()).containsExactlyInAnyOrder(20L, 21L);
        assertThat(command.requesterMemberId()).isEqualTo(1L);
        assertThat(command.cancelReason()).isEqualTo("식당 내부 사정");
        verify(reservationCapacityReader, never()).readTimeSlotStartAt(any());
    }

    @Test
    void acceptRecruitmentDeadline_이미_모집_마감된_예약은_ALREADY_PROCESSED로_아무것도_바꾸지_않는다() {
        // given
        Reservation reservation = reservation(10L, 5L);
        reservation.closeRecruitment();
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));

        // when
        ReservationCancellationTransactionService.RecruitmentDeadlineAcceptance acceptance =
                service().acceptRecruitmentDeadline(10L);

        // then
        assertThat(acceptance.outcome())
                .isEqualTo(ReservationCancellationTransactionService.RecruitmentDeadlineOutcome.ALREADY_PROCESSED);
        assertThat(acceptance.refundCommand()).isNull();
        verify(reservationParticipantRepository, never()).findAllByReservationIdAndParticipationStatus(any(), any());
        verify(emailOutboxEventService, never()).enqueue(any(), any(), any());
    }

    @Test
    void acceptRecruitmentDeadline_이미_취소_진행중인_예약은_ALREADY_PROCESSED로_아무것도_바꾸지_않는다() {
        // given
        Reservation reservation = reservation(10L, 5L);
        reservation.startCancelling();
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));

        // when
        ReservationCancellationTransactionService.RecruitmentDeadlineAcceptance acceptance =
                service().acceptRecruitmentDeadline(10L);

        // then
        assertThat(acceptance.outcome())
                .isEqualTo(ReservationCancellationTransactionService.RecruitmentDeadlineOutcome.ALREADY_PROCESSED);
        verify(reservationCapacityReader, never()).readTableCapacity(any());
        verify(emailOutboxEventService, never()).enqueue(any(), any(), any());
    }

    @Test
    void acceptRecruitmentDeadline_확정_기준_이상이면_모집만_마감하고_취소하지_않는다() {
        // given: capacity 4, threshold 3, 현재 인원 3명(기준 충족)
        Reservation reservation = reservation(10L, 5L);
        reservation.confirm();
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationCapacityReader.readTableCapacity(TIME_SLOT_ID)).willReturn(4);
        given(reservationParticipantRepository.sumPartySizeByStatuses(10L, OCCUPYING_STATUSES)).willReturn(3);

        // when
        ReservationCancellationTransactionService.RecruitmentDeadlineAcceptance acceptance =
                service().acceptRecruitmentDeadline(10L);

        // then
        assertThat(acceptance.outcome())
                .isEqualTo(ReservationCancellationTransactionService.RecruitmentDeadlineOutcome.CLOSED_ONLY);
        assertThat(acceptance.refundCommand()).isNull();
        assertThat(reservation.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(emailOutboxEventService).enqueue(any(), any(), any());
    }

    @Test
    void acceptRecruitmentDeadline_확정_기준_미달이면_모집_마감과_동시에_유효_참여자_전원을_취소_접수한다() {
        // given: capacity 4, threshold 3, 현재 인원 2명(기준 미달)
        Reservation reservation = reservation(10L, 5L);
        ReservationParticipant creator = participant(20L, 10L, 5L);
        ReservationParticipant other = participant(21L, 10L, 6L);
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(reservationCapacityReader.readTableCapacity(TIME_SLOT_ID)).willReturn(4);
        given(reservationParticipantRepository.sumPartySizeByStatuses(10L, OCCUPYING_STATUSES)).willReturn(2);
        given(reservationParticipantRepository.findAllByReservationIdAndParticipationStatus(10L, ParticipationStatus.RESERVED))
                .willReturn(List.of(creator, other));

        // when
        ReservationCancellationTransactionService.RecruitmentDeadlineAcceptance acceptance =
                service().acceptRecruitmentDeadline(10L);

        // then
        assertThat(acceptance.outcome())
                .isEqualTo(ReservationCancellationTransactionService.RecruitmentDeadlineOutcome.CANCELLED);
        assertThat(reservation.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CANCELLING);
        assertThat(creator.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        assertThat(other.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCEL_REQUESTED);

        ReservationCancellationRefundPort.RefundRequestCommand command = acceptance.refundCommand();
        assertThat(command.reservationId()).isEqualTo(10L);
        assertThat(command.reservationParticipantIds()).containsExactlyInAnyOrder(20L, 21L);
        assertThat(command.requesterMemberId()).isEqualTo(5L);
        assertThat(command.cancelReason()).isEqualTo("모집 마감 기준 인원 미달로 자동 취소되었습니다");
        verify(emailOutboxEventService).enqueue(any(), any(), any());
    }

    private void givenOwnedChain(Reservation reservation, Long ownerMemberId) {
        TimeSlot timeSlot = timeSlot(100L);
        SharedTable sharedTable = sharedTable(1000L);
        Restaurant restaurant = restaurant(ownerMemberId);
        given(reservationRepository.findWithLockById(reservation.getId())).willReturn(Optional.of(reservation));
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(TIME_SLOT_ID)).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1000L)).willReturn(Optional.of(restaurant));
    }

    private TimeSlot timeSlot(Long sharedTableId) {
        TimeSlot timeSlot = TimeSlot.create(sharedTableId, CANCELLABLE_START_AT.minusSeconds(3600), CANCELLABLE_START_AT);
        ReflectionTestUtils.setField(timeSlot, "id", TIME_SLOT_ID);
        return timeSlot;
    }

    private SharedTable sharedTable(Long restaurantId) {
        SharedTable sharedTable = SharedTable.create(restaurantId, 4);
        ReflectionTestUtils.setField(sharedTable, "id", 100L);
        return sharedTable;
    }

    private Restaurant restaurant(Long ownerMemberId) {
        Restaurant restaurant = Restaurant.create(ownerMemberId, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000);
        ReflectionTestUtils.setField(restaurant, "id", 1000L);
        return restaurant;
    }

    private Reservation reservation(Long id, Long creatorMemberId) {
        Reservation reservation = Reservation.create(TIME_SLOT_ID, creatorMemberId);
        ReflectionTestUtils.setField(reservation, "id", id);
        return reservation;
    }

    private ReservationParticipant participant(Long id, Long reservationId, Long memberId) {
        ReservationParticipant participant = ReservationParticipant.create(reservationId, memberId, 1);
        ReflectionTestUtils.setField(participant, "id", id);
        return participant;
    }
}
