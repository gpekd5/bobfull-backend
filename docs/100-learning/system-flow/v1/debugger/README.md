# BobFull V1 Code Execution Debugger V2

실제 Java 코드·호출 스택·Runtime State·트랜잭션·락이 단계별로 어떻게 움직이는지 확인하는 정적 시뮬레이터입니다.

기준 `develop` SHA: `b37b9ee828b26cae09e18220e78706bb119a1a4e`

`index.html`을 브라우저에서 직접 열면 됩니다. 외부 서버·CDN·폰트·이미지·API 호출은 사용하지 않습니다.

이 화면은 실제 JVM, MySQL, PortOne을 실행하지 않습니다. 기준 SHA의 결제 완료 코드와 테스트를 바탕으로 확정한 실행 단계를 재생합니다.

## 포함 시나리오

- 정상 완료: `CREATE`, `JOIN`
- 중복 완료: 사전 조회에서 `PAID`, 락 획득 뒤 `PAID`
- 내부 Payment 만료: 외부 검증 뒤 락 안에서 만료 확인
- Reservation·Participant 저장 실패: 통합 테스트의 실패 주입으로 검증한 Rollback

## 코드·테스트 근거

- `PaymentController`, `PaymentCompletionService`, `PortOneSdkPaymentReader`
- `PaymentCompletionTransactionService`, `PaymentRepository`, `Payment`
- `ReservationConfirmationAdapter`, `ReservationConfirmationService`
- `PaymentCompletionServiceTest`
- `PaymentCompletionTransactionServiceTest`
- `PaymentCompletionIdempotencyIntegrationTest`
- `PaymentReservationConfirmationTransactionIntegrationTest`

실제 SQL 수치는 표시하지 않습니다. `PESSIMISTIC_WRITE`는 Repository의 JPA 락 의도를 표시한 것이며, 화면의 재생 시간은 성능 실측값이 아닙니다.

## 알려진 제한

- 만료 경계의 외부 PAID와 내부 실패는 보상 필요 로그까지만 구현되어 있습니다. 환불 실행 파이프라인을 표현하지 않습니다.
- 저장 실패는 운영 요청을 인위적으로 재현한 것이 아니라, 통합 테스트의 실패 주입으로 확인한 트랜잭션 원자성입니다.

V3 Core Engineering Lab은 트러블슈팅·핵심 기술·포트폴리오 사례 중심의 별도 산출물로 제작한다.
