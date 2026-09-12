package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.common.exception.RestaurantErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.member.entity.Member;
import com.bobfull.member.repository.MemberRepository;
import com.bobfull.payment.service.PaymentHoldReader;
import com.bobfull.reservation.presentation.dto.OwnerReservationDetailResponse;
import com.bobfull.reservation.presentation.dto.OwnerReservationListItemResponse;
import com.bobfull.reservation.presentation.dto.OwnerReservationParticipantResponse;
import com.bobfull.reservation.application.dto.OwnerReservationResult;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OwnerReservationQueryServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-01T08:00:00Z"), ZoneOffset.UTC);

    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationParticipantRepository reservationParticipantRepository;
    @Mock private TimeSlotRepository timeSlotRepository;
    @Mock private SharedTableRepository sharedTableRepository;
    @Mock private RestaurantRepository restaurantRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private PaymentHoldReader paymentHoldReader;

    private OwnerReservationQueryService service;

    private final Long ownerMemberId = 1L;
    private Reservation reservation;
    private TimeSlot timeSlot;
    private SharedTable sharedTable;
    private Restaurant restaurant;

    private void setUp() {
        service = new OwnerReservationQueryService(
                reservationRepository, reservationParticipantRepository, timeSlotRepository,
                sharedTableRepository, restaurantRepository, memberRepository, paymentHoldReader, FIXED_CLOCK);
    }

    private void setUpOwnershipChain(Long actualOwnerMemberId) {
        reservation = Reservation.create(100L, 5L);
        ReflectionTestUtils.setField(reservation, "id", 1L);
        timeSlot = TimeSlot.create(10L, Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z"));
        ReflectionTestUtils.setField(timeSlot, "id", 100L);
        sharedTable = SharedTable.create(1000L, 6);
        ReflectionTestUtils.setField(sharedTable, "id", 10L);
        restaurant = Restaurant.create(actualOwnerMemberId, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000);
        ReflectionTestUtils.setField(restaurant, "id", 1000L);

        given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));
        given(timeSlotRepository.findById(timeSlot.getId())).willReturn(Optional.of(timeSlot));
        given(sharedTableRepository.findById(sharedTable.getId())).willReturn(Optional.of(sharedTable));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(restaurant.getId())).willReturn(Optional.of(restaurant));
    }

    @Test
    void 본인_식당의_예약_목록을_조회한다() {
        // given
        setUp();
        Restaurant myRestaurant = Restaurant.create(ownerMemberId, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000);
        ReflectionTestUtils.setField(myRestaurant, "id", 1000L);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1000L)).willReturn(Optional.of(myRestaurant));
        OwnerReservationResult result = new OwnerReservationResult(
                1L, 100L, 10L, 6, Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z"),
                ReservationStatus.RECRUITING, RecruitmentStatus.OPEN, 2L, 0L);
        given(reservationRepository.searchOwnerReservations(
                eq(1000L), any(), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(result)));

        // when
        PageResponse<OwnerReservationListItemResponse> response =
                service.getRestaurantReservations(ownerMemberId, 1000L, null, null, PageRequest.of(0, 20));

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).reservationId()).isEqualTo(1L);
        assertThat(response.content().get(0).availableCapacity()).isEqualTo(4);
    }

    @Test
    void 타인_식당의_예약_목록_조회는_403() {
        // given
        setUp();
        Restaurant otherOwnersRestaurant = Restaurant.create(999L, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000);
        ReflectionTestUtils.setField(otherOwnersRestaurant, "id", 1000L);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1000L)).willReturn(Optional.of(otherOwnersRestaurant));

        // when
        Throwable result = catchThrowable(() ->
                service.getRestaurantReservations(ownerMemberId, 1000L, null, null, PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void 존재하지_않는_식당의_예약_목록_조회는_404() {
        // given
        setUp();
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1000L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() ->
                service.getRestaurantReservations(ownerMemberId, 1000L, null, null, PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND);
    }

    @Test
    void 잘못된_reservationStatus_값이면_400() {
        // given
        setUp();
        Restaurant myRestaurant = Restaurant.create(ownerMemberId, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000);
        ReflectionTestUtils.setField(myRestaurant, "id", 1000L);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1000L)).willReturn(Optional.of(myRestaurant));

        // when
        Throwable result = catchThrowable(() ->
                service.getRestaurantReservations(ownerMemberId, 1000L, "NOT_A_STATUS", null, PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 본인_식당_예약_상세를_조회한다() {
        // given
        setUp();
        setUpOwnershipChain(ownerMemberId);
        given(reservationParticipantRepository.sumPartySizeByStatuses(anyLong(), any())).willReturn(3);
        given(paymentHoldReader.sumActiveReadyPartySize(timeSlot.getId())).willReturn(1);

        // when
        OwnerReservationDetailResponse response = service.getReservationDetail(ownerMemberId, reservation.getId());

        // then
        assertThat(response.reservationId()).isEqualTo(reservation.getId());
        assertThat(response.restaurantId()).isEqualTo(restaurant.getId());
        assertThat(response.currentParticipantCount()).isEqualTo(3);
        assertThat(response.availableCapacity()).isEqualTo(2);
        assertThat(response.confirmationThreshold()).isEqualTo(5);
    }

    @Test
    void 타인_식당_예약_상세_조회는_403() {
        // given
        setUp();
        setUpOwnershipChain(999L);

        // when
        Throwable result = catchThrowable(() -> service.getReservationDetail(ownerMemberId, reservation.getId()));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void 존재하지_않는_예약_상세_조회는_404() {
        // given
        setUp();
        given(reservationRepository.findById(1L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> service.getReservationDetail(ownerMemberId, 1L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_ID_NOT_FOUND);
    }

    @Test
    void 본인_식당_예약의_참여자_목록을_조회한다() {
        // given
        setUp();
        setUpOwnershipChain(ownerMemberId);
        ReservationParticipant participant = ReservationParticipant.create(reservation.getId(), 20L, 2);
        ReflectionTestUtils.setField(participant, "id", 500L);
        Member member = Member.createMember("m@example.com", "hash", "홍길동", "01000000000");
        ReflectionTestUtils.setField(member, "id", 20L);
        Page<ReservationParticipant> page = new PageImpl<>(List.of(participant));
        given(reservationParticipantRepository.findAllByReservationId(reservation.getId(), PageRequest.of(0, 20)))
                .willReturn(page);
        given(memberRepository.findAllById(List.of(20L))).willReturn(List.of(member));

        // when
        PageResponse<OwnerReservationParticipantResponse> response =
                service.getParticipants(ownerMemberId, reservation.getId(), PageRequest.of(0, 20));

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).name()).isEqualTo("홍길동");
        assertThat(response.content().get(0).memberId()).isEqualTo(20L);
    }

    @Test
    void 타인_식당_예약의_참여자_목록_조회는_403() {
        // given
        setUp();
        setUpOwnershipChain(999L);

        // when
        Throwable result = catchThrowable(
                () -> service.getParticipants(ownerMemberId, reservation.getId(), PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void 존재하지_않는_예약의_참여자_목록_조회는_404() {
        // given
        setUp();
        given(reservationRepository.findById(1L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(
                () -> service.getParticipants(ownerMemberId, 1L, PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_ID_NOT_FOUND);
    }

    @Test
    void 회차나_테이블이_삭제됐어도_본인_식당_예약_상세는_조회된다() {
        // given: 사장님이 취소된 예약만 남은 회차·테이블을 나중에 삭제한 상황이다.
        // 목록 조회(§6-11)는 deletedAt을 걸러내지 않으므로, 상세·참여자 조회도 같은 예약을
        // 404로 숨기면 "목록에는 보이는데 눌러보면 404"가 되는 불일치가 생긴다.
        setUp();
        setUpOwnershipChain(ownerMemberId);
        ReflectionTestUtils.setField(timeSlot, "deletedAt", Instant.parse("2026-08-01T12:00:00Z"));
        ReflectionTestUtils.setField(sharedTable, "deletedAt", Instant.parse("2026-08-01T12:00:00Z"));
        given(reservationParticipantRepository.sumPartySizeByStatuses(anyLong(), any())).willReturn(0);
        given(paymentHoldReader.sumActiveReadyPartySize(timeSlot.getId())).willReturn(0);

        // when
        OwnerReservationDetailResponse response = service.getReservationDetail(ownerMemberId, reservation.getId());

        // then
        assertThat(response.reservationId()).isEqualTo(reservation.getId());
    }

    @Test
    void CANCELLING_상태값으로_목록을_조회하면_400() {
        // given
        setUp();
        Restaurant myRestaurant = Restaurant.create(ownerMemberId, "밥풀식당", "제주시", "한식", "설명", "키워드", 10000);
        ReflectionTestUtils.setField(myRestaurant, "id", 1000L);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1000L)).willReturn(Optional.of(myRestaurant));

        // when
        Throwable result = catchThrowable(() ->
                service.getRestaurantReservations(ownerMemberId, 1000L, "CANCELLING", null, PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }
}
