package com.bobfull.reservation.application.service;

import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import com.bobfull.reservation.application.service.ReservationCancellationTransactionService.RecruitmentDeadlineAcceptance;
import com.bobfull.reservation.application.service.ReservationCancellationTransactionService.RecruitmentDeadlineOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 모집 마감 기한 도달 후보 하나의 접수와 환불 요청 시작을 담당한다(Issue #47).
 * 결과 이메일 안내는 이 서비스가 직접 호출하지 않는다 — {@code acceptRecruitmentDeadline}이
 * 핵심 상태 변경과 같은 트랜잭션에 이메일 Outbox와 수신자별 전송 이력을 저장하고, 커밋 뒤
 * 별도 Processor가 실제 발송을 처리한다(Issue #183). 그 결과, 환불 요청이 실패해도 이미
 * 커밋 시점에 저장된 이메일 Outbox 처리에는 영향을 주지 않는다 — 두 후속 작업이 서로
 * 독립적으로 실행된다.
 */
@Service
@RequiredArgsConstructor
public class RecruitmentDeadlineCancellationService {
    private final ReservationCancellationTransactionService transactionService;
    private final ReservationCancellationRefundPort reservationCancellationRefundPort;

    public RecruitmentDeadlineOutcome process(Long reservationId) {
        RecruitmentDeadlineAcceptance acceptance = transactionService.acceptRecruitmentDeadline(reservationId);
        if (acceptance.outcome() == RecruitmentDeadlineOutcome.CANCELLED) {
            reservationCancellationRefundPort.requestRefunds(acceptance.refundCommand());
        }
        return acceptance.outcome();
    }
}
