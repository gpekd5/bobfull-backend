package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.restaurant.restaurant.domain.exception.RestaurantErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.response.PageResponse;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.reservation.presentation.response.NoShowCandidateResponse;
import com.bobfull.reservation.presentation.response.NoShowCustomerResponse;
import com.bobfull.reservation.application.result.NoShowCustomerResult;
import com.bobfull.reservation.presentation.response.NoShowHistoryResponse;
import com.bobfull.reservation.application.result.NoShowHistoryResult;
import com.bobfull.reservation.presentation.response.NoShowProcessResponse;
import com.bobfull.reservation.domain.entity.NoShowHistory;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.infrastructure.repository.NoShowHistoryRepository;
import com.bobfull.reservation.infrastructure.repository.query.NoShowQueryRepository;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 식당 소유권과 식사 종료를 검증해 참여자의 노쇼 상태와 이력을 관리한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class NoShowService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final NoShowHistoryRepository noShowHistoryRepository;
    private final NoShowQueryRepository noShowQueryRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final SharedTableRepository sharedTableRepository;
    private final RestaurantRepository restaurantRepository;
    private final MemberRepository memberRepository;
    private final Clock clock;
    private final BusinessMetricRecorder businessMetricRecorder;

    @Transactional(readOnly = true)
    public PageResponse<NoShowCandidateResponse> getCandidates(Long ownerMemberId, Long reservationId, Pageable pageable) {
        OwnershipContext context = resolveOwnership(reservationId, ownerMemberId);
        requireDiningEnded(context.timeSlot());

        Page<ReservationParticipant> participants = reservationParticipantRepository
                .findAllByReservationIdAndParticipationStatus(reservationId, ParticipationStatus.RESERVED, pageable);
        Map<Long, Member> membersById = fetchMembersById(participants.getContent());
        return PageResponse.from(participants.map(
                participant -> NoShowCandidateResponse.of(participant, membersById.get(participant.getMemberId()).getName())));
    }

    // 식사가 끝난 참여자를 잠가 노쇼 상태와 처리 이력을 함께 기록한다.
    @Transactional
    public NoShowProcessResponse markNoShow(Long ownerMemberId, Long reservationId, Long participationId) {
        OwnershipContext context = resolveOwnership(reservationId, ownerMemberId);
        requireDiningEnded(context.timeSlot());

        ReservationParticipant participant = findParticipantWithLockOrThrow(reservationId, participationId);
        if (participant.getParticipationStatus() != ParticipationStatus.RESERVED) {
            throw new CustomException(ReservationErrorCode.INVALID_STATE);
        }
        participant.markNoShow();
        noShowHistoryRepository.save(NoShowHistory.marked(participant.getId(), ownerMemberId, clock.instant()));
        log.info("event=NO_SHOW_MARKED reservationId={} participantId={} actorId={} afterStatus={}",
                reservationId, participationId, ownerMemberId, participant.getParticipationStatus());
        // 롤백된 노쇼 처리가 운영 지표에 포함되지 않도록 커밋 뒤에 기록한다.
        AfterCommitExecutor.run(() -> businessMetricRecorder.increment(BusinessMetricEvent.NO_SHOW_MARKED));
        return new NoShowProcessResponse(reservationId, participationId);
    }

    // 노쇼 참여자를 잠가 예약 참여 상태로 복구하고 해제 이력을 기록한다.
    @Transactional
    public NoShowProcessResponse unmarkNoShow(Long ownerMemberId, Long reservationId, Long participationId) {
        resolveOwnership(reservationId, ownerMemberId);

        ReservationParticipant participant = findParticipantWithLockOrThrow(reservationId, participationId);
        if (participant.getParticipationStatus() != ParticipationStatus.NO_SHOW) {
            throw new CustomException(ReservationErrorCode.INVALID_STATE);
        }
        participant.unmarkNoShow();
        noShowHistoryRepository.save(NoShowHistory.unmarked(participant.getId(), ownerMemberId, clock.instant()));
        log.info("event=NO_SHOW_UNMARKED reservationId={} participantId={} actorId={} afterStatus={}",
                reservationId, participationId, ownerMemberId, participant.getParticipationStatus());
        return new NoShowProcessResponse(reservationId, participationId);
    }

    @Transactional(readOnly = true)
    public PageResponse<NoShowHistoryResponse> getHistories(Long ownerMemberId, Long reservationId, Pageable pageable) {
        resolveOwnership(reservationId, ownerMemberId);
        Page<NoShowHistoryResult> results = noShowQueryRepository.findHistoriesByReservationId(reservationId, pageable);
        return PageResponse.from(results.map(NoShowHistoryResponse::of));
    }

    @Transactional(readOnly = true)
    public PageResponse<NoShowCustomerResponse> getRestaurantNoShows(
            Long ownerMemberId, Long restaurantId, LocalDate startDate, LocalDate endDate, Pageable pageable
    ) {
        validateRestaurantOwnership(restaurantId, ownerMemberId);
        DateRange range = dateRange(startDate, endDate);
        Page<NoShowCustomerResult> results = noShowQueryRepository.findNoShowCustomers(
                restaurantId, range.startAt(), range.endAt(), pageable);
        return PageResponse.from(results.map(NoShowCustomerResponse::of));
    }

    private OwnershipContext resolveOwnership(Long reservationId, Long ownerMemberId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        TimeSlot timeSlot = timeSlotRepository.findByIdAndDeletedAtIsNull(reservation.getTimeSlotId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        SharedTable sharedTable = sharedTableRepository.findByIdAndDeletedAtIsNull(timeSlot.getSharedTableId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(sharedTable.getRestaurantId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        if (!restaurant.isOwnedBy(ownerMemberId)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
        return new OwnershipContext(reservation, timeSlot);
    }

    // 채팅 전송 차단과 같은 경계인 now >= endAt부터 노쇼 처리를 허용한다.
    private void requireDiningEnded(TimeSlot timeSlot) {
        if (clock.instant().isBefore(timeSlot.getEndAt())) {
            throw new CustomException(ReservationErrorCode.INVALID_STATE);
        }
    }

    // 동시 요청이 같은 상태를 읽고 모두 이력을 남기지 않도록 참여자를 비관적으로 잠근다.
    private ReservationParticipant findParticipantWithLockOrThrow(Long reservationId, Long participationId) {
        return reservationParticipantRepository.findWithLockByIdAndReservationId(participationId, reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.PARTICIPATION_ID_NOT_FOUND));
    }

    private void validateRestaurantOwnership(Long restaurantId, Long ownerMemberId) {
        Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
                .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));
        if (!restaurant.isOwnedBy(ownerMemberId)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
    }

    private Map<Long, Member> fetchMembersById(List<ReservationParticipant> participants) {
        List<Long> memberIds = participants.stream().map(ReservationParticipant::getMemberId).distinct().toList();
        return memberRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
    }

    private DateRange dateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        return new DateRange(
                startDate == null ? null : startDate.atStartOfDay(SEOUL).toInstant(),
                endDate == null ? null : endDate.plusDays(1).atStartOfDay(SEOUL).toInstant());
    }

    private record OwnershipContext(Reservation reservation, TimeSlot timeSlot) {
    }

    private record DateRange(Instant startAt, Instant endAt) {
    }
}
