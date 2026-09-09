# AI Human 검토 기록

AI 제안·설계·Issue 계약을 Human이 검토하면서 발견한 누락과 최종 판단을 남긴다.
기술 문제의 원인·후보안·검증 상태는 [트러블슈팅 문서](../../troubleshooting/README.md)에 기록한다.

## 기록 원칙

- Human이 무엇을 발견했고 어떤 판단으로 계약을 수정했는지 중심으로 작성한다.
- AI 제안 원문이나 대화를 그대로 옮기지 않는다.
- 구현 전 예상 위험은 기술 트러블슈팅에서 `검토 중`으로 구분한다.
- 실제 반영 위치와 관련 Issue를 함께 남긴다.

## 결제 Issue 계약 검토 사례

### 1. 외부 결제 식별자 생성 전략 누락

- 관련 Issue: #91
- AI 제안: `paymentId`를 내부에서 생성해 PortOne 요청에도 사용한다.
- Human 발견: 내부 PK와 외부 식별자의 분리, 외부 식별자 생성 방식이 빠져 구현자가 임의로 결정할 수 있다.
- Human 판단: DB 내부 PK는 `Long` 자동 증가 식별자, 외부·PortOne용 `paymentId`는 UUID 기반 `String`으로 분리하고 UNIQUE 제약을 둔다.
- 반영: #91 계약에 UUID 기반 외부 `paymentId`와 PG 조건 확인 항목을 기록했다.

### 2. 다중 통화 범위 축소

- 관련 Issue: #91, #92, #93
- AI 제안: 금액·통화를 분리 저장하고 통화 처리 경계를 상세 설계한다.
- Human 판단: 국내 이용자 대상 V1에는 통화 선택·환율 기능이 필요하지 않다.
- Human 결정: 사용자 통화 선택은 두지 않고 KRW로 고정하며, PortOne 조회 결과의 KRW만 최소 검증한다.

### 3. 구현 전 과도한 정책 확정 방지

- 관련 Issue: #92, #93
- AI 제안: 외부 상태 매핑, 재시도, 보상, 전용 이력 모델을 Issue 단계에서 확정한다.
- Human 판단: 실제 외부 응답과 구현 중 확인할 문제까지 선확정하면 계약이 과도해진다.
- Human 결정: 책임 경계와 완료 조건만 확정하고 상세 상태 전이·재시도·보상은 구현 중 검증 대상으로 남긴다.

### 4. 좌석 선점 만료와 PG 승인 시점 불일치

- 관련 Issue: #92, #93
- AI 초기 설명: 만료 READY Payment를 스케줄러로 처리하고 좌석을 복구한다.
- Human 발견: 스케줄러는 결제하지 않고 이탈한 경우의 좌석 복구에는 도움이 되지만, 내부 만료 후 외부 결제가 승인되는 경우까지 해결하지 못한다.
- Human 결정: 만료 후 외부 승인 가능성을 별도 경계로 기록하고 PortOne V2 수동 승인을 후보안으로 검토한다.
- 관련 기술 검토: [결제 트러블슈팅](../../troubleshooting/payment-troubleshooting.md)

### 5. 예약 취소·환불 트랜잭션 경계 재설계

- 관련 Issue: #44, #45, #131, #141
- 관련 PR: #135(병합됨), #144
- AI 제안: 예약 취소 전체 흐름을 Reservation 락을 보유한 하나의 트랜잭션으로 묶고, 참여자별 환불 처리를 REQUIRES_NEW 트랜잭션으로 즉시 커밋한다. 환불 중 하나가 실패하면 예약 취소 트랜잭션은 예외로 롤백한다.
- Human 발견: 참여자 A의 외부 환불이 REQUIRES_NEW로 먼저 성공 커밋된 뒤 참여자 B의 환불이 실패하면, 바깥 예약 트랜잭션만 롤백돼 A의 `Refund=COMPLETED`/`Payment=REFUNDED`와 `Participant=RESERVED`가 불일치하는 상태가 남을 수 있다. REQUIRES_NEW는 앞선 외부 환불 결과를 보존할 뿐, 예약·참여 상태까지 함께 정합하게 맞춰주는 해결책은 아니었다.
- Human 판단: 전체 흐름을 하나의 트랜잭션으로 묶지 않고, 트랜잭션 없는 파사드가 접수(짧은 트랜잭션)·외부 환불 실행(트랜잭션 밖)·완료 확정(짧은 트랜잭션)의 3단계를 조정하도록 변경한다. 즉시 응답·PortOne 웹훅·정합성 확인 스케줄러가 모두 같은 완료 확정 경로(`RefundCompletionService` → `completeParticipantCancellation`)를 사용하도록 통일한다.
- 반영: `ReservationStatus.CANCELLING`, `ParticipationStatus.CANCEL_REQUESTED`, `ReservationCancellationTransactionService.accept()`(짧은 접수 트랜잭션), `RefundCompletionService`(공통 완료 경로), `completeCancelIfRequested()`(조건부 UPDATE 기반 멱등 처리), Issue #141(정합성 확인 스케줄러 후속 분리), PR #135·#144, ADR 0001.
- 관련 기술 검토: [예약 트러블슈팅](../../troubleshooting/reservation-troubleshooting.md)

### 6. 취소 완료 확정 경로의 Bean 순환 의존 (V2)

- 관련 Issue: #45
- 관련 PR: #144
- 배경: 5번 기록에서 확정한 공통 완료 경로(`RefundCompletionService` → `completeParticipantCancellation`)를 구현하는 과정에서, 취소 시작을 담당하는 `ReservationCancellationService`가 완료 확정까지 함께 담당하고 있었다. 그 결과 `ReservationCancellationService → ReservationCancellationRefundPort → (결제 Adapter) → RefundCompletionService → ReservationCancellationService`로 이어지는 Spring Bean 생성자 의존 그래프가 자기 자신으로 돌아와, 정상적으로 생성자 주입하면 애플리케이션 Context가 기동하지 못했다. `ObjectProvider<ReservationCancellationService>`로 Bean 조회 시점을 늦추는 임시 우회가 들어갔다.
- Human 발견: `ObjectProvider` 우회는 순환을 없앤 것이 아니라 Bean 조회 시점만 늦춘 것이며, 최종 계약으로 유지할 수 없다. 원인은 "결제가 예약을 아는 것" 자체가 아니라, 취소 시작과 완료 확정이라는 서로 다른 두 책임이 한 클래스(`ReservationCancellationService`)에 있어 그 클래스가 결제→예약 호출의 시작점이자 도착점을 동시에 겸했기 때문이다.
- Human 판단: Spring Event나 두 도메인 위에 별도 상위 조정 계층(오케스트레이터)을 새로 두지 않는다. 대신 완료 확정 책임만 `ReservationCancellationCompletionService`로 분리하고, 결제 도메인이 소유한 좁은 계약 `ReservationCancellationCompletionPort`(구현체 `ReservationCancellationCompletionAdapter`)로 연결한다. 이는 기존 결제 확정 흐름(`ReservationConfirmationPort`/`Adapter`)과 동일한 패턴이며, `docs/architecture/architecture.md`가 이미 예약↔결제를 양방향으로 문서화하고 있어 새 컴포넌트를 추가하지 않아도 된다.
- 반영: `ReservationCancellationCompletionPort`(payment 소유), `ReservationCancellationCompletionAdapter`, `ReservationCancellationCompletionService`(예약 소유, `Propagation.MANDATORY`로 결제 완료 트랜잭션 참여를 강제) 신규 추가. `RefundCompletionService`에서 `ObjectProvider`와 `ReservationCancellationService` 직접 참조 제거. `ReservationCancellationService`는 취소 시작·환불 요청 Port 호출만 담당하도록 축소. 내부 네 상태(Refund·Payment·Participant·Reservation)는 여전히 하나의 `REQUIRES_NEW` 완료 트랜잭션으로 반영해, 5번 기록의 정합성 계약을 그대로 유지했다.
- V3 후속 판단: 이 동기 구조가 실제로 병목이 되는지는 k6로 실측(웹훅 응답 p95·p99, Reservation 락 대기, DB 커넥션 점유)한 뒤에만 Spring Event 전환을 검토한다. PortOne 외부 API 지연은 이미 트랜잭션·락 밖에서 처리하므로 이 판단의 근거로 쓰지 않는다.
- 관련 기술 검토: [예약 트러블슈팅](../../troubleshooting/reservation-troubleshooting.md)

### 7. 결과 불명확 Refund 상태 표현 — `UNKNOWN` 미도입 (V2)

- 관련 Issue: #44, #45, #141
- 관련 PR: #144
- 배경: 외부 코덱스 리뷰가 `PROJECT_CONTEXT`·`ERD`·API 명세·`DOMAIN_DEPENDENCIES`를 실제 코드와 대조하다가, Issue #44 완료 조건("`Refund=UNKNOWN` 상태가 반영된다")과 실제 구현(`RefundStatus`에 `UNKNOWN` 없이 결과 불명확을 `REQUESTED` 유지로 표현)이 서로 다르다는 것을 발견했다. 담당자 AI는 이를 임의로 해결하지 않고 4개 문서에 "Human 결정 필요"로만 표시했다.
- AI 보완 설명: `REQUESTED`는 Refund 생성과 PortOne 응답 처리가 같은 호출 안에서 거의 즉시 다음 상태로 넘어가는 구조라, `REQUESTED`가 일정 시간 이상 머문다는 사실 자체가 이미 결과 불명확 신호와 동일하다. `UNKNOWN`을 추가해도 correctness상 새로 얻는 것은 없고, 재조회 대상 조회 조건("오래 머문 REQUESTED/PROCESSING")도 동일하게 동작한다. 다만 상태 이름만으로 운영자가 "이건 확인이 필요한 상태다"를 바로 알 수 있다는 가독성 이점은 있다.
- Human 판단: 애매한 상태(`UNKNOWN`)를 별도로 늘리지 않는다. 결과 불명확은 `REQUESTED` 유지로 표현하는 현재 구현을 최종 정책으로 확정한다.
- 반영: Issue #44 완료 조건·상태 모델·필수 테스트 항목의 `UNKNOWN` 언급을 "`REQUESTED` 유지, `UNKNOWN` 미도입"으로 수정하고 관련 완료 조건 2건을 체크 완료로 표시했다. Issue #141(아직 미구현)의 재조회 대상 조회 조건에서도 `UNKNOWN`을 제거해 `REQUESTED/PROCESSING`만 남겼다. `PROJECT_CONTEXT`·`ERD`·`DOMAIN_DEPENDENCIES`의 "Human 결정 필요" 각주를 확정된 정책 설명으로 교체했다.
- 관련 기술 검토: [예약 트러블슈팅](../../troubleshooting/reservation-troubleshooting.md)

## 새 기록 작성

[AI Human 검토 양식](../../templates/ai-human-review-template.md)을 복사해 사용한다.
