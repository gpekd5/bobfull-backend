# 환불 정합성 재확인 운영 절차

## 대상

`REQUESTED` 또는 `PROCESSING` 상태가 오래 유지된 Refund를 조회 전용 스케줄러가 재확인한다. 이 절차는 외부 환불을 다시 요청하거나 DB 상태를 수동으로 바꾸기 위한 것이 아니다.

## 확인 순서

1. 구조화 로그의 `refundId`, `paymentId`, 상태, 경과 시간, `cancellationId` 존재 여부를 확인한다.
2. 내부 DB에서 Refund·Payment·ReservationParticipant·Reservation 상태가 완료 경로와 일치하는지 확인한다.
3. PortOne 콘솔 또는 Payment 조회에서 `cancellationId`, 취소 금액, `requestedAt`, `cancelledAt`을 확인한다.
4. PortOne 완료가 명확하면 외부 환불을 다시 보내지 말고 다음 스케줄러 실행의 공통 완료 경로 반영을 확인한다.
5. `REFUND_MATCH_AMBIGUOUS` 또는 반복 `REFUND_LOOKUP_FAILED`는 완료로 추정하지 않는다. 장애 기록을 남기고 코드·데이터 원인을 조사한다.
6. `FAILED`는 자동 재시도 대상이 아니다. 실패 사유를 확인한 뒤 결제 담당자와 PortOne 지원 절차로 넘긴다.

실제 거래 식별자와 회원정보는 공개 GitHub Issue·PR에 기록하지 않고 비공개 운영 기록에만 남긴다.
