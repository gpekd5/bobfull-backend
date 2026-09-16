package com.bobfull.reservation.application.service;

import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import com.bobfull.reservation.application.service.ReservationCancellationTransactionService.RecruitmentDeadlineAcceptance;
import com.bobfull.reservation.application.service.ReservationCancellationTransactionService.RecruitmentDeadlineOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 모집 마감 후보의 상태를 확정하고 인원 미달이면 환불을 시작한다.
@Service
@RequiredArgsConstructor
public class RecruitmentDeadlineCancellationService {
    private final ReservationCancellationTransactionService transactionService;
    private final ReservationCancellationRefundPort reservationCancellationRefundPort;

    // 상태 변경과 이메일 Outbox를 먼저 커밋해 이후 환불 실패와 알림 처리를 분리한다.
    public RecruitmentDeadlineOutcome process(Long reservationId) {
        RecruitmentDeadlineAcceptance acceptance = transactionService.acceptRecruitmentDeadline(reservationId);
        if (acceptance.outcome() == RecruitmentDeadlineOutcome.CANCELLED) {
            reservationCancellationRefundPort.requestRefunds(acceptance.refundCommand());
        }
        return acceptance.outcome();
    }
}
