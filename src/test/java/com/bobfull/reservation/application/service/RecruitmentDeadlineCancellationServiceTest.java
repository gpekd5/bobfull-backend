package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort.RefundRequestCommand;
import com.bobfull.reservation.application.service.ReservationCancellationTransactionService.RecruitmentDeadlineAcceptance;
import com.bobfull.reservation.application.service.ReservationCancellationTransactionService.RecruitmentDeadlineOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecruitmentDeadlineCancellationServiceTest {
    @Mock ReservationCancellationTransactionService transactionService;
    @Mock ReservationCancellationRefundPort refundPort;

    private RecruitmentDeadlineCancellationService service() {
        return new RecruitmentDeadlineCancellationService(transactionService, refundPort);
    }

    @Test
    void CANCELLED_결과면_환불Port를_호출한다() {
        RefundRequestCommand command = new RefundRequestCommand(1L, List.of(2L, 3L), 9L, "모집 마감 기준 인원 미달로 자동 취소되었습니다");
        when(transactionService.acceptRecruitmentDeadline(1L)).thenReturn(
                new RecruitmentDeadlineAcceptance(1L, RecruitmentDeadlineOutcome.CANCELLED, command));

        RecruitmentDeadlineOutcome outcome = service().process(1L);

        assertThat(outcome).isEqualTo(RecruitmentDeadlineOutcome.CANCELLED);
        verify(refundPort).requestRefunds(command);
    }

    @Test
    void CLOSED_ONLY_결과면_환불Port를_호출하지_않는다() {
        when(transactionService.acceptRecruitmentDeadline(1L)).thenReturn(
                new RecruitmentDeadlineAcceptance(1L, RecruitmentDeadlineOutcome.CLOSED_ONLY, null));

        RecruitmentDeadlineOutcome outcome = service().process(1L);

        assertThat(outcome).isEqualTo(RecruitmentDeadlineOutcome.CLOSED_ONLY);
        verify(refundPort, never()).requestRefunds(any());
    }

    @Test
    void ALREADY_PROCESSED_결과면_환불Port를_호출하지_않는다() {
        when(transactionService.acceptRecruitmentDeadline(1L)).thenReturn(
                new RecruitmentDeadlineAcceptance(1L, RecruitmentDeadlineOutcome.ALREADY_PROCESSED, null));

        RecruitmentDeadlineOutcome outcome = service().process(1L);

        assertThat(outcome).isEqualTo(RecruitmentDeadlineOutcome.ALREADY_PROCESSED);
        verify(refundPort, never()).requestRefunds(any());
    }

    @Test
    void 환불_요청이_실패해도_예외가_그대로_호출자에게_전파된다() {
        // 이메일 안내는 acceptRecruitmentDeadline이 발행한 이벤트로 이미 커밋 시점에 트리거되므로
        // (Issue #168 V2), 환불 실패가 이메일 발송 자체를 막지 않는다 — 다만 환불 실패는 여전히
        // 기존 스케줄러의 실패 로그 경로(processOne의 catch)까지 전파돼야 한다.
        RefundRequestCommand command = new RefundRequestCommand(1L, List.of(2L), 9L, "모집 마감 기준 인원 미달로 자동 취소되었습니다");
        when(transactionService.acceptRecruitmentDeadline(1L)).thenReturn(
                new RecruitmentDeadlineAcceptance(1L, RecruitmentDeadlineOutcome.CANCELLED, command));
        doThrow(new IllegalStateException("환불 실패(테스트)")).when(refundPort).requestRefunds(command);

        assertThatThrownBy(() -> service().process(1L)).isInstanceOf(IllegalStateException.class);
    }
}
