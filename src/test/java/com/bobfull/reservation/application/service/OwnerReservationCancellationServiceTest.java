package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.reservation.presentation.dto.OwnerReservationCancellationResponse;
import com.bobfull.reservation.presentation.dto.ReservationCancellationRequest;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort.RefundRequestCommand;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort.RefundRequestResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnerReservationCancellationServiceTest {
    @Mock ReservationCancellationTransactionService transactionService;
    @Mock ReservationCancellationRefundPort refundPort;

    private OwnerReservationCancellationService service() {
        return new OwnerReservationCancellationService(transactionService, refundPort);
    }

    @Test
    void 취소_접수_커밋_후_환불Port를_호출하고_reservationId를_반환한다() {
        RefundRequestCommand command = new RefundRequestCommand(1L, List.of(2L, 3L), 9L, "식당 내부 사정");
        when(transactionService.acceptByOwner(eq(9L), eq(1L), eq("식당 내부 사정"))).thenReturn(
                new ReservationCancellationTransactionService.OwnerCancellationAcceptance(1L, command));
        when(refundPort.requestRefunds(command)).thenReturn(
                List.of(new RefundRequestResult(2L, "PROCESSING"), new RefundRequestResult(3L, "PROCESSING")));

        OwnerReservationCancellationResponse result = service().cancel(
                9L, 1L, new ReservationCancellationRequest("식당 내부 사정"));

        assertThat(result.reservationId()).isEqualTo(1L);
        InOrder order = inOrder(transactionService, refundPort);
        order.verify(transactionService).acceptByOwner(eq(9L), eq(1L), eq("식당 내부 사정"));
        order.verify(refundPort).requestRefunds(command);
    }

    @Test
    void 접수_단계_예외는_환불Port_호출_없이_전달된다() {
        when(transactionService.acceptByOwner(eq(9L), eq(1L), eq("식당 내부 사정")))
                .thenThrow(new CustomException(ReservationErrorCode.INVALID_STATE));

        assertThatThrownBy(() -> service().cancel(9L, 1L, new ReservationCancellationRequest("식당 내부 사정")))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void 환불Port_예외는_그대로_전달된다() {
        RefundRequestCommand command = new RefundRequestCommand(1L, List.of(2L), 9L, "식당 내부 사정");
        when(transactionService.acceptByOwner(eq(9L), eq(1L), eq("식당 내부 사정"))).thenReturn(
                new ReservationCancellationTransactionService.OwnerCancellationAcceptance(1L, command));
        when(refundPort.requestRefunds(command)).thenThrow(
                new CustomException(ReservationErrorCode.INVALID_STATE));

        assertThatThrownBy(() -> service().cancel(9L, 1L, new ReservationCancellationRequest("식당 내부 사정")))
                .isInstanceOf(CustomException.class);
    }
}
