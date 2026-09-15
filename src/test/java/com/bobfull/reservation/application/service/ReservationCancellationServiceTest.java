package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.reservation.domain.CancellationScope;
import com.bobfull.reservation.presentation.request.ReservationCancellationRequest;
import com.bobfull.reservation.presentation.response.ReservationCancellationResponse;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
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
class ReservationCancellationServiceTest {
    @Mock ReservationCancellationTransactionService transactionService;
    @Mock ReservationCancellationRefundPort refundPort;

    private ReservationCancellationService service() {
        return new ReservationCancellationService(transactionService, refundPort);
    }

    @Test
    void 취소_접수_커밋_후_환불Port를_호출하고_CANCEL_REQUESTED를_반환한다() {
        RefundRequestCommand command = new RefundRequestCommand(1L, List.of(2L), 3L, "사유");
        when(transactionService.accept(eq(3L), eq(1L), any())).thenReturn(
                new ReservationCancellationTransactionService.CancellationAcceptance(
                        1L, 2L, CancellationScope.PARTICIPATION, command));
        when(refundPort.requestRefunds(command)).thenReturn(
                List.of(new RefundRequestResult(2L, "PROCESSING")));

        ReservationCancellationResponse result = service().cancel(
                3L, 1L, new ReservationCancellationRequest("사유"));

        assertThat(result.participationStatus()).isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        InOrder order = inOrder(transactionService, refundPort);
        order.verify(transactionService).accept(eq(3L), eq(1L), any());
        order.verify(refundPort).requestRefunds(command);
    }

    @Test
    void 환불Port_예외는_전달되고_구형_원상복구를_수행하지_않는다() {
        RefundRequestCommand command = new RefundRequestCommand(1L, List.of(2L), 3L, "사유");
        when(transactionService.accept(eq(3L), eq(1L), any())).thenReturn(
                new ReservationCancellationTransactionService.CancellationAcceptance(
                        1L, 2L, CancellationScope.PARTICIPATION, command));
        when(refundPort.requestRefunds(command)).thenThrow(
                new CustomException(ReservationErrorCode.CANCELLATION_NOT_ALLOWED));

        assertThatThrownBy(() -> service().cancel(
                3L, 1L, new ReservationCancellationRequest("사유")))
                .isInstanceOf(CustomException.class);
    }
}
