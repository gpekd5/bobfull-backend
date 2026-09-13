package com.bobfull.payment.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.common.exception.RestaurantErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.payment.dto.ExpectedSettlementResponse;
import com.bobfull.payment.dto.SettlementReservationDetailResponse;
import com.bobfull.payment.dto.SettlementReservationResponse;
import com.bobfull.payment.entity.Payment;
import com.bobfull.payment.entity.Refund;
import com.bobfull.payment.entity.RefundStatus;
import com.bobfull.payment.repository.PaymentRepository;
import com.bobfull.payment.repository.RefundRepository;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OWNER 식당의 Payment·Refund 이력으로 지급 예정 금액을 조회 계산한다. */
@Service
public class SettlementQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RestaurantRepository restaurantRepository;
    private final SharedTableRepository sharedTableRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public SettlementQueryService(
            RestaurantRepository restaurantRepository,
            SharedTableRepository sharedTableRepository,
            TimeSlotRepository timeSlotRepository,
            ReservationRepository reservationRepository,
            PaymentRepository paymentRepository,
            RefundRepository refundRepository
    ) {
        this.restaurantRepository = restaurantRepository;
        this.sharedTableRepository = sharedTableRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    @Transactional(readOnly = true)
    public ExpectedSettlementResponse getExpectedSettlement(Long ownerMemberId, Long restaurantId, LocalDate startDate, LocalDate endDate) {
        validateOwnership(ownerMemberId, restaurantId);
        DateRange range = dateRange(startDate, endDate);
        Object[] amounts = paymentRepository.sumSettlementAmounts(restaurantId, RefundStatus.COMPLETED, range.startAt(), range.endAt())
                .stream().findFirst().orElse(new Object[] {BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal paid = decimal(amounts[0]);
        BigDecimal refunded = decimal(amounts[1]);
        return new ExpectedSettlementResponse(paid, refunded, paid.subtract(refunded));
    }

    @Transactional(readOnly = true)
    public PageResponse<SettlementReservationResponse> getReservationSettlements(
            Long ownerMemberId, Long restaurantId, LocalDate startDate, LocalDate endDate, Pageable pageable
    ) {
        validateOwnership(ownerMemberId, restaurantId);
        DateRange range = dateRange(startDate, endDate);
        Page<Reservation> reservations = reservationRepository.findSettlementReservations(
                restaurantId, range.startAt(), range.endAt(), pageable);
        Map<Long, TimeSlot> slotsById = timeSlotRepository.findAllById(reservations.getContent().stream()
                .map(Reservation::getTimeSlotId).toList()).stream().collect(Collectors.toMap(TimeSlot::getId, Function.identity()));
        Map<Long, Amounts> amountsByReservation = amountsByReservation(reservations.getContent().stream()
                .map(Reservation::getId).toList());
        return PageResponse.from(reservations.map(reservation -> response(reservation, slotsById.get(reservation.getTimeSlotId()),
                amountsByReservation.getOrDefault(reservation.getId(), Amounts.ZERO))));
    }

    @Transactional(readOnly = true)
    public SettlementReservationDetailResponse getReservationSettlement(Long ownerMemberId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        TimeSlot slot = timeSlotRepository.findById(reservation.getTimeSlotId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        SharedTable table = sharedTableRepository.findById(slot.getSharedTableId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        validateOwnership(ownerMemberId, table.getRestaurantId());
        List<Payment> payments = paymentRepository.findAllByReservationIdAndPaidAtIsNotNull(reservationId);
        List<Refund> refunds = refundRepository.findAllByPayment_ReservationId(reservationId);
        Amounts amounts = amounts(payments, refunds);
        return new SettlementReservationDetailResponse(reservationId, amounts.expected(),
                payments.stream().map(payment -> new SettlementReservationDetailResponse.PaymentItem(
                        payment.getPaymentId(), payment.getStatus().name(), payment.getAmount())).toList(),
                refunds.stream().map(refund -> new SettlementReservationDetailResponse.RefundItem(
                        refund.getId(), refund.getStatus().name(), refund.getAmount())).toList());
    }

    private Map<Long, Amounts> amountsByReservation(Collection<Long> reservationIds) {
        if (reservationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Payment>> payments = paymentRepository.findAllByReservationIdInAndPaidAtIsNotNull(reservationIds).stream()
                .collect(Collectors.groupingBy(Payment::getReservationId));
        Map<Long, List<Refund>> refunds = refundRepository.findAllByPayment_ReservationIdIn(reservationIds).stream()
                .collect(Collectors.groupingBy(refund -> refund.getPayment().getReservationId()));
        return reservationIds.stream().collect(Collectors.toMap(Function.identity(), id -> amounts(
                payments.getOrDefault(id, List.of()), refunds.getOrDefault(id, List.of()))));
    }

    private SettlementReservationResponse response(Reservation reservation, TimeSlot slot, Amounts amounts) {
        return new SettlementReservationResponse(reservation.getId(), OffsetDateTime.ofInstant(slot.getStartAt(), SEOUL),
                amounts.paid(), amounts.refunded(), amounts.expected());
    }

    private Amounts amounts(List<Payment> payments, List<Refund> refunds) {
        BigDecimal paid = payments.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refunded = refunds.stream().filter(refund -> refund.getStatus() == RefundStatus.COMPLETED)
                .map(Refund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Amounts(paid, refunded);
    }

    private void validateOwnership(Long ownerMemberId, Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
                .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));
        if (!restaurant.isOwnedBy(ownerMemberId)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
    }

    private DateRange dateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        return new DateRange(startDate == null ? null : startDate.atStartOfDay(SEOUL).toInstant(),
                endDate == null ? null : endDate.plusDays(1).atStartOfDay(SEOUL).toInstant());
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }

    private record DateRange(Instant startAt, Instant endAt) {
    }

    private record Amounts(BigDecimal paid, BigDecimal refunded) {
        private static final Amounts ZERO = new Amounts(BigDecimal.ZERO, BigDecimal.ZERO);

        private BigDecimal expected() {
            return paid.subtract(refunded);
        }
    }
}
