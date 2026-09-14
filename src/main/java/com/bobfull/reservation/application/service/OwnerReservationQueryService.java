package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.restaurant.restaurant.domain.exception.RestaurantErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.payment.application.port.PaymentHoldReader;
import com.bobfull.reservation.presentation.dto.OwnerReservationDetailResponse;
import com.bobfull.reservation.presentation.dto.OwnerReservationListItemResponse;
import com.bobfull.reservation.presentation.dto.OwnerReservationParticipantResponse;
import com.bobfull.reservation.application.dto.OwnerReservationResult;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.domain.policy.ReservationCapacityPolicy;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OWNER의 식당별 예약 목록·상세·참여자 조회를 담당한다(Issue #147 §6-11~6-13). */
@Service
public class OwnerReservationQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final List<ParticipationStatus> OCCUPYING_STATUSES =
            List.of(ParticipationStatus.RESERVED, ParticipationStatus.CANCEL_REQUESTED);

    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final SharedTableRepository sharedTableRepository;
    private final RestaurantRepository restaurantRepository;
    private final MemberRepository memberRepository;
    private final PaymentHoldReader paymentHoldReader;
    private final Clock clock;

    public OwnerReservationQueryService(
            ReservationRepository reservationRepository,
            ReservationParticipantRepository reservationParticipantRepository,
            TimeSlotRepository timeSlotRepository,
            SharedTableRepository sharedTableRepository,
            RestaurantRepository restaurantRepository,
            MemberRepository memberRepository,
            PaymentHoldReader paymentHoldReader,
            Clock clock
    ) {
        this.reservationRepository = reservationRepository;
        this.reservationParticipantRepository = reservationParticipantRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.sharedTableRepository = sharedTableRepository;
        this.restaurantRepository = restaurantRepository;
        this.memberRepository = memberRepository;
        this.paymentHoldReader = paymentHoldReader;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<OwnerReservationListItemResponse> getRestaurantReservations(
            Long ownerMemberId, Long restaurantId, String reservationStatus, LocalDate date, Pageable pageable
    ) {
        validateRestaurantOwnership(restaurantId, ownerMemberId);
        ReservationStatus status = parseStatus(reservationStatus);
        Instant startAt = date == null ? null : date.atStartOfDay(SEOUL_ZONE).toInstant();
        Instant endAt = date == null ? null : date.plusDays(1).atStartOfDay(SEOUL_ZONE).toInstant();

        Page<OwnerReservationResult> results = reservationRepository.searchOwnerReservations(
                restaurantId, status, startAt, endAt, clock.instant(), pageable);
        return PageResponse.from(results.map(result -> OwnerReservationListItemResponse.of(
                result, toSeoulOffset(result.startAt()), toSeoulOffset(result.endAt()))));
    }

    @Transactional(readOnly = true)
    public OwnerReservationDetailResponse getReservationDetail(Long ownerMemberId, Long reservationId) {
        OwnershipContext context = resolveOwnership(reservationId, ownerMemberId);
        Reservation reservation = context.reservation();
        TimeSlot timeSlot = context.timeSlot();
        SharedTable sharedTable = context.sharedTable();

        int currentParticipantCount =
                reservationParticipantRepository.sumPartySizeByStatuses(reservationId, OCCUPYING_STATUSES);
        int temporaryHeldCount = paymentHoldReader.sumActiveReadyPartySize(timeSlot.getId());
        int availableCapacity = ReservationCapacityPolicy.availableCapacity(
                sharedTable.getCapacity(), currentParticipantCount, temporaryHeldCount);

        return new OwnerReservationDetailResponse(
                reservation.getId(),
                context.restaurant().getId(),
                timeSlot.getId(),
                sharedTable.getId(),
                sharedTable.getCapacity(),
                toSeoulOffset(timeSlot.getStartAt()),
                toSeoulOffset(timeSlot.getEndAt()),
                reservation.getReservationStatus(),
                reservation.getRecruitmentStatus(),
                currentParticipantCount,
                availableCapacity,
                ReservationCapacityPolicy.confirmationThreshold(sharedTable.getCapacity())
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<OwnerReservationParticipantResponse> getParticipants(
            Long ownerMemberId, Long reservationId, Pageable pageable
    ) {
        resolveOwnership(reservationId, ownerMemberId);

        Page<ReservationParticipant> participants =
                reservationParticipantRepository.findAllByReservationId(reservationId, pageable);
        Map<Long, Member> membersById = fetchMembersById(participants.getContent());
        return PageResponse.from(participants.map(participant -> OwnerReservationParticipantResponse.of(
                participant, membersById.get(participant.getMemberId()).getName())));
    }

    private void validateRestaurantOwnership(Long restaurantId, Long ownerMemberId) {
        Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
                .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));
        if (!restaurant.isOwnedBy(ownerMemberId)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
    }

    /**
     * TimeSlot·SharedTable은 소프트 삭제 후에도 조회한다. 목록 조회(§6-11)가 deletedAt을 걸러내지
     * 않으므로, 여기서 걸러내면 목록에는 보이지만 상세·참여자 조회는 404가 되는 불일치가 생긴다
     * (예: 취소된 예약만 남은 회차·테이블을 사장님이 나중에 삭제한 경우).
     */
    private OwnershipContext resolveOwnership(Long reservationId, Long ownerMemberId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        TimeSlot timeSlot = timeSlotRepository.findById(reservation.getTimeSlotId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        SharedTable sharedTable = sharedTableRepository.findById(timeSlot.getSharedTableId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(sharedTable.getRestaurantId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        if (!restaurant.isOwnedBy(ownerMemberId)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
        return new OwnershipContext(reservation, timeSlot, sharedTable, restaurant);
    }

    /** API 명세 §6-11이 문서화한 값(RECRUITING/CONFIRMED/CANCELLED/CLOSED)만 허용한다. */
    private ReservationStatus parseStatus(String reservationStatus) {
        if (reservationStatus == null || reservationStatus.isBlank()) {
            return null;
        }
        try {
            ReservationStatus status = ReservationStatus.valueOf(reservationStatus);
            if (status == ReservationStatus.CANCELLING) {
                throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
            }
            return status;
        } catch (IllegalArgumentException exception) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private Map<Long, Member> fetchMembersById(List<ReservationParticipant> participants) {
        List<Long> memberIds = participants.stream().map(ReservationParticipant::getMemberId).distinct().toList();
        return memberRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
    }

    private OffsetDateTime toSeoulOffset(Instant instant) {
        return instant.atZone(SEOUL_ZONE).toOffsetDateTime();
    }

    private record OwnershipContext(Reservation reservation, TimeSlot timeSlot, SharedTable sharedTable, Restaurant restaurant) {
    }
}
