package com.bobfull.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.payment.entity.Payment;
import com.bobfull.payment.entity.PaymentPurpose;
import com.bobfull.payment.entity.Refund;
import com.bobfull.payment.entity.RefundStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.entity.Restaurant;
import com.bobfull.restaurant.repository.RestaurantRepository;
import com.bobfull.sharedtable.entity.SharedTable;
import com.bobfull.sharedtable.repository.SharedTableRepository;
import com.bobfull.timeslot.entity.TimeSlot;
import com.bobfull.timeslot.repository.TimeSlotRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class SettlementAmountRepositoryTest {

    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;

    @Test
    void REFUNDED여도_paidAt이_있는_결제완료이력은_한번만_차감한다() {
        // given
        Restaurant restaurant = restaurantRepository.save(Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot slot = timeSlotRepository.save(TimeSlot.create(table.getId(),
                Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        Reservation reservation = reservationRepository.save(Reservation.create(slot.getId(), 2L));
        Payment paid = Payment.createReady("paid", 2L, slot.getId(), reservation.getId(), PaymentPurpose.JOIN, 1,
                BigDecimal.valueOf(30000), Instant.parse("2026-08-01T08:00:00Z"));
        paid.complete(Instant.parse("2026-08-01T08:01:00Z"));
        paid.markRefunded();
        paymentRepository.save(paid);
        refundRepository.save(Refund.create(paid, BigDecimal.valueOf(30000), RefundStatus.COMPLETED,
                Instant.parse("2026-08-01T08:02:00Z"), Instant.parse("2026-08-01T08:03:00Z"),
                "test-key-settlement-completed", "test reason"));
        slot.softDelete(Instant.parse("2026-08-02T00:00:00Z"));
        timeSlotRepository.saveAndFlush(slot);

        // when
        Object[] amounts = paymentRepository.sumSettlementAmounts(restaurant.getId(), RefundStatus.COMPLETED,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z")).get(0);

        // then
        assertThat((BigDecimal) amounts[0]).isEqualByComparingTo("30000");
        assertThat((BigDecimal) amounts[1]).isEqualByComparingTo("30000");
        assertThat(((BigDecimal) amounts[0]).subtract((BigDecimal) amounts[1])).isZero();
        assertThat(paid.getStatus().name()).isEqualTo("REFUNDED");
        assertThat(paid.getPaidAt()).isEqualTo(Instant.parse("2026-08-01T08:01:00Z"));
        assertThat(reservationRepository.findSettlementReservations(restaurant.getId(), null, null, PageRequest.of(0, 20))
                .getContent()).extracting(Reservation::getId).containsExactly(reservation.getId());
    }

    @Test
    void 완료되지않은_환불은_지급예정액에서_차감하지않는다() {
        Restaurant restaurant = restaurantRepository.save(Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot slot = timeSlotRepository.save(TimeSlot.create(table.getId(),
                Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z")));
        Reservation reservation = reservationRepository.save(Reservation.create(slot.getId(), 2L));
        Payment paid = Payment.createReady("processing-refund", 2L, slot.getId(), reservation.getId(), PaymentPurpose.JOIN, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-08-01T08:00:00Z"));
        paid.complete(Instant.parse("2026-08-01T08:01:00Z"));
        paymentRepository.save(paid);
        refundRepository.save(Refund.create(paid, BigDecimal.valueOf(10000), RefundStatus.PROCESSING,
                Instant.parse("2026-08-01T08:02:00Z"), null,
                "test-key-settlement-processing", "test reason"));

        Object[] amounts = paymentRepository.sumSettlementAmounts(restaurant.getId(), RefundStatus.COMPLETED,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z")).get(0);

        assertThat((BigDecimal) amounts[0]).isEqualByComparingTo("10000");
        assertThat((BigDecimal) amounts[1]).isZero();
    }

    @Test
    void 정산기간은_서울시간_시작일_포함_종료일_다음날_미만으로_조회한다() {
        Restaurant restaurant = restaurantRepository.save(Restaurant.create(1L, "식당", "주소", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        TimeSlot beforeStart = timeSlotRepository.save(TimeSlot.create(table.getId(),
                Instant.parse("2026-07-31T14:59:59Z"), Instant.parse("2026-07-31T15:30:00Z")));
        TimeSlot atStart = timeSlotRepository.save(TimeSlot.create(table.getId(),
                Instant.parse("2026-07-31T15:00:00Z"), Instant.parse("2026-07-31T16:00:00Z")));
        TimeSlot atEndExclusive = timeSlotRepository.save(TimeSlot.create(table.getId(),
                Instant.parse("2026-08-01T15:00:00Z"), Instant.parse("2026-08-01T16:00:00Z")));
        Reservation excludedBeforeStart = reservationRepository.save(Reservation.create(beforeStart.getId(), 2L));
        Reservation includedAtStart = reservationRepository.save(Reservation.create(atStart.getId(), 2L));
        Reservation excludedAtEnd = reservationRepository.save(Reservation.create(atEndExclusive.getId(), 2L));

        var page = reservationRepository.findSettlementReservations(restaurant.getId(),
                Instant.parse("2026-07-31T15:00:00Z"), Instant.parse("2026-08-01T15:00:00Z"), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Reservation::getId)
                .contains(includedAtStart.getId())
                .doesNotContain(excludedBeforeStart.getId(), excludedAtEnd.getId());
    }
}
