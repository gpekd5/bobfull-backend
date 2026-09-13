package com.bobfull.restaurant.timeslot.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.RestaurantErrorCode;
import com.bobfull.common.exception.SharedTableErrorCode;
import com.bobfull.common.exception.TimeSlotErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.payment.application.port.PaymentHoldReader;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.domain.policy.ReservationCapacityPolicy;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.presentation.dto.AvailableDiningSessionListResponse;
import com.bobfull.restaurant.timeslot.presentation.dto.AvailableDiningSessionResponse;
import com.bobfull.restaurant.timeslot.presentation.dto.DiningSessionBulkRequest;
import com.bobfull.restaurant.timeslot.presentation.dto.DiningSessionBulkResponse;
import com.bobfull.restaurant.timeslot.presentation.dto.DiningSessionIdResponse;
import com.bobfull.restaurant.timeslot.presentation.dto.DiningSessionRequest;
import com.bobfull.restaurant.timeslot.presentation.dto.DiningSessionResponse;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API에서는 diningSession, 내부 영속 모델에서는 TimeSlot으로 다루는 회차 서비스다.
 */
@Service
public class TimeSlotService {

    private static final Logger log = LoggerFactory.getLogger(TimeSlotService.class);
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final List<ReservationStatus> ACTIVE_RESERVATION_STATUSES =
            List.of(ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLING);
    private static final List<ReservationStatus> CLOSED_RESERVATION_STATUS = List.of(ReservationStatus.CLOSED);
    private static final List<ParticipationStatus> OCCUPYING_PARTICIPATION_STATUSES =
            List.of(ParticipationStatus.RESERVED, ParticipationStatus.CANCEL_REQUESTED);

    private final TimeSlotRepository timeSlotRepository;
    private final SharedTableRepository sharedTableRepository;
    private final RestaurantRepository restaurantRepository;
    private final TimeSlotReservationValidator timeSlotReservationValidator;
    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final PaymentHoldReader paymentHoldReader;
    private final Clock clock;

    public TimeSlotService(
            TimeSlotRepository timeSlotRepository,
            SharedTableRepository sharedTableRepository,
            RestaurantRepository restaurantRepository,
            TimeSlotReservationValidator timeSlotReservationValidator,
            ReservationRepository reservationRepository,
            ReservationParticipantRepository reservationParticipantRepository,
            PaymentHoldReader paymentHoldReader,
            Clock clock
    ) {
        this.timeSlotRepository = timeSlotRepository;
        this.sharedTableRepository = sharedTableRepository;
        this.restaurantRepository = restaurantRepository;
        this.timeSlotReservationValidator = timeSlotReservationValidator;
        this.reservationRepository = reservationRepository;
        this.reservationParticipantRepository = reservationParticipantRepository;
        this.paymentHoldReader = paymentHoldReader;
        this.clock = clock;
    }

    @Transactional
    public DiningSessionIdResponse register(Long ownerMemberId, Long tableId, DiningSessionRequest request) {
        SharedTable sharedTable = findActiveTableOrThrow(tableId);
        validateRestaurantOwnership(sharedTable.getRestaurantId(), ownerMemberId);

        TimeRange timeRange = toTimeRange(request.startAt(), request.endAt());
        validateActiveDuplicate(sharedTable.getId(), timeRange.startAt());

        TimeSlot savedTimeSlot = saveTimeSlotOrThrowDuplicate(
                TimeSlot.create(sharedTable.getId(), timeRange.startAt(), timeRange.endAt()));
        return DiningSessionIdResponse.from(savedTimeSlot);
    }

    @Transactional
    public DiningSessionBulkResponse registerBulk(
            Long ownerMemberId,
            Long tableId,
            DiningSessionBulkRequest request
    ) {
        SharedTable sharedTable = findActiveTableOrThrow(tableId);
        validateRestaurantOwnership(sharedTable.getRestaurantId(), ownerMemberId);

        List<TimeRange> timeRanges = toIntervalTimeRanges(
                request.dates(), request.startTime(), request.endTime(), request.intervalMinutes());
        validateNoDuplicateStarts(sharedTable.getId(), timeRanges);

        List<TimeSlot> timeSlots = timeRanges.stream()
                .map(timeRange -> TimeSlot.create(sharedTable.getId(), timeRange.startAt(), timeRange.endAt()))
                .toList();
        saveAllTimeSlotsOrThrowDuplicate(timeSlots);
        return new DiningSessionBulkResponse(sharedTable.getId(), timeSlots.size());
    }

    @Transactional(readOnly = true)
    public PageResponse<DiningSessionResponse> getOwnerDiningSessions(
            Long ownerMemberId,
            Long restaurantId,
            LocalDate date,
            Pageable pageable
    ) {
        Restaurant restaurant = findActiveRestaurantOrThrow(restaurantId);
        validateOwnership(restaurant, ownerMemberId);

        List<SharedTable> sharedTables = sharedTableRepository.findAllByRestaurantIdAndDeletedAtIsNull(restaurantId);
        if (sharedTables.isEmpty()) {
            return PageResponse.from(Page.empty(pageable));
        }

        Map<Long, Integer> capacityByTableId = capacityByTableId(sharedTables);
        Collection<Long> tableIds = capacityByTableId.keySet();
        Page<TimeSlot> timeSlots = findOwnerTimeSlots(tableIds, date, pageable);
        Page<DiningSessionResponse> responsePage = timeSlots.map(timeSlot -> toDiningSessionResponse(
                timeSlot,
                capacityByTableId.get(timeSlot.getSharedTableId())
        ));
        return PageResponse.from(responsePage);
    }

    @Transactional(readOnly = true)
    public AvailableDiningSessionListResponse getAvailableDiningSessions(
            Long restaurantId,
            LocalDate date,
            Integer partySize
    ) {
        validatePartySize(partySize);
        findActiveRestaurantOrThrow(restaurantId);

        List<SharedTable> sharedTables = sharedTableRepository.findAllByRestaurantIdAndDeletedAtIsNull(restaurantId);
        if (sharedTables.isEmpty()) {
            return new AvailableDiningSessionListResponse(restaurantId, List.of());
        }

        Map<Long, Integer> capacityByTableId = capacityByTableId(sharedTables);
        DateRange dateRange = toDateRange(date);
        List<TimeSlot> timeSlots = timeSlotRepository
                .findAllBySharedTableIdInAndStartAtGreaterThanEqualAndStartAtLessThanAndDeletedAtIsNullOrderByStartAtAsc(
                        capacityByTableId.keySet(), dateRange.startAt(), dateRange.endAt());
        if (timeSlots.isEmpty()) {
            return new AvailableDiningSessionListResponse(restaurantId, List.of());
        }

        AvailableDiningSessionBatchContext context = loadAvailableDiningSessionBatchContext(timeSlots);
        List<AvailableDiningSessionResponse> content = timeSlots.stream()
                .map(timeSlot -> toAvailableDiningSessionResponse(
                        timeSlot,
                        capacityByTableId.get(timeSlot.getSharedTableId()),
                        context
                ))
                .filter(response -> partySize == null || response.availableCapacity() >= partySize)
                .toList();
        return new AvailableDiningSessionListResponse(restaurantId, content);
    }

    @Transactional
    public DiningSessionIdResponse update(Long ownerMemberId, Long sessionId, DiningSessionRequest request) {
        TimeSlot timeSlot = findActiveTimeSlotOrThrow(sessionId);
        SharedTable sharedTable = findActiveTableOrThrow(timeSlot.getSharedTableId());
        validateRestaurantOwnership(sharedTable.getRestaurantId(), ownerMemberId);

        TimeRange timeRange = toTimeRange(request.startAt(), request.endAt());
        timeSlotReservationValidator.validateChangeAllowed(timeSlot.getId());
        validateActiveDuplicateForUpdate(sharedTable.getId(), timeRange.startAt(), timeSlot.getId());

        Instant beforeStartAt = timeSlot.getStartAt();
        Instant beforeEndAt = timeSlot.getEndAt();
        timeSlot.update(timeRange.startAt(), timeRange.endAt());
        flushTimeSlotOrThrowDuplicate();
        if (!beforeStartAt.equals(timeSlot.getStartAt()) || !beforeEndAt.equals(timeSlot.getEndAt())) {
            log.info("event=DINING_SESSION_TIME_CHANGED sessionId={} tableId={} actorId={} beforeStartAt={} afterStartAt={} beforeEndAt={} afterEndAt={}",
                    timeSlot.getId(), timeSlot.getSharedTableId(), ownerMemberId, beforeStartAt, timeSlot.getStartAt(),
                    beforeEndAt, timeSlot.getEndAt());
        }
        return DiningSessionIdResponse.from(timeSlot);
    }

    @Transactional
    public DiningSessionIdResponse delete(Long ownerMemberId, Long sessionId) {
        TimeSlot timeSlot = findActiveTimeSlotOrThrow(sessionId);
        SharedTable sharedTable = findActiveTableOrThrow(timeSlot.getSharedTableId());
        validateRestaurantOwnership(sharedTable.getRestaurantId(), ownerMemberId);
        timeSlotReservationValidator.validateDeletionAllowed(timeSlot.getId());

        timeSlot.softDelete(clock.instant());
        return DiningSessionIdResponse.from(timeSlot);
    }

    private Page<TimeSlot> findOwnerTimeSlots(Collection<Long> tableIds, LocalDate date, Pageable pageable) {
        if (date == null) {
            return timeSlotRepository.findAllBySharedTableIdInAndDeletedAtIsNullOrderByStartAtAsc(tableIds, pageable);
        }

        DateRange dateRange = toDateRange(date);
        return timeSlotRepository
                .findAllBySharedTableIdInAndStartAtGreaterThanEqualAndStartAtLessThanAndDeletedAtIsNullOrderByStartAtAsc(
                        tableIds, dateRange.startAt(), dateRange.endAt(), pageable);
    }

    private DiningSessionResponse toDiningSessionResponse(TimeSlot timeSlot, Integer capacity) {
        return DiningSessionResponse.of(
                timeSlot,
                capacity,
                toOffsetDateTime(timeSlot.getStartAt()),
                toOffsetDateTime(timeSlot.getEndAt())
        );
    }

    /**
     * #142(인기 회차 조회 폭주)에서 회차 목록 조회가 회차당 4개 쿼리(활성 예약·참여자 합계·CLOSED
     * 여부·READY 선점 합계)를 반복해 DB Pool·CPU가 동시 포화되는 병목으로 확인됐다(Issue #235
     * "1. 병목 Hot-path 분리"). 회차 ID를 미리 다 알고 있으므로, 이 4개를 회차 수와 무관하게
     * 고정된 배치 쿼리로 한 번씩만 실행해 앞에서 모아두고 Java에서 회차별로 조립한다 —
     * `availableCapacity` 계산식 자체(닫힘이면 0, 아니면 {@link ReservationCapacityPolicy})는
     * {@link com.bobfull.reservation.application.service.AvailableCapacityCalculator}와 동일하게 유지한다.
     */
    private AvailableDiningSessionBatchContext loadAvailableDiningSessionBatchContext(List<TimeSlot> timeSlots) {
        List<Long> timeSlotIds = timeSlots.stream().map(TimeSlot::getId).toList();

        Map<Long, Reservation> activeReservationByTimeSlotId = reservationRepository
                .findAllByTimeSlotIdInAndReservationStatusIn(timeSlotIds, ACTIVE_RESERVATION_STATUSES)
                .stream()
                .collect(Collectors.toMap(Reservation::getTimeSlotId, reservation -> reservation));

        Set<Long> closedTimeSlotIds = reservationRepository
                .findAllByTimeSlotIdInAndReservationStatusIn(timeSlotIds, CLOSED_RESERVATION_STATUS)
                .stream()
                .map(Reservation::getTimeSlotId)
                .collect(Collectors.toSet());

        List<Long> activeReservationIds = activeReservationByTimeSlotId.values().stream()
                .map(Reservation::getId)
                .toList();
        Map<Long, Integer> participantCountByReservationId = activeReservationIds.isEmpty()
                ? Map.of()
                : reservationParticipantRepository
                        .sumPartySizeByReservationIdsAndStatuses(activeReservationIds, OCCUPYING_PARTICIPATION_STATUSES)
                        .stream()
                        .collect(Collectors.toMap(row -> (Long) row[0], row -> ((Number) row[1]).intValue()));

        Map<Long, Integer> readyHoldPartySizeByTimeSlotId = paymentHoldReader
                .sumActiveReadyPartySizeByTimeSlotIds(timeSlotIds);

        return new AvailableDiningSessionBatchContext(
                activeReservationByTimeSlotId, closedTimeSlotIds, participantCountByReservationId, readyHoldPartySizeByTimeSlotId);
    }

    private AvailableDiningSessionResponse toAvailableDiningSessionResponse(
            TimeSlot timeSlot, Integer capacity, AvailableDiningSessionBatchContext context) {
        Reservation activeReservation = context.activeReservationByTimeSlotId().get(timeSlot.getId());
        Long reservationId = activeReservation != null ? activeReservation.getId() : null;
        int currentParticipantCount = reservationId != null
                ? context.participantCountByReservationId().getOrDefault(reservationId, 0)
                : 0;
        int availableCapacity = context.closedTimeSlotIds().contains(timeSlot.getId())
                ? 0
                : ReservationCapacityPolicy.availableCapacity(
                        capacity,
                        currentParticipantCount,
                        context.readyHoldPartySizeByTimeSlotId().getOrDefault(timeSlot.getId(), 0));
        return AvailableDiningSessionResponse.of(
                timeSlot,
                capacity,
                toOffsetDateTime(timeSlot.getStartAt()),
                toOffsetDateTime(timeSlot.getEndAt()),
                availableCapacity,
                reservationId,
                currentParticipantCount
        );
    }

    private record AvailableDiningSessionBatchContext(
            Map<Long, Reservation> activeReservationByTimeSlotId,
            Set<Long> closedTimeSlotIds,
            Map<Long, Integer> participantCountByReservationId,
            Map<Long, Integer> readyHoldPartySizeByTimeSlotId
    ) {
    }

    private Map<Long, Integer> capacityByTableId(List<SharedTable> sharedTables) {
        return sharedTables.stream()
                .collect(Collectors.toMap(SharedTable::getId, SharedTable::getCapacity));
    }

    private SharedTable findActiveTableOrThrow(Long tableId) {
        return sharedTableRepository.findByIdAndDeletedAtIsNull(tableId)
                .orElseThrow(() -> new CustomException(SharedTableErrorCode.TABLE_ID_NOT_FOUND));
    }

    private TimeSlot findActiveTimeSlotOrThrow(Long sessionId) {
        return timeSlotRepository.findByIdAndDeletedAtIsNull(sessionId)
                .orElseThrow(() -> new CustomException(TimeSlotErrorCode.SESSION_ID_NOT_FOUND));
    }

    private Restaurant findActiveRestaurantOrThrow(Long restaurantId) {
        return restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
                .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));
    }

    private void validateRestaurantOwnership(Long restaurantId, Long ownerMemberId) {
        Restaurant restaurant = findActiveRestaurantOrThrow(restaurantId);
        validateOwnership(restaurant, ownerMemberId);
    }

    private void validateOwnership(Restaurant restaurant, Long ownerMemberId) {
        if (!restaurant.isOwnedBy(ownerMemberId)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
    }

    private void validatePartySize(Integer partySize) {
        if (partySize != null && partySize <= 0) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private void validateActiveDuplicate(Long tableId, Instant startAt) {
        if (timeSlotRepository.existsBySharedTableIdAndStartAtAndDeletedAtIsNull(tableId, startAt)) {
            throw new CustomException(TimeSlotErrorCode.DUPLICATE_DINING_SESSION);
        }
    }

    private void validateActiveDuplicateForUpdate(Long tableId, Instant startAt, Long sessionId) {
        if (timeSlotRepository.existsBySharedTableIdAndStartAtAndDeletedAtIsNullAndIdNot(tableId, startAt, sessionId)) {
            throw new CustomException(TimeSlotErrorCode.DUPLICATE_DINING_SESSION);
        }
    }

    private void validateNoDuplicateStarts(Long tableId, List<TimeRange> timeRanges) {
        validateNoDuplicateStartsInRequest(timeRanges);
        for (TimeRange timeRange : timeRanges) {
            validateActiveDuplicate(tableId, timeRange.startAt());
        }
    }

    private void validateNoDuplicateStartsInRequest(List<TimeRange> timeRanges) {
        Set<Instant> starts = new HashSet<>();
        for (TimeRange timeRange : timeRanges) {
            if (!starts.add(timeRange.startAt())) {
                throw new CustomException(TimeSlotErrorCode.DUPLICATE_DINING_SESSION);
            }
        }
    }

    private TimeSlot saveTimeSlotOrThrowDuplicate(TimeSlot timeSlot) {
        try {
            return timeSlotRepository.saveAndFlush(timeSlot);
        } catch (DataIntegrityViolationException exception) {
            throw new CustomException(TimeSlotErrorCode.DUPLICATE_DINING_SESSION);
        }
    }

    private void saveAllTimeSlotsOrThrowDuplicate(List<TimeSlot> timeSlots) {
        try {
            timeSlotRepository.saveAllAndFlush(timeSlots);
        } catch (DataIntegrityViolationException exception) {
            throw new CustomException(TimeSlotErrorCode.DUPLICATE_DINING_SESSION);
        }
    }

    private void flushTimeSlotOrThrowDuplicate() {
        try {
            timeSlotRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new CustomException(TimeSlotErrorCode.DUPLICATE_DINING_SESSION);
        }
    }

    private TimeRange toTimeRange(LocalDateTime startAt, LocalDateTime endAt) {
        Instant startInstant = startAt.atZone(SEOUL_ZONE).toInstant();
        Instant endInstant = endAt.atZone(SEOUL_ZONE).toInstant();
        validateTimeRange(startInstant, endInstant);
        return new TimeRange(startInstant, endInstant);
    }

    private List<TimeRange> toIntervalTimeRanges(
            List<LocalDate> dates,
            LocalTime startTime,
            LocalTime endTime,
            Integer intervalMinutes
    ) {
        validateDailyTimeRange(startTime, endTime);
        validateInterval(startTime, endTime, intervalMinutes);

        List<TimeRange> timeRanges = new ArrayList<>();
        for (LocalDate date : dates) {
            LocalTime currentStart = startTime;
            while (currentStart.isBefore(endTime)) {
                LocalTime currentEnd = currentStart.plusMinutes(intervalMinutes);
                timeRanges.add(new TimeRange(
                        toInstant(date, currentStart),
                        toInstant(date, currentEnd)
                ));
                currentStart = currentEnd;
            }
        }
        return timeRanges;
    }

    private void validateDailyTimeRange(LocalTime startTime, LocalTime endTime) {
        if (!startTime.isBefore(endTime)) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private void validateTimeRange(Instant startAt, Instant endAt) {
        if (!startAt.isBefore(endAt)) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private void validateInterval(LocalTime startTime, LocalTime endTime, Integer intervalMinutes) {
        long totalMinutes = Duration.between(startTime, endTime).toMinutes();
        if (intervalMinutes == null || intervalMinutes <= 0 || totalMinutes % intervalMinutes != 0) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private DateRange toDateRange(LocalDate date) {
        return new DateRange(toInstant(date, LocalTime.MIN), toInstant(date.plusDays(1), LocalTime.MIN));
    }

    private Instant toInstant(LocalDate date, LocalTime time) {
        return LocalDateTime.of(date, time).atZone(SEOUL_ZONE).toInstant();
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atZone(SEOUL_ZONE).toOffsetDateTime();
    }

    private record TimeRange(Instant startAt, Instant endAt) {
    }

    private record DateRange(Instant startAt, Instant endAt) {
    }
}
