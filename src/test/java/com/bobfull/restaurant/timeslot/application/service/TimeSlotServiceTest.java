package com.bobfull.restaurant.timeslot.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.timeslot.domain.exception.TimeSlotErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.payment.application.port.PaymentHoldReader;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TimeSlotServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TimeSlotRepository timeSlotRepository;

    @Mock
    private SharedTableRepository sharedTableRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private TimeSlotReservationValidator timeSlotReservationValidator;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationParticipantRepository reservationParticipantRepository;

    @Mock
    private PaymentHoldReader paymentHoldReader;

    private TimeSlotService timeSlotService() {
        return new TimeSlotService(
                timeSlotRepository,
                sharedTableRepository,
                restaurantRepository,
                timeSlotReservationValidator,
                reservationRepository,
                reservationParticipantRepository,
                paymentHoldReader,
                FIXED_CLOCK
        );
    }

    @Test
    void 회차를_등록하면_서울_로컬_시각을_UTC_Instant로_저장한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(timeSlotRepository.existsBySharedTableIdAndStartAtAndDeletedAtIsNull(100L, seoulInstant("2026-08-01T11:00:00")))
                .willReturn(false);
        given(timeSlotRepository.saveAndFlush(any(TimeSlot.class))).willAnswer(invocation -> {
            TimeSlot timeSlot = invocation.getArgument(0);
            ReflectionTestUtils.setField(timeSlot, "id", 200L);
            return timeSlot;
        });

        // when
        DiningSessionIdResponse response = timeSlotService().register(1L, 100L, request());

        // then
        ArgumentCaptor<TimeSlot> captor = ArgumentCaptor.forClass(TimeSlot.class);
        verify(timeSlotRepository).saveAndFlush(captor.capture());
        assertThat(response.sessionId()).isEqualTo(200L);
        assertThat(captor.getValue().getSharedTableId()).isEqualTo(100L);
        assertThat(captor.getValue().getStartAt()).isEqualTo(Instant.parse("2026-08-01T02:00:00Z"));
        assertThat(captor.getValue().getEndAt()).isEqualTo(Instant.parse("2026-08-01T04:00:00Z"));
    }

    @Test
    void 동일_테이블의_동일_시작_활성_회차가_있으면_409_예외가_발생한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(timeSlotRepository.existsBySharedTableIdAndStartAtAndDeletedAtIsNull(100L, seoulInstant("2026-08-01T11:00:00")))
                .willReturn(true);

        // when
        Throwable result = catchThrowable(() -> timeSlotService().register(1L, 100L, request()));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(TimeSlotErrorCode.DUPLICATE_DINING_SESSION);
        verify(timeSlotRepository, never()).saveAndFlush(any());
    }

    @Test
    void DB_활성_회차_중복_제약이_발생하면_409_예외로_변환한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(timeSlotRepository.existsBySharedTableIdAndStartAtAndDeletedAtIsNull(100L, seoulInstant("2026-08-01T11:00:00")))
                .willReturn(false);
        given(timeSlotRepository.saveAndFlush(any(TimeSlot.class)))
                .willThrow(new DataIntegrityViolationException("duplicate active dining session"));

        // when
        Throwable result = catchThrowable(() -> timeSlotService().register(1L, 100L, request()));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(TimeSlotErrorCode.DUPLICATE_DINING_SESSION);
    }

    @Test
    void 종료_시각이_시작_시각보다_늦지_않으면_400_예외가_발생한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        DiningSessionRequest request = new DiningSessionRequest(
                LocalDateTime.of(2026, 8, 1, 11, 0),
                LocalDateTime.of(2026, 8, 1, 11, 0)
        );
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> timeSlotService().register(1L, 100L, request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
        verify(timeSlotRepository, never()).saveAndFlush(any());
    }

    @Test
    void 기존_테이블에_intervalMinutes_기준_회차를_일괄_등록한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        DiningSessionBulkRequest request = new DiningSessionBulkRequest(
                List.of(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2)),
                LocalTime.of(11, 0),
                LocalTime.of(13, 0),
                30
        );
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(timeSlotRepository.existsBySharedTableIdAndStartAtAndDeletedAtIsNull(eq(100L), any(Instant.class)))
                .willReturn(false);

        // when
        DiningSessionBulkResponse response = timeSlotService().registerBulk(1L, 100L, request);

        // then
        ArgumentCaptor<List<TimeSlot>> captor = ArgumentCaptor.forClass(List.class);
        verify(timeSlotRepository).saveAllAndFlush(captor.capture());
        assertThat(response.tableId()).isEqualTo(100L);
        assertThat(response.createdSessionCount()).isEqualTo(8);
        assertThat(captor.getValue()).hasSize(8);
        assertThat(captor.getValue().get(0).getStartAt()).isEqualTo(seoulInstant("2026-08-01T11:00:00"));
        assertThat(captor.getValue().get(0).getEndAt()).isEqualTo(seoulInstant("2026-08-01T11:30:00"));
    }

    @Test
    void 기존_테이블_일괄_등록_요청에_같은_날짜가_중복되면_409_예외가_발생한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        DiningSessionBulkRequest request = new DiningSessionBulkRequest(
                List.of(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1)),
                LocalTime.of(11, 0),
                LocalTime.of(13, 0),
                30
        );
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> timeSlotService().registerBulk(1L, 100L, request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(TimeSlotErrorCode.DUPLICATE_DINING_SESSION);
        verify(timeSlotRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void intervalMinutes가_시간_범위를_정확히_나누지_못하면_400_예외가_발생한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        DiningSessionBulkRequest request = new DiningSessionBulkRequest(
                List.of(LocalDate.of(2026, 8, 1)),
                LocalTime.of(11, 0),
                LocalTime.of(12, 10),
                30
        );
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        Throwable result = catchThrowable(() -> timeSlotService().registerBulk(1L, 100L, request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
        verify(timeSlotRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void 본인_식당의_회차_목록을_테이블_capacity와_함께_조회한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        TimeSlot timeSlot = timeSlot(200L, 100L, "2026-08-01T11:00:00", "2026-08-01T13:00:00");
        Pageable pageable = PageRequest.of(0, 20);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(sharedTableRepository.findAllByRestaurantIdAndDeletedAtIsNull(10L)).willReturn(List.of(sharedTable));
        given(timeSlotRepository
                .findAllBySharedTableIdInAndStartAtGreaterThanEqualAndStartAtLessThanAndDeletedAtIsNullOrderByStartAtAsc(
                        anyCollection(), any(Instant.class), any(Instant.class), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(timeSlot), pageable, 1));

        // when
        PageResponse<DiningSessionResponse> response = timeSlotService()
                .getOwnerDiningSessions(1L, 10L, LocalDate.of(2026, 8, 1), pageable);

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).sessionId()).isEqualTo(200L);
        assertThat(response.content().get(0).capacity()).isEqualTo(4);
        assertThat(response.content().get(0).startAt().toString()).isEqualTo("2026-08-01T11:00+09:00");
    }

    @Test
    void 사용자용_예약_가능_회차는_availableCapacity를_현재_capacity로_반환하고_partySize로_필터한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable smallTable = sharedTable(100L, 10L, 2);
        SharedTable largeTable = sharedTable(101L, 10L, 4);
        TimeSlot smallSlot = timeSlot(200L, 100L, "2026-08-01T11:00:00", "2026-08-01T13:00:00");
        TimeSlot largeSlot = timeSlot(201L, 101L, "2026-08-01T11:00:00", "2026-08-01T13:00:00");
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(sharedTableRepository.findAllByRestaurantIdAndDeletedAtIsNull(10L))
                .willReturn(List.of(smallTable, largeTable));
        given(timeSlotRepository
                .findAllBySharedTableIdInAndStartAtGreaterThanEqualAndStartAtLessThanAndDeletedAtIsNullOrderByStartAtAsc(
                        anyCollection(), any(Instant.class), any(Instant.class)))
                .willReturn(List.of(smallSlot, largeSlot));
        given(reservationRepository.findAllByTimeSlotIdInAndReservationStatusIn(anyCollection(), anyCollection()))
                .willReturn(List.of());
        given(paymentHoldReader.sumActiveReadyPartySizeByTimeSlotIds(anyCollection())).willReturn(Map.of());

        // when
        AvailableDiningSessionListResponse response = timeSlotService()
                .getAvailableDiningSessions(10L, LocalDate.of(2026, 8, 1), 3);

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).sessionId()).isEqualTo(201L);
        assertThat(response.content().get(0).availableCapacity()).isEqualTo(4);
        assertThat(response.content().get(0).reservationId()).isNull();
        assertThat(response.content().get(0).currentParticipantCount()).isEqualTo(0);
    }

    /**
     * Issue #235 — 회차 목록의 availableCapacity 계산이 회차별 반복 조회 대신 배치 조회 결과를
     * Java에서 조립하는 방식으로 바뀌었다(TimeSlotService#loadAvailableDiningSessionBatchContext).
     * 활성 예약이 있는 회차(참여자 합계 반영)와 CLOSED인 회차(참여자·READY 선점과 무관하게 0)를
     * 함께 확인해, 배치 조립 로직이 기존 단건 조회(AvailableCapacityCalculator)와 같은 계산식을
     * 따르는지 검증한다.
     */
    @Test
    void 사용자용_예약_가능_회차는_배치로_조회한_활성_예약_참여자_CLOSED_여부를_반영한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable table = sharedTable(100L, 10L, 4);
        TimeSlot occupiedSlot = timeSlot(200L, 100L, "2026-08-01T11:00:00", "2026-08-01T13:00:00");
        TimeSlot closedSlot = timeSlot(201L, 100L, "2026-08-01T13:00:00", "2026-08-01T15:00:00");
        Reservation activeReservation = Reservation.create(200L, 1L);
        ReflectionTestUtils.setField(activeReservation, "id", 900L);
        Reservation closedReservation = Reservation.create(201L, 1L);
        ReflectionTestUtils.setField(closedReservation, "id", 901L);
        ReflectionTestUtils.setField(closedReservation, "reservationStatus", ReservationStatus.CLOSED);

        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(sharedTableRepository.findAllByRestaurantIdAndDeletedAtIsNull(10L)).willReturn(List.of(table));
        given(timeSlotRepository
                .findAllBySharedTableIdInAndStartAtGreaterThanEqualAndStartAtLessThanAndDeletedAtIsNullOrderByStartAtAsc(
                        anyCollection(), any(Instant.class), any(Instant.class)))
                .willReturn(List.of(occupiedSlot, closedSlot));
        given(reservationRepository.findAllByTimeSlotIdInAndReservationStatusIn(
                anyCollection(), eq(List.of(ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLING))))
                .willReturn(List.of(activeReservation));
        given(reservationRepository.findAllByTimeSlotIdInAndReservationStatusIn(
                anyCollection(), eq(List.of(ReservationStatus.CLOSED))))
                .willReturn(List.of(closedReservation));
        given(reservationParticipantRepository.sumPartySizeByReservationIdsAndStatuses(eq(List.of(900L)), anyCollection()))
                .willReturn(List.<Object[]>of(new Object[]{900L, 3}));
        given(paymentHoldReader.sumActiveReadyPartySizeByTimeSlotIds(anyCollection()))
                .willReturn(Map.of(200L, 1));

        // when
        AvailableDiningSessionListResponse response = timeSlotService()
                .getAvailableDiningSessions(10L, LocalDate.of(2026, 8, 1), null);

        // then — 정원 4명인 occupiedSlot: 참여자 3 + READY 선점 1을 뺀 0석
        AvailableDiningSessionResponse occupied = response.content().stream()
                .filter(r -> r.sessionId().equals(200L)).findFirst().orElseThrow();
        assertThat(occupied.reservationId()).isEqualTo(900L);
        assertThat(occupied.currentParticipantCount()).isEqualTo(3);
        assertThat(occupied.availableCapacity()).isEqualTo(0);

        // then — CLOSED인 closedSlot: reservationId는 활성 예약 기준이라 null이지만(CLOSED는
        // 집계 대상이 아님, 기존 동작과 동일), availableCapacity는 참여자·READY 선점과 무관하게
        // 항상 0석이다.
        AvailableDiningSessionResponse closed = response.content().stream()
                .filter(r -> r.sessionId().equals(201L)).findFirst().orElseThrow();
        assertThat(closed.reservationId()).isNull();
        assertThat(closed.availableCapacity()).isZero();
    }

    @Test
    void 회차_수정은_소유권과_예약_경계와_활성_중복을_검증한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        TimeSlot timeSlot = timeSlot(200L, 100L, "2026-08-01T11:00:00", "2026-08-01T13:00:00");
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(200L)).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(timeSlotRepository.existsBySharedTableIdAndStartAtAndDeletedAtIsNullAndIdNot(
                100L, seoulInstant("2026-08-01T12:00:00"), 200L)).willReturn(false);

        // when
        timeSlotService().update(1L, 200L, new DiningSessionRequest(
                LocalDateTime.of(2026, 8, 1, 12, 0),
                LocalDateTime.of(2026, 8, 1, 14, 0)
        ));

        // then
        verify(timeSlotReservationValidator).validateChangeAllowed(200L);
        verify(timeSlotRepository).flush();
        assertThat(timeSlot.getStartAt()).isEqualTo(seoulInstant("2026-08-01T12:00:00"));
        assertThat(timeSlot.getEndAt()).isEqualTo(seoulInstant("2026-08-01T14:00:00"));
    }

    @Test
    void 회차_시간을_수정하면_DINING_SESSION_TIME_CHANGED_구조화로그를_남긴다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        TimeSlot timeSlot = timeSlot(200L, 100L, "2026-08-01T11:00:00", "2026-08-01T13:00:00");
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(200L)).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(timeSlotRepository.existsBySharedTableIdAndStartAtAndDeletedAtIsNullAndIdNot(
                100L, seoulInstant("2026-08-01T12:00:00"), 200L)).willReturn(false);
        Logger logger = (Logger) LoggerFactory.getLogger(TimeSlotService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            timeSlotService().update(1L, 200L, new DiningSessionRequest(
                    LocalDateTime.of(2026, 8, 1, 12, 0),
                    LocalDateTime.of(2026, 8, 1, 14, 0)
            ));
        } finally {
            logger.detachAppender(appender);
        }

        // then
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("event=DINING_SESSION_TIME_CHANGED");
            assertThat(event.getFormattedMessage()).contains("sessionId=200");
            assertThat(event.getFormattedMessage()).contains("tableId=100");
            assertThat(event.getFormattedMessage()).contains("actorId=1");
            assertThat(event.getFormattedMessage()).contains("beforeStartAt=2026-08-01T02:00:00Z");
            assertThat(event.getFormattedMessage()).contains("afterStartAt=2026-08-01T03:00:00Z");
        });
    }

    @Test
    void 회차_수정_중_DB_활성_회차_중복_제약이_발생하면_409_예외로_변환한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        TimeSlot timeSlot = timeSlot(200L, 100L, "2026-08-01T11:00:00", "2026-08-01T13:00:00");
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(200L)).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));
        given(timeSlotRepository.existsBySharedTableIdAndStartAtAndDeletedAtIsNullAndIdNot(
                100L, seoulInstant("2026-08-01T12:00:00"), 200L)).willReturn(false);
        willThrow(new DataIntegrityViolationException("duplicate active dining session"))
                .given(timeSlotRepository).flush();

        // when
        Throwable result = catchThrowable(() -> timeSlotService().update(1L, 200L, new DiningSessionRequest(
                LocalDateTime.of(2026, 8, 1, 12, 0),
                LocalDateTime.of(2026, 8, 1, 14, 0)
        )));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(TimeSlotErrorCode.DUPLICATE_DINING_SESSION);
    }

    @Test
    void 회차_삭제는_소프트_딜리트하고_예약_경계를_검증한다() {
        // given
        Restaurant restaurant = restaurantOwnedBy(1L);
        SharedTable sharedTable = sharedTable(100L, 10L, 4);
        TimeSlot timeSlot = timeSlot(200L, 100L, "2026-08-01T11:00:00", "2026-08-01T13:00:00");
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(200L)).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(restaurant));

        // when
        timeSlotService().delete(1L, 200L);

        // then
        verify(timeSlotReservationValidator).validateDeletionAllowed(200L);
        assertThat(timeSlot.getDeletedAt()).isEqualTo(FIXED_CLOCK.instant());
    }

    private Restaurant restaurantOwnedBy(Long ownerMemberId) {
        Restaurant restaurant = Restaurant.create(
                ownerMemberId, "밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000);
        ReflectionTestUtils.setField(restaurant, "id", 10L);
        return restaurant;
    }

    private SharedTable sharedTable(Long tableId, Long restaurantId, Integer capacity) {
        SharedTable sharedTable = SharedTable.create(restaurantId, capacity);
        ReflectionTestUtils.setField(sharedTable, "id", tableId);
        return sharedTable;
    }

    private TimeSlot timeSlot(Long sessionId, Long tableId, String startAt, String endAt) {
        TimeSlot timeSlot = TimeSlot.create(tableId, seoulInstant(startAt), seoulInstant(endAt));
        ReflectionTestUtils.setField(timeSlot, "id", sessionId);
        return timeSlot;
    }

    private DiningSessionRequest request() {
        return new DiningSessionRequest(
                LocalDateTime.of(2026, 8, 1, 11, 0),
                LocalDateTime.of(2026, 8, 1, 13, 0)
        );
    }

    private Instant seoulInstant(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atOffset(ZoneOffset.ofHours(9)).toInstant();
    }
}
