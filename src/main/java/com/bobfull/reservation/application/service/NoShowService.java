package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.common.exception.RestaurantErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.response.PageResponse;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.member.entity.Member;
import com.bobfull.member.repository.MemberRepository;
import com.bobfull.reservation.presentation.dto.NoShowCandidateResponse;
import com.bobfull.reservation.presentation.dto.NoShowCustomerResponse;
import com.bobfull.reservation.application.dto.NoShowCustomerResult;
import com.bobfull.reservation.presentation.dto.NoShowHistoryResponse;
import com.bobfull.reservation.application.dto.NoShowHistoryResult;
import com.bobfull.reservation.presentation.dto.NoShowProcessResponse;
import com.bobfull.reservation.domain.entity.NoShowHistory;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.infrastructure.repository.NoShowHistoryRepository;
import com.bobfull.reservation.infrastructure.repository.NoShowQueryRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.entity.Restaurant;
import com.bobfull.restaurant.repository.RestaurantRepository;
import com.bobfull.sharedtable.entity.SharedTable;
import com.bobfull.sharedtable.repository.SharedTableRepository;
import com.bobfull.timeslot.entity.TimeSlot;
import com.bobfull.timeslot.repository.TimeSlotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OWNER의 참여자별 노쇼 처리·해제·이력 조회를 담당한다(Issue #48 §9-1~9-5). */
@Service
public class NoShowService {

    private static final Logger log = LoggerFactory.getLogger(NoShowService.class);
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

    public NoShowService(
            ReservationRepository reservationRepository,
            ReservationParticipantRepository reservationParticipantRepository,
            NoShowHistoryRepository noShowHistoryRepository,
            NoShowQueryRepository noShowQueryRepository,
            TimeSlotRepository timeSlotRepository,
            SharedTableRepository sharedTableRepository,
            RestaurantRepository restaurantRepository,
            MemberRepository memberRepository,
            Clock clock,
            BusinessMetricRecorder businessMetricRecorder
    ) {
        this.reservationRepository = reservationRepository;
        this.reservationParticipantRepository = reservationParticipantRepository;
        this.noShowHistoryRepository = noShowHistoryRepository;
        this.noShowQueryRepository = noShowQueryRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.sharedTableRepository = sharedTableRepository;
        this.restaurantRepository = restaurantRepository;
        this.memberRepository = memberRepository;
        this.clock = clock;
        this.businessMetricRecorder = businessMetricRecorder;
    }

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
        AfterCommitExecutor.run(() -> businessMetricRecorder.increment(BusinessMetricEvent.NO_SHOW_MARKED));
        return new NoShowProcessResponse(reservationId, participationId);
    }

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

    /**
     * 식사 종료 경계는 {@code now >= TimeSlot.endAt}이다(Issue #175 Q1·Q4). 채팅 SEND 차단과
     * 동일한 경계를 사용해, 정확히 종료 시각인 순간부터 노쇼 처리를 허용한다.
     */
    private void requireDiningEnded(TimeSlot timeSlot) {
        if (clock.instant().isBefore(timeSlot.getEndAt())) {
            throw new CustomException(ReservationErrorCode.INVALID_STATE);
        }
    }

    /**
     * 노쇼 처리·해제 대상 참여자를 비관적 락으로 조회한다.
     * 락 없이 조회·상태 확인·전이를 하면 동시에 들어온 두 요청이 서로의 커밋 전에 같은 상태를
     * 읽어 둘 다 검증을 통과할 수 있다 — 참여자 최종 상태는 같은 값으로 수렴해 깨지지 않지만,
     * NoShowHistory가 중복 기록돼 §9-4 이력 조회에 그대로 노출된다(PR #133 리뷰 반영).
     */
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
