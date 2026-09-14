package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.restaurant.restaurant.domain.exception.RestaurantErrorCode;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.response.PageResponse;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.reservation.application.dto.NoShowCustomerResult;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NoShowServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC);

    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationParticipantRepository reservationParticipantRepository;
    @Mock private NoShowHistoryRepository noShowHistoryRepository;
    @Mock private NoShowQueryRepository noShowQueryRepository;
    @Mock private TimeSlotRepository timeSlotRepository;
    @Mock private SharedTableRepository sharedTableRepository;
    @Mock private RestaurantRepository restaurantRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private BusinessMetricRecorder businessMetricRecorder;

    private NoShowService service;

    private final Long ownerMemberId = 1L;
    private Reservation reservation;
    private TimeSlot timeSlot;
    private SharedTable sharedTable;
    private Restaurant restaurant;

    private void setUpOwnershipChain(Instant timeSlotEndAt) {
        reservation = Reservation.create(100L, 5L);
        ReflectionTestUtils.setField(reservation, "id", 1L);
        timeSlot = TimeSlot.create(10L, timeSlotEndAt.minusSeconds(3600), timeSlotEndAt);
        ReflectionTestUtils.setField(timeSlot, "id", 100L);
        sharedTable = SharedTable.create(1000L, 4);
        ReflectionTestUtils.setField(sharedTable, "id", 10L);
        restaurant = Restaurant.create(ownerMemberId, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000);
        ReflectionTestUtils.setField(restaurant, "id", 1000L);

        service = new NoShowService(
                reservationRepository, reservationParticipantRepository, noShowHistoryRepository,
                noShowQueryRepository, timeSlotRepository, sharedTableRepository, restaurantRepository,
                memberRepository, FIXED_CLOCK, businessMetricRecorder);

        given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(timeSlot.getId())).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findByIdAndDeletedAtIsNull(sharedTable.getId())).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(restaurant.getId())).willReturn(Optional.of(restaurant));
    }

    @Test
    void 식사_종료_후_노쇼_후보를_조회한다() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().minusSeconds(60));
        ReservationParticipant participant = ReservationParticipant.create(reservation.getId(), 20L, 2);
        ReflectionTestUtils.setField(participant, "id", 500L);
        Member member = Member.createMember("m@example.com", "hash", "홍길동", "01000000000");
        ReflectionTestUtils.setField(member, "id", 20L);
        Page<ReservationParticipant> page = new PageImpl<>(List.of(participant));
        given(reservationParticipantRepository.findAllByReservationIdAndParticipationStatus(
                reservation.getId(), ParticipationStatus.RESERVED, PageRequest.of(0, 20))).willReturn(page);
        given(memberRepository.findAllById(List.of(20L))).willReturn(List.of(member));

        // when
        PageResponse<?> result = service.getCandidates(ownerMemberId, reservation.getId(), PageRequest.of(0, 20));

        // then
        assertThat(result.content()).hasSize(1);
    }

    @Test
    void 식사_종료_전_후보_조회는_409() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().plusSeconds(60));

        // when
        Throwable result = catchThrowable(
                () -> service.getCandidates(ownerMemberId, reservation.getId(), PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_STATE);
    }

    @Test
    void 식사_종료_시각과_같으면_노쇼_후보_조회를_허용한다() {
        // given: now == TimeSlot.endAt (Issue #175 Q1 경계)
        setUpOwnershipChain(FIXED_CLOCK.instant());
        given(reservationParticipantRepository.findAllByReservationIdAndParticipationStatus(
                reservation.getId(), ParticipationStatus.RESERVED, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of()));

        // when
        Throwable result = catchThrowable(
                () -> service.getCandidates(ownerMemberId, reservation.getId(), PageRequest.of(0, 20)));

        // then
        assertThat(result).isNull();
    }

    @Test
    void 존재하지_않는_예약은_404() {
        // given
        service = new NoShowService(
                reservationRepository, reservationParticipantRepository, noShowHistoryRepository,
                noShowQueryRepository, timeSlotRepository, sharedTableRepository, restaurantRepository,
                memberRepository, FIXED_CLOCK, businessMetricRecorder);
        given(reservationRepository.findById(999L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(
                () -> service.getCandidates(ownerMemberId, 999L, PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_ID_NOT_FOUND);
    }

    @Test
    void 본인_식당이_아닌_예약_접근은_403() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().minusSeconds(60));

        // when
        Throwable result = catchThrowable(
                () -> service.getCandidates(999L, reservation.getId(), PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void RESERVED_참여자를_노쇼_처리하면_NO_SHOW로_전이하고_이력을_남긴다() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().minusSeconds(60));
        ReservationParticipant participant = ReservationParticipant.create(reservation.getId(), 20L, 2);
        ReflectionTestUtils.setField(participant, "id", 500L);
        given(reservationParticipantRepository.findWithLockByIdAndReservationId(500L, reservation.getId()))
                .willReturn(Optional.of(participant));

        // when
        NoShowProcessResponse result = service.markNoShow(ownerMemberId, reservation.getId(), 500L);

        // then
        assertThat(result.reservationId()).isEqualTo(reservation.getId());
        assertThat(result.participationId()).isEqualTo(500L);
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.NO_SHOW);
        verify(noShowHistoryRepository).save(any(NoShowHistory.class));
    }

    @Test
    void 노쇼_처리시_NO_SHOW_MARKED_구조화로그를_남긴다() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().minusSeconds(60));
        ReservationParticipant participant = ReservationParticipant.create(reservation.getId(), 20L, 2);
        ReflectionTestUtils.setField(participant, "id", 500L);
        given(reservationParticipantRepository.findWithLockByIdAndReservationId(500L, reservation.getId()))
                .willReturn(Optional.of(participant));
        Logger logger = (Logger) LoggerFactory.getLogger(NoShowService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            service.markNoShow(ownerMemberId, reservation.getId(), 500L);
        } finally {
            logger.detachAppender(appender);
        }

        // then
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("event=NO_SHOW_MARKED");
            assertThat(event.getFormattedMessage()).contains("reservationId=1");
            assertThat(event.getFormattedMessage()).contains("participantId=500");
            assertThat(event.getFormattedMessage()).contains("actorId=1");
            assertThat(event.getFormattedMessage()).contains("afterStatus=NO_SHOW");
        });
    }

    @Test
    void 이미_처리된_참여자를_다시_노쇼_처리하면_409() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().minusSeconds(60));
        ReservationParticipant participant = ReservationParticipant.create(reservation.getId(), 20L, 2);
        ReflectionTestUtils.setField(participant, "id", 500L);
        participant.markNoShow();
        given(reservationParticipantRepository.findWithLockByIdAndReservationId(500L, reservation.getId()))
                .willReturn(Optional.of(participant));

        // when
        Throwable result = catchThrowable(() -> service.markNoShow(ownerMemberId, reservation.getId(), 500L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_STATE);
        verify(noShowHistoryRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_참여자_노쇼_처리는_404() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().minusSeconds(60));
        given(reservationParticipantRepository.findWithLockByIdAndReservationId(999L, reservation.getId()))
                .willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> service.markNoShow(ownerMemberId, reservation.getId(), 999L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.PARTICIPATION_ID_NOT_FOUND);
    }

    @Test
    void 식사_종료_전_노쇼_처리는_409() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().plusSeconds(60));

        // when
        Throwable result = catchThrowable(() -> service.markNoShow(ownerMemberId, reservation.getId(), 500L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_STATE);
        verify(reservationParticipantRepository, never()).findWithLockByIdAndReservationId(any(), any());
    }

    @Test
    void 식사_종료_시각과_같으면_노쇼_처리를_허용한다() {
        // given: now == TimeSlot.endAt (Issue #175 Q1·Q4 경계, 채팅 SEND 차단과 동일 기준)
        setUpOwnershipChain(FIXED_CLOCK.instant());
        ReservationParticipant participant = ReservationParticipant.create(reservation.getId(), 20L, 2);
        ReflectionTestUtils.setField(participant, "id", 500L);
        given(reservationParticipantRepository.findWithLockByIdAndReservationId(500L, reservation.getId()))
                .willReturn(Optional.of(participant));

        // when
        NoShowProcessResponse result = service.markNoShow(ownerMemberId, reservation.getId(), 500L);

        // then
        assertThat(result.participationId()).isEqualTo(500L);
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.NO_SHOW);
    }

    @Test
    void NO_SHOW_참여자를_해제하면_RESERVED로_복귀하고_이력을_남긴다() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().minusSeconds(60));
        ReservationParticipant participant = ReservationParticipant.create(reservation.getId(), 20L, 2);
        ReflectionTestUtils.setField(participant, "id", 500L);
        participant.markNoShow();
        given(reservationParticipantRepository.findWithLockByIdAndReservationId(500L, reservation.getId()))
                .willReturn(Optional.of(participant));

        // when
        NoShowProcessResponse result = service.unmarkNoShow(ownerMemberId, reservation.getId(), 500L);

        // then
        assertThat(result.participationId()).isEqualTo(500L);
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.RESERVED);
        verify(noShowHistoryRepository).save(any(NoShowHistory.class));
    }

    @Test
    void RESERVED_참여자를_노쇼_해제하면_409() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().minusSeconds(60));
        ReservationParticipant participant = ReservationParticipant.create(reservation.getId(), 20L, 2);
        ReflectionTestUtils.setField(participant, "id", 500L);
        given(reservationParticipantRepository.findWithLockByIdAndReservationId(500L, reservation.getId()))
                .willReturn(Optional.of(participant));

        // when
        Throwable result = catchThrowable(() -> service.unmarkNoShow(ownerMemberId, reservation.getId(), 500L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_STATE);
    }

    @Test
    void 예약별_노쇼_이력을_조회한다() {
        // given
        setUpOwnershipChain(FIXED_CLOCK.instant().minusSeconds(60));
        NoShowHistoryResult resultRow = new NoShowHistoryResult(
                1L, 500L, 20L, "홍길동", 2, true, ownerMemberId, FIXED_CLOCK.instant());
        given(noShowQueryRepository.findHistoriesByReservationId(reservation.getId(), PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(resultRow)));

        // when
        PageResponse<?> result = service.getHistories(ownerMemberId, reservation.getId(), PageRequest.of(0, 20));

        // then
        assertThat(result.content()).hasSize(1);
    }

    @Test
    void 식당_노쇼_고객을_조회한다() {
        // given
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1000L)).willReturn(Optional.of(
                withId(Restaurant.create(ownerMemberId, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000), 1000L)));
        NoShowCustomerResult resultRow = new NoShowCustomerResult(
                20L, "홍길동", 2, FIXED_CLOCK.instant(), 1L, 500L, 2);
        given(noShowQueryRepository.findNoShowCustomers(1000L, null, null, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(resultRow)));
        service = new NoShowService(
                reservationRepository, reservationParticipantRepository, noShowHistoryRepository,
                noShowQueryRepository, timeSlotRepository, sharedTableRepository, restaurantRepository,
                memberRepository, FIXED_CLOCK, businessMetricRecorder);

        // when
        PageResponse<?> result = service.getRestaurantNoShows(
                ownerMemberId, 1000L, null, null, PageRequest.of(0, 20));

        // then
        assertThat(result.content()).hasSize(1);
    }

    @Test
    void 식당_노쇼_고객_조회_기간이_역전되면_400() {
        // given
        service = new NoShowService(
                reservationRepository, reservationParticipantRepository, noShowHistoryRepository,
                noShowQueryRepository, timeSlotRepository, sharedTableRepository, restaurantRepository,
                memberRepository, FIXED_CLOCK, businessMetricRecorder);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1000L)).willReturn(Optional.of(
                withId(Restaurant.create(ownerMemberId, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000), 1000L)));
        Pageable pageable = PageRequest.of(0, 20);

        // when
        Throwable result = catchThrowable(() -> service.getRestaurantNoShows(
                ownerMemberId, 1000L, java.time.LocalDate.of(2026, 8, 10), java.time.LocalDate.of(2026, 8, 1), pageable));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 존재하지_않는_식당_조회는_404() {
        // given
        service = new NoShowService(
                reservationRepository, reservationParticipantRepository, noShowHistoryRepository,
                noShowQueryRepository, timeSlotRepository, sharedTableRepository, restaurantRepository,
                memberRepository, FIXED_CLOCK, businessMetricRecorder);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> service.getRestaurantNoShows(
                ownerMemberId, 999L, null, null, PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND);
    }

    private Restaurant withId(Restaurant restaurant, Long id) {
        ReflectionTestUtils.setField(restaurant, "id", id);
        return restaurant;
    }
}
