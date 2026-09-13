package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.RestaurantErrorCode;
import com.bobfull.payment.presentation.dto.ExpectedSettlementResponse;
import com.bobfull.payment.presentation.dto.SettlementReservationDetailResponse;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SettlementQueryServiceTest {

    @Mock private RestaurantRepository restaurantRepository;
    @Mock private SharedTableRepository sharedTableRepository;
    @Mock private TimeSlotRepository timeSlotRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;

    @Test
    void 타인식당의_정산은_403을_반환한다() {
        Restaurant restaurant = Restaurant.create(2L, "식당", "주소", "한식", "설명", "키워드", 10000);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(restaurant));

        Throwable result = catchThrowable(() -> service().getExpectedSettlement(1L, 1L, null, null));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void 존재하지않는_식당의_정산은_404를_반환한다() {
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

        Throwable result = catchThrowable(() -> service().getExpectedSettlement(1L, 1L, null, null));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND);
    }

    @Test
    void 역전된_기간은_400을_반환한다() {
        Restaurant restaurant = Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(restaurant));

        Throwable result = catchThrowable(() -> service().getExpectedSettlement(1L, 1L,
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 1)));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 타인식당의_예약상세정산은_403을_반환한다() {
        Reservation reservation = Reservation.create(2L, 10L);
        TimeSlot slot = TimeSlot.create(3L, Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z"));
        SharedTable table = SharedTable.create(4L, 4);
        Restaurant restaurant = Restaurant.create(2L, "식당", "주소", "한식", "설명", "키워드", 10000);
        ReflectionTestUtils.setField(reservation, "id", 1L);
        ReflectionTestUtils.setField(slot, "id", 2L);
        ReflectionTestUtils.setField(table, "id", 3L);
        given(reservationRepository.findById(1L)).willReturn(Optional.of(reservation));
        given(timeSlotRepository.findById(2L)).willReturn(Optional.of(slot));
        given(sharedTableRepository.findById(3L)).willReturn(Optional.of(table));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(4L)).willReturn(Optional.of(restaurant));

        Throwable result = catchThrowable(() -> service().getReservationSettlement(1L, 1L));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    @Test
    void 결제와_환불이_없는_식당은_0원_지급예정액을_반환한다() {
        Restaurant restaurant = Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(restaurant));
        given(paymentRepository.sumSettlementAmounts(1L, RefundStatus.COMPLETED, null, null)).willReturn(List.of());

        ExpectedSettlementResponse response = service().getExpectedSettlement(1L, 1L, null, null);

        assertThat(response.totalPaidAmount()).isZero();
        assertThat(response.totalRefundedAmount()).isZero();
        assertThat(response.expectedSettlementAmount()).isZero();
    }

    @Test
    void 삭제된_TimeSlot의_예약상세도_결제환불내역과_지급예정액을_반환한다() {
        Reservation reservation = Reservation.create(2L, 10L);
        TimeSlot slot = TimeSlot.create(3L, Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z"));
        SharedTable table = SharedTable.create(4L, 4);
        Restaurant restaurant = Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000);
        Payment payment = Payment.createReady("refunded", 10L, 2L, 1L, PaymentPurpose.JOIN, 1,
                BigDecimal.valueOf(30000), Instant.parse("2026-08-01T08:00:00Z"));
        payment.complete(Instant.parse("2026-08-01T08:01:00Z"));
        payment.markRefunded();
        Refund refund = Refund.create(payment, BigDecimal.valueOf(30000), RefundStatus.COMPLETED,
                Instant.parse("2026-08-01T08:02:00Z"), Instant.parse("2026-08-01T08:03:00Z"),
                "test-key-detail", "test reason");
        slot.softDelete(Instant.parse("2026-08-02T00:00:00Z"));
        ReflectionTestUtils.setField(reservation, "id", 1L);
        ReflectionTestUtils.setField(slot, "id", 2L);
        ReflectionTestUtils.setField(table, "id", 3L);
        ReflectionTestUtils.setField(refund, "id", 4L);
        given(reservationRepository.findById(1L)).willReturn(Optional.of(reservation));
        given(timeSlotRepository.findById(2L)).willReturn(Optional.of(slot));
        given(sharedTableRepository.findById(3L)).willReturn(Optional.of(table));
        given(restaurantRepository.findByIdAndDeletedAtIsNull(4L)).willReturn(Optional.of(restaurant));
        given(paymentRepository.findAllByReservationIdAndPaidAtIsNotNull(1L)).willReturn(List.of(payment));
        given(refundRepository.findAllByPayment_ReservationId(1L)).willReturn(List.of(refund));

        SettlementReservationDetailResponse response = service().getReservationSettlement(1L, 1L);

        assertThat(slot.getDeletedAt()).isNotNull();
        assertThat(response.expectedSettlementAmount()).isZero();
        assertThat(response.payments()).singleElement().satisfies(item -> {
            assertThat(item.paymentStatus()).isEqualTo("REFUNDED");
            assertThat(item.amount()).isEqualByComparingTo("30000");
        });
        assertThat(response.refunds()).singleElement().satisfies(item -> {
            assertThat(item.refundStatus()).isEqualTo("COMPLETED");
            assertThat(item.amount()).isEqualByComparingTo("30000");
        });
    }

    @Test
    void 삭제된_TimeSlot의_총액과_예약별목록_지급예정액은_같은_범위를_사용한다() {
        Restaurant restaurant = Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000);
        Reservation reservation = Reservation.create(2L, 10L);
        TimeSlot slot = TimeSlot.create(3L, Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z"));
        Payment payment = Payment.createReady("refunded-list", 10L, 2L, 1L, PaymentPurpose.JOIN, 1,
                BigDecimal.valueOf(30000), Instant.parse("2026-08-01T08:00:00Z"));
        payment.complete(Instant.parse("2026-08-01T08:01:00Z"));
        payment.markRefunded();
        Refund refund = Refund.create(payment, BigDecimal.valueOf(30000), RefundStatus.COMPLETED,
                Instant.parse("2026-08-01T08:02:00Z"), Instant.parse("2026-08-01T08:03:00Z"),
                "test-key-list", "test reason");
        slot.softDelete(Instant.parse("2026-08-02T00:00:00Z"));
        ReflectionTestUtils.setField(reservation, "id", 1L);
        ReflectionTestUtils.setField(slot, "id", 2L);
        given(restaurantRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(restaurant));
        given(paymentRepository.sumSettlementAmounts(1L, RefundStatus.COMPLETED, null, null))
                .willReturn(List.<Object[]>of(new Object[] {BigDecimal.valueOf(30000), BigDecimal.valueOf(30000)}));
        given(reservationRepository.findSettlementReservations(1L, null, null, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(reservation), PageRequest.of(0, 20), 1));
        given(timeSlotRepository.findAllById(List.of(2L))).willReturn(List.of(slot));
        given(paymentRepository.findAllByReservationIdInAndPaidAtIsNotNull(List.of(1L))).willReturn(List.of(payment));
        given(refundRepository.findAllByPayment_ReservationIdIn(List.of(1L))).willReturn(List.of(refund));

        ExpectedSettlementResponse total = service().getExpectedSettlement(1L, 1L, null, null);
        var list = service().getReservationSettlements(1L, 1L, null, null, PageRequest.of(0, 20));

        assertThat(slot.getDeletedAt()).isNotNull();
        assertThat(total.expectedSettlementAmount()).isZero();
        assertThat(list.content()).singleElement().satisfies(item -> {
            assertThat(item.totalPaidAmount()).isEqualByComparingTo("30000");
            assertThat(item.totalRefundedAmount()).isEqualByComparingTo("30000");
            assertThat(item.expectedSettlementAmount()).isEqualByComparingTo(total.expectedSettlementAmount());
        });
    }

    private SettlementQueryService service() {
        return new SettlementQueryService(restaurantRepository, sharedTableRepository, timeSlotRepository,
                reservationRepository, paymentRepository, refundRepository);
    }
}
