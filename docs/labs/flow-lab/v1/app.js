/*
 * BobFull V1 Flow Lab
 * 측정 기준 브랜치: develop / 측정 기준 Commit SHA: feebb8c24aac4f6043c89578e5dfa55fa05e8036
 *
 * 이 파일은 실제 코드를 실행하지 않는다. 각 사용자 행동(userAction)의 steps[]는 기준 SHA의
 * 실제 코드·테스트를 근거로 사람이 정리한 재생 데이터다. PR 최신 검증 Head는 측정값의 기준 SHA와
 * 구분해 PR 본문과 RESULTS.md에 기록한다. actor/target/performance는 layer 등
 * 이미 검증된 필드로부터 파생(derive)되며, 별도로 지어낸 값이 아니다.
 */

const CONFIG = {
  branch: "develop",
  sha: "feebb8c24aac4f6043c89578e5dfa55fa05e8036",
  generatedDate: "2026-08-03"
};

const PERFORMANCE_BASELINES = {
  "available-dining-sessions": { status: "실측 완료", measuredAt: "2026-08-03T12:37:46+09:00", http: "평균 19.289ms / p50 15.367ms / 최소 13.856ms / 최대 32.960ms", queries: "11건 (Hibernate Statistics prepareStatementCount)", source: "perf-baseline/RESULTS.md" },
  "expected-settlement": { status: "실측 완료", measuredAt: "2026-08-03T12:37:46+09:00", http: "평균 5.763ms / p50 5.285ms / 최소 4.968ms / 최대 6.980ms", queries: "2건 (Hibernate Statistics prepareStatementCount)", source: "perf-baseline/RESULTS.md" },
  "settlement-list": { status: "실측 완료", measuredAt: "2026-08-03T12:37:46+09:00", http: "평균 13.047ms / p50 10.806ms / 최소 9.576ms / 최대 21.270ms", queries: "5건 (Hibernate Statistics prepareStatementCount)", source: "perf-baseline/RESULTS.md" }
};

/* ------------------------------------------------------------------ */
/* 공통 파생 유틸 (actor/target, 성능 관찰, 그룹핑)                          */
/* ------------------------------------------------------------------ */

function deriveActorTarget(step, roleActor) {
  if (step.layer === "HTTP") return { actor: roleActor, target: "BACKEND" };
  if (step.layer === "Response") return { actor: "BACKEND", target: roleActor };
  if (step.layer === "External Port") return { actor: "BACKEND", target: "PORTONE" };
  const repo = step.repositoryAction || "";
  if (step.layer === "Repository" || (repo && repo !== "없음" && !/^없음/.test(repo))) {
    return { actor: "BACKEND", target: "MYSQL" };
  }
  return { actor: "BACKEND", target: "BACKEND" };
}

function derivePerformanceCategory(step) {
  const repo = step.repositoryAction || "";
  const ext = step.externalCall || "";
  if (ext && ext !== "없음" && !/호출 안 함|없음/.test(ext)) return "외부 네트워크 I/O";
  if (step.layer === "Lock") return "락 대기 가능 구간";
  if (step.layer === "Transaction" || step.layer === "Commit" || step.layer === "Rollback") return "트랜잭션 경계";
  if (/SELECT|조회|sum|find|Read|검색/i.test(repo) && !/save|insert|생성|저장|update|flush/i.test(repo)) return "DB 조회";
  if (/save|insert|생성|저장|update|flush/i.test(repo)) return "DB 쓰기";
  return "메모리 연산";
}

function deriveBottleneckRisk(category, lockHeld) {
  if (category === "외부 네트워크 I/O") return "외부 API 응답 시간에 영향을 받을 수 있음";
  if (category === "락 대기 가능 구간") return "동시 요청 시 락 대기가 발생할 수 있음";
  if (category === "DB 쓰기" && lockHeld) return "락 보유 중 쓰기라 대기 시간에 영향 가능";
  if (category === "DB 조회" || category === "DB 쓰기") return "DB 응답 시간에 영향받을 수 있음(수치 미측정)";
  return "낮음(메모리 연산 중심)";
}

function derivePerformance(step, cumulativeState) {
  const category = derivePerformanceCategory(step);
  const lockHeld = !!(cumulativeState && (
    /보유/.test(cumulativeState.paymentLock || "") ||
    /보유/.test(cumulativeState.reservationLock || "") ||
    /보유/.test(cumulativeState.timeSlotLock || "")
  ));
  const txState = (cumulativeState && cumulativeState.transactionState) || "없음";
  const inTransaction = /진행중|시작됨/.test(txState);
  return {
    category,
    transactionScope: inTransaction ? "트랜잭션 안" : "트랜잭션 밖",
    dbLock: lockHeld ? "보유 중" : "없음",
    queryInvolved: /SELECT|INSERT|save|find|sum|Read|검색|UPDATE/i.test(step.repositoryAction || "") ? "예" : "아니오",
    externalCallInvolved: (step.externalCall && step.externalCall !== "없음" && !/호출 안 함/.test(step.externalCall)) ? "예" : "아니오",
    bottleneckRisk: deriveBottleneckRisk(category, lockHeld),
    measured: false,
    duration: null,
    measurementSource: "코드 구조 기반 관찰(실제 측정값 아님)"
  };
}

function makeGroups(steps, defs) {
  let idx = 0;
  return defs.map((def) => {
    const startIdx = idx;
    const endIdx = idx + def.count - 1;
    idx += def.count;
    return { title: def.title, startIdx, endIdx };
  });
}

/* ------------------------------------------------------------------ */
/* 공통 초기 상태 (Ch4 결제 완료 계열)                                      */
/* ------------------------------------------------------------------ */

function baseInitialState() {
  return {
    externalPaymentStatus: "조회 전",
    paymentStatus: "READY",
    paidAt: null,
    expiresAt: "2026-09-01T00:00:00Z",
    reservationId: null,
    participationId: null,
    reservationStatus: "-",
    recruitmentStatus: "-",
    participantCreated: false,
    currentParticipants: 0,
    capacity: 4,
    transactionState: "없음",
    paymentLock: "없음",
    reservationLock: "없음"
  };
}

/* ------------------------------------------------------------------ */
/* Ch4-A. 정상 결제 완료 (CREATE / JOIN 변형)                                */
/* ------------------------------------------------------------------ */

function successSteps(purpose) {
  const isCreate = purpose === "CREATE";
  const capacity = 4;
  const initialParticipants = isCreate ? 0 : 2;
  const addedPartySize = isCreate ? 3 : 1;
  const finalParticipants = initialParticipants + addedPartySize;
  const threshold = capacity - 1;

  const steps = [];

  steps.push({
    id: "http-request",
    layer: "HTTP",
    title: "결제 완료 HTTP 요청 수신",
    filePath: null, className: null, methodName: null, codeType: "framework", code: null,
    httpRequest: `POST /api/payments/{paymentId}/complete\nAuthorization: Bearer <access-token>`,
    httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
    evidence: "Spring MVC DispatcherServlet의 라우팅이며 BobFull 애플리케이션 코드가 아니다.",
    stateChanges: {},
    explanation: {
      whatRuns: ["JwtAuthenticationFilter가 Authorization 헤더를 파싱해 인증된 AuthMember를 SecurityContext에 등록한다."],
      whatChanged: ["아직 도메인 상태 변경 없음."],
      safeguards: ["/api/webhooks/portone 경로만 이 필터를 건너뛴다(JwtAuthenticationFilter)."],
      whySequence: ["인증이 확인되지 않으면 이후 Controller 로직 자체가 실행되지 않는다."],
      ifFails: ["JWT가 없거나 유효하지 않으면 Security 계층에서 401로 즉시 응답하고 Controller에 도달하지 않는다."],
      fact: ["인증 방식은 JWT Bearer 토큰이다(JwtAuthenticationFilter)."]
    }
  });

  steps.push({
    id: "controller-entry",
    layer: "Controller",
    title: "PaymentController.complete 진입",
    filePath: "src/main/java/com/bobfull/payment/controller/PaymentController.java",
    className: "PaymentController", methodName: "complete", codeType: "actual",
    code:
`@PostMapping("/{paymentId}/complete")
public ApiResponse<PaymentCompletionResponse> complete(
        @AuthenticationPrincipal AuthMember authMember, @PathVariable String paymentId) {
    PaymentCompletionTransactionService.PaymentCompletionResult result =
            paymentCompletionService.complete(paymentId, authMember.id());
    return ApiResponse.success(
            PaymentCompletionResponse.from(result.payment(), result.reservationId(), result.participationId()));
}`,
    externalCall: "없음", repositoryAction: "없음", error: null,
    evidence: "PaymentController.java:27-32",
    stateChanges: {},
    explanation: {
      whatRuns: ["인증된 memberId와 경로변수 paymentId를 PaymentCompletionService.complete로 그대로 위임한다."],
      whatChanged: ["없음. Controller 자체는 분기·검증 로직을 갖지 않는다."],
      safeguards: ["Controller는 얇게 유지되어 검증·트랜잭션 책임이 Service 계층에 집중된다."],
      whySequence: ["요청을 Service로 넘기기 전 단계이므로 아직 아무 외부 호출도 발생하지 않는다."],
      ifFails: ["Service가 던지는 CustomException은 GlobalExceptionHandler가 처리하므로 Controller에는 try/catch가 없다."],
      fact: ["Controller는 결제 소유권 검증을 직접 수행하지 않는다(Service로 위임)."]
    }
  });

  steps.push({
    id: "pre-lookup",
    layer: "Service",
    title: "Payment 사전 조회 (락 없음)",
    filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionService.java",
    className: "PaymentCompletionService", methodName: "complete", codeType: "actual",
    code:
`public PaymentCompletionTransactionService.PaymentCompletionResult complete(String paymentId, Long memberId) {
    Payment payment = paymentRepository.findByPaymentId(paymentId)
            .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    if (!payment.isOwnedBy(memberId)) throw new CustomException(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
    return completeVerified(paymentId, payment, memberId);
}`,
    externalCall: "없음",
    repositoryAction: "PaymentRepository.findByPaymentId(paymentId) — 단순 SELECT, 락 없음",
    error: null,
    evidence: "PaymentCompletionService.java:32-36 / 테스트: PaymentCompletionServiceTest.존재하지_않는_Payment은_결제_검증에_실패하고_외부_호출을_수행하지_않는다",
    stateChanges: { externalPaymentStatus: "조회 전" },
    explanation: {
      whatRuns: ["paymentId로 Payment를 락 없이 조회한다.", "결과가 없으면 PAYMENT_NOT_FOUND(404)를 즉시 던진다."],
      whatChanged: ["없음. 조회만 수행."],
      safeguards: ["여기서 조회가 실패하면 이후 PortOne 호출·트랜잭션이 전혀 시작되지 않는다(verifyNoInteractions로 검증됨)."],
      whySequence: ["트랜잭션·락을 잡기 전에 대상이 존재하는지부터 저비용으로 확인한다."],
      ifFails: ["PAYMENT_NOT_FOUND 예외가 즉시 던져지고 4번 이후 단계는 실행되지 않는다."],
      fact: ["이 조회는 findByPaymentId(락 없음)이며, 실제 상태 전이 시점의 락 조회(findWithLockByPaymentId)와는 다른 메서드다."]
    }
  });

  steps.push({
    id: "pre-ownership",
    layer: "Domain",
    title: "소유권 사전 검증",
    filePath: "src/main/java/com/bobfull/payment/entity/Payment.java",
    className: "Payment", methodName: "isOwnedBy", codeType: "actual",
    code:
`public boolean isOwnedBy(Long memberId) {
    return this.memberId.equals(memberId);
}`,
    externalCall: "없음", repositoryAction: "없음", error: null,
    evidence: "Payment.java:192-194 / 테스트: PaymentCompletionServiceTest.Payment_소유자가_아니면_결제_검증에_실패하고_외부_호출을_수행하지_않는다",
    stateChanges: {},
    explanation: {
      whatRuns: ["요청자의 memberId와 Payment.memberId를 비교한다."],
      whatChanged: ["없음."],
      safeguards: ["불일치 시 403 PAYMENT_ACCESS_DENIED — 이 지점까지 외부 PortOne 호출은 전혀 발생하지 않는다."],
      whySequence: ["비용이 큰 외부 API 호출 전에 저비용 검증(존재 여부 → 소유권)을 먼저 수행한다."],
      ifFails: ["PAYMENT_ACCESS_DENIED(403)로 즉시 종료, 5번 이후 실행되지 않는다."],
      fact: []
    }
  });

  steps.push({
    id: "portone-read",
    layer: "External Port",
    title: "PortOne 결제 단건 조회",
    filePath: "src/main/java/com/bobfull/payment/adapter/PortOneSdkPaymentReader.java",
    className: "PortOneSdkPaymentReader", methodName: "read", codeType: "actual",
    code:
`@Override
public PortOnePayment read(String paymentId) {
    Payment payment = portOneClient.getPayment().getPayment(paymentId).join();
    if (payment instanceof PaidPayment paidPayment) {
        return new PortOnePayment(paidPayment.getId(), true,
                BigDecimal.valueOf(paidPayment.getAmount().getTotal()), paidPayment.getCurrency().getValue());
    }
    return new PortOnePayment(paymentId, false, null, null);
}`,
    externalCall: "PortOne SDK: portOneClient.getPayment().getPayment(paymentId) — 이번 시나리오에서는 정상 PAID 응답",
    repositoryAction: "없음", error: null,
    evidence: "PortOneSdkPaymentReader.java:19-27 (PortOnePaymentReader 인터페이스, PortOnePayment.java:5-9)",
    stateChanges: { externalPaymentStatus: "PAID (조회됨)" },
    explanation: {
      whatRuns: ["PortOne SDK로 결제 단건을 조회하고, PaidPayment 서브타입이면 paid=true/금액/통화를 매핑한다."],
      whatChanged: ["externalPaymentStatus가 '조회됨(PAID)'로 바뀐다. 아직 내부 Payment 상태는 변하지 않는다."],
      safeguards: ["이 호출은 DB 트랜잭션·락 밖에서 실행된다 — 외부 HTTP 왕복 시간 동안 행 락을 붙잡지 않는다."],
      whySequence: ["락을 잡기 전에 외부 검증을 먼저 끝내야 락 보유 시간이 짧아진다."],
      ifFails: ["PortOne 상태가 PAID가 아니거나 SDK가 예외를 던지면 이후 트랜잭션은 시작되지 않는다."],
      fact: ["PortOneSdkPaymentReader는 CANCELLED/FAILED/READY 등 세부 상태를 구분하지 않고 paid(boolean) 하나로 단순화한다."],
      futureNotes: ["외부 상태 세분화(CANCELLED/FAILED 구분)는 현재 미구현이며 2차 개선 후보다."]
    }
  });

  steps.push({
    id: "external-verify",
    layer: "Service",
    title: "외부 결제 상태·금액·통화 검증",
    filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionService.java",
    className: "PaymentCompletionService", methodName: "completeVerified", codeType: "actual",
    code:
`if (!paymentId.equals(external.paymentId()) || !external.paid() || external.amount() == null
        || payment.getAmount().compareTo(external.amount()) != 0
        || !Payment.CURRENCY_KRW.equals(external.currency()) || !payment.getCurrency().equals(external.currency())) {
    throw new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
}
return completeAfterExternalPaid(paymentId, memberId);`,
    externalCall: "없음(직전 결과 재사용)", repositoryAction: "없음", error: null,
    evidence: "PaymentCompletionService.java:60-66 / 테스트: PortOne_승인_금액이_내부_금액과_다르면..., 내부_금액의_scale만_다르면_정상_검증을_통과한다 등",
    stateChanges: {},
    explanation: {
      whatRuns: ["paymentId 일치, PAID 여부, 금액(BigDecimal.compareTo), 통화(KRW 고정) 4가지를 모두 통과해야 다음 단계로 진행한다."],
      whatChanged: ["없음. 검증만 수행."],
      safeguards: ["금액 비교는 compareTo이므로 10000과 10000.00처럼 scale만 다른 값은 정상 통과한다."],
      whySequence: ["트랜잭션·락을 시작하기 전에 외부 검증을 모두 마쳐야 락 보유 구간에 외부 호출이 섞이지 않는다."],
      ifFails: ["하나라도 실패하면 409 PAYMENT_VERIFICATION_FAILED, 트랜잭션은 시작되지 않는다."],
      fact: []
    }
  });

  steps.push({
    id: "tx-start",
    layer: "Transaction",
    title: "내부 트랜잭션 시작",
    filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
    className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
    code:
`@Transactional
public PaymentCompletionResult complete(String paymentId, Long memberId) {
    Payment payment = paymentRepository.findWithLockByPaymentId(paymentId)
            .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    // ...`,
    externalCall: "없음", repositoryAction: "없음(다음 단계에서 락 조회)", error: null,
    evidence: "PaymentCompletionTransactionService.java:28-31",
    stateChanges: { transactionState: "시작됨" },
    explanation: {
      whatRuns: ["@Transactional 메서드 진입으로 Spring이 새 DB 트랜잭션을 연다."],
      whatChanged: ["transactionState: 없음 → 시작됨."],
      safeguards: ["PaymentCompletionService 자체에는 @Transactional이 없다 — 트랜잭션은 여기서 처음 열린다."],
      whySequence: ["외부 PortOne 호출(5번)이 이미 끝난 뒤에 트랜잭션이 열리므로, 트랜잭션 범위는 상태 전이~예약 확정까지로 짧게 유지된다."],
      ifFails: ["이 지점 이후 발생하는 RuntimeException은 전부 이 트랜잭션을 롤백시킨다."],
      fact: ["PaymentCompletionTransactionService.complete가 이 흐름에서 유일한 @Transactional 경계다."]
    }
  });

  steps.push({
    id: "lock-acquire",
    layer: "Lock",
    title: "Payment 비관적 락 획득",
    filePath: "src/main/java/com/bobfull/payment/repository/PaymentRepository.java",
    className: "PaymentRepository", methodName: "findWithLockByPaymentId", codeType: "actual",
    code: `@Lock(LockModeType.PESSIMISTIC_WRITE)\nOptional<Payment> findWithLockByPaymentId(String paymentId);`,
    externalCall: "없음",
    repositoryAction: "SELECT ... FOR UPDATE 예상(JPA @Lock(PESSIMISTIC_WRITE)가 생성하는 락 동작, 실제 SQL 로그 캡처는 아님)",
    error: null, evidence: "PaymentRepository.java:28-29",
    stateChanges: { paymentLock: "보유" },
    explanation: {
      whatRuns: ["같은 payment_id 행에 대해 DB 행 잠금을 건다. 동시에 같은 Payment를 완료 처리하려는 다른 요청은 이 지점에서 대기한다."],
      whatChanged: ["paymentLock: 없음 → 보유."],
      safeguards: ["이 락 덕분에 이후 상태 재검증~PAID 전이~예약 확정까지가 직렬화된다."],
      whySequence: ["외부 호출이 끝난 뒤에 락을 잡으므로 락 보유 시간이 최소화된다."],
      ifFails: ["대상 Payment가 없으면 PAYMENT_NOT_FOUND(이 분기 자체를 직접 검증하는 유닛 테스트는 확인되지 않음)."],
      fact: ["@Lock(LockModeType.PESSIMISTIC_WRITE)는 Spring Data JPA가 SELECT ... FOR UPDATE로 변환하는 표준 동작이며, 이 라인은 실제 SQL 로그가 아니라 JPA 동작에 대한 설명이다."]
    }
  });

  steps.push({
    id: "ownership-recheck",
    layer: "Domain",
    title: "소유권 재검증 (락 보유 상태)",
    filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
    className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
    code: `if (memberId != null && !payment.isOwnedBy(memberId)) {\n    throw new CustomException(PaymentErrorCode.PAYMENT_ACCESS_DENIED);\n}`,
    externalCall: "없음", repositoryAction: "없음", error: null,
    evidence: "PaymentCompletionTransactionService.java:32-34 / 테스트: PaymentCompletionTransactionServiceTest.잠금_획득후_소유권이_일치하지_않으면_접근을_거부하고_Port를_호출하지_않는다",
    stateChanges: {},
    explanation: {
      whatRuns: ["memberId가 null이 아닐 때만 소유권을 다시 검사한다(웹훅 경로는 memberId=null이라 스킵)."],
      whatChanged: ["없음."],
      safeguards: ["사전 검증과 락 이후 재검증을 모두 두어, 두 검증 사이의 데이터 변경 가능성에 대비한다."],
      whySequence: ["락을 잡은 뒤 실제 상태 전이를 하기 전 마지막 안전장치다."],
      ifFails: ["PAYMENT_ACCESS_DENIED(403), ReservationConfirmationPort는 호출되지 않는다."],
      fact: []
    }
  });

  steps.push({
    id: "status-gate",
    layer: "Domain",
    title: "PAID 멱등 확인 → EXPIRED 거절 → READY 아니면 거절",
    filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
    className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
    code:
`if (payment.getStatus() == PaymentStatus.PAID) {
    return new PaymentCompletionResult(payment, payment.getReservationId(), payment.getReservationParticipantId());
}
if (payment.getStatus() == PaymentStatus.EXPIRED) {
    throw new PaymentExpiredException(payment.getStatus(), payment.getExpiresAt());
}
if (payment.getStatus() != PaymentStatus.READY) {
    throw new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
}`,
    externalCall: "없음", repositoryAction: "없음", error: null,
    evidence: "PaymentCompletionTransactionService.java:35-43",
    stateChanges: {},
    branches: [
      { label: "PAID였다면", note: "멱등 반환, Port 미호출 (→ '중복 요청' 시나리오 참고)" },
      { label: "EXPIRED였다면", note: "즉시 409 거절 (→ '내부 만료' 시나리오 참고)" }
    ],
    explanation: {
      whatRuns: ["락 보유 상태의 최신 status를 세 갈래로 확인한다: 이미 PAID면 멱등 반환, EXPIRED면 즉시 거절, READY가 아닌 나머지(FAILED 등)면 거절."],
      whatChanged: ["없음. 이 시나리오는 READY이므로 그대로 통과한다."],
      safeguards: ["이 게이트가 중복 요청·경합 상황에서 두 번째 요청이 예약을 다시 확정시키지 않도록 막는 핵심 지점이다."],
      whySequence: ["만료 재검증(다음 단계)보다 먼저 상태 자체를 확인해야 이미 끝난 요청을 값싸게 종료할 수 있다."],
      ifFails: ["PAID면 200 멱등 반환, EXPIRED면 409 PAYMENT_EXPIRED, 그 외 상태면 409 PAYMENT_VERIFICATION_FAILED."],
      fact: []
    }
  });

  steps.push({
    id: "expiry-recheck",
    layer: "Domain",
    title: "만료 재검증",
    filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
    className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
    code:
`Instant now = clock.instant();
if (!payment.getExpiresAt().isAfter(now)) {
    throw new PaymentExpiredException(payment.getStatus(), payment.getExpiresAt());
}`,
    externalCall: "없음", repositoryAction: "없음", error: null,
    evidence: "PaymentCompletionTransactionService.java:45-48",
    stateChanges: {},
    explanation: {
      whatRuns: ["락 대기 중에도 시간이 흐를 수 있으므로, 락을 획득한 뒤의 현재 시각으로 expiresAt을 다시 비교한다."],
      whatChanged: ["없음. 이 시나리오는 만료 전이므로 통과한다."],
      safeguards: ["status==READY만으로는 만료 여부를 보장할 수 없어(만료 배치가 아직 안 돌았을 수 있음) 이 재검증이 필요하다."],
      whySequence: ["PAID 전이 직전 마지막 방어선이다."],
      ifFails: ["PaymentExpiredException(409 PAYMENT_EXPIRED) — 만료 시나리오에서 별도로 다룬다."],
      fact: []
    }
  });

  steps.push({
    id: "ready-to-paid",
    layer: "Domain",
    title: "READY → PAID 전이",
    filePath: "src/main/java/com/bobfull/payment/entity/Payment.java",
    className: "Payment", methodName: "complete", codeType: "actual",
    code:
`public void complete(Instant paidAt) {
    if (status != PaymentStatus.READY) {
        throw new IllegalStateException("READY Payment만 완료할 수 있습니다.");
    }
    status = PaymentStatus.PAID;
    this.paidAt = paidAt;
}`,
    externalCall: "없음", repositoryAction: "없음(아직 flush 전, 트랜잭션 내 메모리 상태 변경)", error: null,
    evidence: "Payment.java:196-202 / 테스트: PaymentCompletionTransactionServiceTest.잠금_획득후_READY_Payment을_완료하고_Port_결과를_응답과_엔티티에_연결한다",
    stateChanges: { paymentStatus: "READY → PAID", paidAt: "clock.instant() 값으로 기록" },
    explanation: {
      whatRuns: ["Payment 엔티티가 스스로 READY 불변식을 재확인하고 PAID로 전이하며 paidAt을 기록한다."],
      whatChanged: ["paymentStatus: READY → PAID, paidAt: null → 현재 시각."],
      safeguards: ["엔티티 내부에서 다시 status!=READY 방어 가드를 두어, 이 메서드가 호출 순서를 어긴 채 실행되는 것을 막는다."],
      whySequence: ["예약 확정(다음 단계)보다 먼저 결제 상태를 PAID로 만들어, 이후 예약 확정 실패 시 함께 롤백될 하나의 트랜잭션 단위를 구성한다."],
      ifFails: ["status가 READY가 아니면 IllegalStateException(정상 흐름에서는 도달 불가한 방어 코드)."],
      fact: []
    }
  });

  if (isCreate) {
    steps.push({
      id: "reservation-branch",
      layer: "Repository",
      title: "CREATE 분기: 새 Reservation 생성",
      filePath: "src/main/java/com/bobfull/reservation/service/ReservationConfirmationService.java",
      className: "ReservationConfirmationService", methodName: "confirm", codeType: "actual",
      code:
`Reservation reservation = (purpose == PaymentPurpose.CREATE)
        ? reservationRepository.save(Reservation.create(timeSlotId, memberId))
        : findReservationWithLockOrThrow(reservationId);`,
      externalCall: "없음",
      repositoryAction: "ReservationRepository.save(Reservation.create(...)) — CREATE는 새 Reservation INSERT",
      error: null,
      evidence: "ReservationConfirmationService.java:55-57 / 테스트: PaymentReservationConfirmationTransactionIntegrationTest.CREATE_완료는_Payment_PAID와_Reservation_최초_Participant를_하나의_트랜잭션으로_저장한다",
      stateChanges: { reservationStatus: "RECRUITING (신규)" },
      branches: [{ label: "JOIN이었다면", note: "기존 Reservation을 비관적 락으로 조회(findWithLockById)" }],
      explanation: {
        whatRuns: ["purpose==CREATE이므로 새 Reservation을 만들어 저장한다. JOIN이었다면 기존 Reservation을 비관적 락으로 조회했을 것이다."],
        whatChanged: ["reservationStatus: - → RECRUITING(생성 직후 기본값)."],
        safeguards: ["이 메서드는 @Transactional(propagation = MANDATORY)이라 결제 완료 트랜잭션 밖에서 단독 호출되면 즉시 실패한다(부분 성공 방지)."],
        whySequence: ["Payment가 이미 PAID로 전이된 같은 트랜잭션 안에서 예약을 만들어야 두 상태가 항상 함께 커밋되거나 함께 롤백된다."],
        ifFails: ["저장 실패 시 예외가 트랜잭션 밖으로 전파되어 Payment PAID 전이까지 함께 롤백된다(Rollback 시나리오에서 다룸)."],
        fact: []
      }
    });
  } else {
    steps.push({
      id: "reservation-branch",
      layer: "Repository",
      title: "JOIN 분기: 기존 Reservation 락 조회",
      filePath: "src/main/java/com/bobfull/reservation/service/ReservationConfirmationService.java",
      className: "ReservationConfirmationService", methodName: "findReservationWithLockOrThrow", codeType: "actual",
      code: `private Reservation findReservationWithLockOrThrow(Long reservationId) {\n    return reservationRepository.findWithLockById(reservationId)\n            .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));\n}`,
      externalCall: "없음",
      repositoryAction: "ReservationRepository.findWithLockById(reservationId) — @Lock(PESSIMISTIC_WRITE), 예상 SQL: SELECT ... FOR UPDATE",
      error: null,
      evidence: "ReservationConfirmationService.java:90-93 / 테스트: PaymentReservationConfirmationTransactionIntegrationTest.JOIN_완료는_기존_Reservation에_Participant_한_건만_추가하고_확정_기준이면_CONFIRMED_OPEN으로_전이한다",
      stateChanges: { reservationStatus: "RECRUITING (기존)", reservationLock: "보유" },
      branches: [{ label: "CREATE였다면", note: "새 Reservation을 생성(Reservation.create + save)" }],
      explanation: {
        whatRuns: ["JOIN이므로 이미 존재하는 Reservation을 비관적 락으로 조회한다. 없으면 RESOURCE_NOT_FOUND."],
        whatChanged: ["reservationLock: 없음 → 보유."],
        safeguards: ["같은 Reservation에 대한 동시 JOIN 완료 요청이 인원 집계를 안전하게 순서대로 반영하도록 락을 건다."],
        whySequence: ["참여자 추가(다음 단계) 전에 대상 Reservation을 잠가야 정원 집계가 최신 상태를 반영한다."],
        ifFails: ["대상 Reservation이 없으면 RESOURCE_NOT_FOUND — 이 경계 자체를 직접 검증하는 전용 테스트는 확인되지 않았다."],
        fact: []
      }
    });
  }

  steps.push({
    id: "participant-create",
    layer: "Repository",
    title: "ReservationParticipant 생성",
    filePath: "src/main/java/com/bobfull/reservation/service/ReservationConfirmationService.java",
    className: "ReservationConfirmationService", methodName: "confirm", codeType: "actual",
    code: `ReservationParticipant participant = reservationParticipantRepository.save(\n        ReservationParticipant.create(reservation.getId(), memberId, partySize));`,
    externalCall: "없음", repositoryAction: "ReservationParticipantRepository.save(...) — INSERT", error: null,
    evidence: "ReservationConfirmationService.java:59-60",
    stateChanges: { participantCreated: true, currentParticipants: `${initialParticipants} → ${finalParticipants}` },
    explanation: {
      whatRuns: ["결제한 회원과 partySize로 ReservationParticipant를 하나 생성한다. CREATE/JOIN 모두 이 한 줄을 공유한다."],
      whatChanged: [`currentParticipants: ${initialParticipants}명 → ${finalParticipants}명.`],
      safeguards: ["멱등성 테스트(PaymentCompletionIdempotencyIntegrationTest)가 같은 Payment에 대해 이 저장이 두 번 일어나지 않음을 보장한다(앞선 상태 게이트 덕분)."],
      whySequence: ["Reservation 확보(락/생성) 이후에 참여자를 추가해야 정원 집계가 일관된다."],
      ifFails: ["제약 위반(예: 잘못된 참여자 데이터) 시 예외가 트랜잭션 전체를 롤백시킨다(Rollback 시나리오에서 다룸)."],
      fact: []
    }
  });

  steps.push({
    id: "status-update",
    layer: "Domain",
    title: "예약 상태·모집 상태 갱신",
    filePath: "src/main/java/com/bobfull/reservation/service/ReservationConfirmationService.java",
    className: "ReservationConfirmationService", methodName: "updateReservationStatus", codeType: "actual",
    code:
`int tableCapacity = tableCapacityOf(timeSlotId);
int currentParticipantCount = reservationParticipantRepository.sumPartySize(reservation.getId(), ParticipationStatus.RESERVED);
if (currentParticipantCount >= confirmationThreshold(tableCapacity)) {
    reservation.confirm();
}
if (currentParticipantCount >= tableCapacity) {
    reservation.closeRecruitment();
}`,
    externalCall: "없음", repositoryAction: "ReservationParticipantRepository.sumPartySize(...) — 집계 조회", error: null,
    evidence: `ReservationConfirmationService.java:66-80 / confirmationThreshold: capacity==2면 2명, 그 외 capacity-1명 (이번 시나리오: capacity=${capacity} → 기준 ${threshold}명)`,
    stateChanges: {
      reservationStatus: `${finalParticipants >= threshold ? "RECRUITING → CONFIRMED" : "RECRUITING 유지"}`,
      recruitmentStatus: `${finalParticipants >= capacity ? "OPEN → CLOSED" : "OPEN 유지"}`
    },
    explanation: {
      whatRuns: [`현재 참여 인원(${finalParticipants}명)을 정원(${capacity}명) 및 확정 기준(${threshold}명)과 비교해 확정·모집마감 여부를 정한다.`],
      whatChanged: [
        finalParticipants >= threshold ? "reservationStatus: RECRUITING → CONFIRMED" : "reservationStatus 변경 없음",
        finalParticipants >= capacity ? "recruitmentStatus: OPEN → CLOSED" : "recruitmentStatus 변경 없음(추가 참여 가능)"
      ],
      safeguards: ["confirm()/closeRecruitment()는 Reservation 엔티티 내부 메서드로, 이미 CONFIRMED인 예약을 다시 RECRUITING으로 되돌리지 않는다."],
      whySequence: ["참여자 저장 직후 최신 인원으로 계산해야 정확한 정원 판정이 나온다."],
      ifFails: ["이 계산 자체는 예외를 던지지 않는다(TimeSlot/SharedTable 조회 실패 시에만 RESOURCE_NOT_FOUND)."],
      fact: []
    }
  });

  steps.push({
    id: "attach-confirmation",
    layer: "Domain",
    title: "Payment에 reservationId·participationId 연결",
    filePath: "src/main/java/com/bobfull/payment/entity/Payment.java",
    className: "Payment", methodName: "attachReservationConfirmation", codeType: "actual",
    code:
`public void attachReservationConfirmation(Long reservationId, Long reservationParticipantId) {
    if (reservationId == null || reservationParticipantId == null) {
        throw new IllegalArgumentException("예약과 참여자 식별자는 필수입니다.");
    }
    this.reservationId = reservationId;
    this.reservationParticipantId = reservationParticipantId;
}`,
    externalCall: "없음", repositoryAction: "없음(메모리 상태, 트랜잭션 커밋 시 함께 flush)", error: null,
    evidence: "Payment.java:220-226, 호출: PaymentCompletionTransactionService.java:51",
    stateChanges: { reservationId: "null → 실제 값", participationId: "null → 실제 값" },
    explanation: {
      whatRuns: ["예약 확정 결과(reservationId, participantId)를 Payment 엔티티에 되돌려 기록한다."],
      whatChanged: ["reservationId, participationId가 null에서 실제 값으로 채워진다."],
      safeguards: ["둘 중 하나라도 null이면 IllegalArgumentException — Port 구현체가 잘못된 결과를 반환하는 것을 막는 방어 코드."],
      whySequence: ["Commit 직전 마지막으로 Payment와 Reservation을 서로 연결한다."],
      ifFails: ["여기서 예외가 나면 트랜잭션 전체가 롤백된다(Rollback 시나리오 참고: 결과 ID 연결 실패 케이스)."],
      fact: []
    }
  });

  steps.push({
    id: "commit",
    layer: "Commit",
    title: "트랜잭션 커밋",
    filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
    className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
    code: `return new PaymentCompletionResult(payment, result.reservationId(), result.participationId());\n// 메서드가 예외 없이 반환하면 Spring AOP가 트랜잭션을 커밋한다.`,
    externalCall: "없음", repositoryAction: "Payment/Reservation/ReservationParticipant 변경 사항 flush 및 커밋", error: null,
    evidence: "PaymentCompletionTransactionService.java:52-53 (선언적 트랜잭션, 명시적 커밋 호출 코드 없음)",
    stateChanges: { transactionState: "시작됨 → 커밋됨" },
    explanation: {
      whatRuns: ["메서드가 정상적으로 반환되어 Spring 트랜잭션 AOP가 커밋을 수행한다."],
      whatChanged: ["transactionState: 시작됨 → 커밋됨."],
      safeguards: ["여기까지 예외가 없어야만 커밋된다 — 도중 예외는 전부 롤백으로 이어진다."],
      whySequence: ["모든 상태 변경이 끝난 뒤 한 번에 커밋해 부분 반영을 방지한다."],
      ifFails: ["해당 없음(이 단계 자체가 '성공 경로'다)."],
      fact: ["코드에는 명시적인 commit() 호출이 없다 — @Transactional AOP 프록시가 처리하는 선언적 트랜잭션이다."]
    }
  });

  steps.push({
    id: "lock-release",
    layer: "Lock",
    title: "락 해제",
    filePath: "src/main/java/com/bobfull/payment/repository/PaymentRepository.java",
    className: "PaymentRepository", methodName: "findWithLockByPaymentId", codeType: "framework", code: null,
    externalCall: "없음", repositoryAction: "없음", error: null,
    evidence: "코드에 별도 unlock 호출은 없다 — 트랜잭션 커밋과 동시에 DB가 행 락을 해제한다(JPA/DB 표준 동작).",
    stateChanges: { paymentLock: "보유 → 해제", reservationLock: isCreate ? undefined : "보유 → 해제" },
    explanation: {
      whatRuns: ["트랜잭션 커밋과 함께 DB가 자동으로 행 잠금을 해제한다."],
      whatChanged: ["paymentLock: 보유 → 해제" + (isCreate ? "" : ", reservationLock: 보유 → 해제")],
      safeguards: ["대기 중이던 다른 요청(동일 Payment에 대한 중복 완료 요청 등)이 이 시점부터 락을 획득할 수 있다."],
      whySequence: ["커밋 이후에만 락이 풀려야 커밋 전 다른 트랜잭션이 중간 상태를 보지 않는다."],
      ifFails: ["해당 없음."],
      fact: []
    }
  });

  steps.push({
    id: "http-response",
    layer: "Response",
    title: "HTTP 200 응답",
    filePath: "src/main/java/com/bobfull/payment/dto/PaymentCompletionResponse.java",
    className: "PaymentCompletionResponse", methodName: "from", codeType: "actual",
    code:
`public static PaymentCompletionResponse from(Payment payment, Long reservationId, Long participationId) {
    if (reservationId == null || participationId == null) {
        throw new IllegalArgumentException("완료 응답의 예약과 참여자 식별자는 필수입니다.");
    }
    return new PaymentCompletionResponse(payment.getPaymentId(), payment.getStatus(), reservationId, participationId);
}`,
    externalCall: "없음", repositoryAction: "없음", error: null,
    evidence: "PaymentCompletionResponse.java:6-12, ApiResponse.success (ApiResponse.java:25-27)",
    httpRequest: null,
    httpResponse:
`200 OK
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "paymentId": "...",
    "paymentStatus": "PAID",
    "reservationId": <실제 값>,
    "participationId": <실제 값>
  }
}`,
    stateChanges: {},
    explanation: {
      whatRuns: ["결과를 PaymentCompletionResponse로 감싸 ApiResponse.success로 응답한다."],
      whatChanged: ["없음(응답 직렬화 단계)."],
      safeguards: ["ApiResponse는 성공 시 code 필드를 생략(@JsonInclude(NON_NULL))한다."],
      whySequence: ["Flow Lab의 마지막 단계 — 클라이언트가 최종적으로 받는 응답."],
      ifFails: ["해당 없음."],
      fact: []
    }
  });

  return {
    steps,
    initialState: Object.assign(baseInitialState(), {
      capacity,
      currentParticipants: initialParticipants,
      reservationStatus: isCreate ? "-" : "RECRUITING",
      recruitmentStatus: isCreate ? "-" : "OPEN"
    }),
    groups: makeGroups(steps, [
      { title: "결제 완료 요청 접수", count: 4 },
      { title: "PortOne 결제 검증", count: 2 },
      { title: "트랜잭션 시작", count: 1 },
      { title: "Payment 락과 재검증", count: 4 },
      { title: "READY → PAID", count: 1 },
      { title: "Reservation·Participant 확정", count: 4 },
      { title: "Commit·락 해제", count: 2 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["paymentStatus", "transactionState", "paymentLock", "reservationStatus", "participantCreated"]
  };
}

/* ------------------------------------------------------------------ */
/* Ch4-B. 중복 결제 완료 요청 (락 이전 / 락 이후 변형)                          */
/* ------------------------------------------------------------------ */

function duplicatePreLockSteps() {
  const steps = [
    {
      id: "dup-pre-http", layer: "HTTP", title: "두 번째 완료 요청 도착 (이미 PAID)",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: "POST /api/payments/{paymentId}/complete (같은 paymentId, 이미 처리 완료됨)",
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "시나리오 전제: 이 Payment는 이전 요청에서 이미 PAID로 전이되었다.",
      stateChanges: {},
      explanation: {
        whatRuns: ["동일한 결제를 사용자가 새로고침·재시도 등으로 다시 완료 요청한 상황."],
        whatChanged: [], safeguards: [], whySequence: [],
        ifFails: [], fact: ["시작 상태가 이미 PaymentStatus.PAID라는 점이 다른 시나리오와의 유일한 차이다."]
      }
    },
    {
      id: "dup-pre-lookup", layer: "Service", title: "Payment 사전 조회 → 이미 PAID",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionService.java",
      className: "PaymentCompletionService", methodName: "complete", codeType: "actual",
      code:
`Payment payment = paymentRepository.findByPaymentId(paymentId)
        .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
if (!payment.isOwnedBy(memberId)) throw new CustomException(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
return completeVerified(paymentId, payment, memberId);`,
      externalCall: "없음", repositoryAction: "PaymentRepository.findByPaymentId(paymentId)", error: null,
      evidence: "PaymentCompletionService.java:32-36",
      stateChanges: {},
      explanation: {
        whatRuns: ["락 없이 조회한 Payment의 status가 이미 PAID다."],
        whatChanged: [], safeguards: ["소유권 검증은 이번에도 통과(같은 사용자)."], whySequence: [],
        ifFails: [], fact: []
      }
    },
    {
      id: "dup-pre-idempotent", layer: "Domain", title: "PAID 멱등 반환 — PortOne·트랜잭션 호출 없음",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionService.java",
      className: "PaymentCompletionService", methodName: "completeVerified", codeType: "actual",
      code:
`private PaymentCompletionTransactionService.PaymentCompletionResult completeVerified(
        String paymentId, Payment payment, Long memberId) {
    if (payment.getStatus() == PaymentStatus.PAID) {
        return new PaymentCompletionTransactionService.PaymentCompletionResult(
                payment, payment.getReservationId(), payment.getReservationParticipantId());
    }
    // ...`,
      externalCall: "호출 안 함", repositoryAction: "없음", error: null,
      evidence: "PaymentCompletionService.java:55-56 / 테스트: PaymentCompletionServiceTest.이미_완료된_Payment은_PortOne_재조회와_예약확정_트랜잭션을_수행하지_않는다 (verifyNoInteractions(portOnePaymentReader, transactionService))",
      stateChanges: {},
      branches: [{ label: "아직 READY였다면", note: "PortOne 재조회부터 진행 (→ '정상 완료' 시나리오 참고)" }],
      explanation: {
        whatRuns: ["status==PAID를 확인하는 즉시 기존 reservationId·participationId로 결과를 만들어 반환한다."],
        whatChanged: ["없음 — 이미 PAID인 상태를 그대로 재사용."],
        safeguards: ["이 단축 경로 덕분에 PortOne 재조회도, 락 트랜잭션 진입도 발생하지 않는다 — 외부 API 비용과 락 경합을 모두 피한다."],
        whySequence: ["가장 값싼 상태 확인을 가장 먼저 배치해 중복 요청을 조기에 종료한다."],
        ifFails: ["해당 없음(성공 경로)."],
        fact: []
      }
    },
    {
      id: "dup-pre-response", layer: "Response", title: "HTTP 200 (기존 결과 그대로)",
      filePath: "src/main/java/com/bobfull/payment/dto/PaymentCompletionResponse.java",
      className: "PaymentCompletionResponse", methodName: "from", codeType: "actual",
      code: `return new PaymentCompletionResponse(payment.getPaymentId(), payment.getStatus(), reservationId, participationId);`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "PaymentCompletionResponse.java:11",
      httpResponse: `200 OK\n{ "success": true, "data": { "paymentStatus": "PAID", "reservationId": <기존 값>, "participationId": <기존 값> } }`,
      stateChanges: {},
      explanation: {
        whatRuns: ["처음 완료됐을 때와 동일한 reservationId·participationId를 그대로 응답한다."],
        whatChanged: [], safeguards: ["클라이언트 관점에서 재시도는 안전하다(멱등)."], whySequence: [],
        ifFails: [], fact: []
      }
    }
  ];
  return {
    steps,
    initialState: Object.assign(baseInitialState(), {
      externalPaymentStatus: "PAID (이전 요청에서 이미 조회됨)",
      paymentStatus: "PAID",
      paidAt: "이전 요청에서 기록된 값",
      reservationId: "이전 요청에서 연결된 값",
      participationId: "이전 요청에서 연결된 값",
      reservationStatus: "CONFIRMED", recruitmentStatus: "OPEN",
      participantCreated: true, currentParticipants: 3, capacity: 4,
      transactionState: "없음(이번 요청은 트랜잭션을 열지 않음)"
    }),
    groups: makeGroups(steps, [
      { title: "중복 요청 도착·사전 조회", count: 2 },
      { title: "PAID 멱등 반환", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["paymentStatus", "participantCreated", "reservationStatus"]
  };
}

function duplicatePostLockSteps() {
  const steps = [
    {
      id: "dup-post-http", layer: "HTTP", title: "API 요청과 Webhook이 거의 동시에 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: "요청 A: POST /api/payments/{id}/complete (사용자 API)\n요청 B: POST /api/webhooks/portone (PortOne Webhook)",
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "테스트: PaymentCompletionIdempotencyIntegrationTest.완료_API와_웹훅이_동시에_같은_Payment을_처리해도_Reservation과_Participant는_한번만_생성된다 (CountDownLatch로 두 스레드를 실제로 동시 진입시킴)",
      stateChanges: {},
      explanation: {
        whatRuns: ["같은 Payment(READY)에 대해 사용자의 완료 API 호출과 PortOne의 결제완료 Webhook이 거의 동시에 들어온 상황."],
        whatChanged: [], safeguards: [], whySequence: [],
        ifFails: [], fact: ["두 경로 모두 최종적으로 같은 PaymentCompletionTransactionService.complete 로직을 공유한다."]
      }
    },
    {
      id: "dup-post-both-portone", layer: "External Port", title: "두 요청 모두 PortOne 조회까지 병렬 진행",
      filePath: "src/main/java/com/bobfull/payment/adapter/PortOneSdkPaymentReader.java",
      className: "PortOneSdkPaymentReader", methodName: "read", codeType: "actual",
      code: `Payment payment = portOneClient.getPayment().getPayment(paymentId).join();\n// 두 요청 모두 이 시점에는 아직 트랜잭션·락을 잡지 않은 상태다.`,
      externalCall: "PortOne SDK 조회 — 두 요청 각각 1회, 모두 PAID 응답",
      repositoryAction: "없음", error: null,
      evidence: "PortOneSdkPaymentReader.java:19-27, 테스트 상 CountDownLatch(2)로 두 호출이 동시에 도착함을 강제로 확인",
      stateChanges: { externalPaymentStatus: "PAID (두 요청 모두 조회 완료)" },
      explanation: {
        whatRuns: ["트랜잭션 밖이라는 특성 때문에 두 요청이 동시에 PortOne을 조회할 수 있다."],
        whatChanged: [], safeguards: ["이 지점까지는 아직 아무 것도 직렬화되지 않는다 — 실제 직렬화는 락에서 시작된다."],
        whySequence: ["외부 호출은 굳이 직렬화할 필요가 없어 락 밖에 둔다."],
        ifFails: [], fact: []
      }
    },
    {
      id: "dup-post-lock-a", layer: "Lock", title: "요청 A가 먼저 Payment 행 락을 선점",
      filePath: "src/main/java/com/bobfull/payment/repository/PaymentRepository.java",
      className: "PaymentRepository", methodName: "findWithLockByPaymentId", codeType: "actual",
      code: `@Lock(LockModeType.PESSIMISTIC_WRITE)\nOptional<Payment> findWithLockByPaymentId(String paymentId);`,
      externalCall: "없음", repositoryAction: "요청 A: SELECT ... FOR UPDATE 성공. 요청 B: 같은 행 락 대기(Blocked)",
      error: null, evidence: "PaymentRepository.java:28-29",
      stateChanges: { paymentLock: "요청 A가 보유, 요청 B는 대기", transactionState: "요청 A 시작됨" },
      explanation: {
        whatRuns: ["둘 중 먼저 트랜잭션에 진입한 요청(여기서는 A)이 행 락을 얻고, 나머지(B)는 대기한다."],
        whatChanged: ["paymentLock 상태가 A/B로 분리된다."],
        safeguards: ["이 락이 두 요청의 상태 전이가 서로 겹치지 않도록 만드는 핵심 장치다."],
        whySequence: ["락 획득 순서가 이후 '누가 실제로 PAID 전이를 수행하는가'를 결정한다."],
        ifFails: [], fact: []
      }
    },
    {
      id: "dup-post-a-completes", layer: "Domain", title: "요청 A: 상태 게이트 통과 후 PAID 전이 및 예약 확정",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
      className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
      code: `payment.complete(now);\nReservationConfirmationResult result = reservationConfirmationPort.confirm(payment);\npayment.attachReservationConfirmation(result.reservationId(), result.participationId());`,
      externalCall: "없음", repositoryAction: "Reservation/ReservationParticipant 저장(요청 A만 수행)", error: null,
      evidence: "PaymentCompletionTransactionService.java:49-51",
      stateChanges: { paymentStatus: "READY → PAID (요청 A가 수행)", participantCreated: true },
      explanation: {
        whatRuns: ["요청 A는 status==READY를 확인하고 정상적으로 PAID 전이 및 예약 확정을 1회 수행한다."],
        whatChanged: ["paymentStatus: READY → PAID(요청 A 기준)."],
        safeguards: [], whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "dup-post-a-commit", layer: "Commit", title: "요청 A 커밋, 락 해제",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
      className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
      code: `return new PaymentCompletionResult(payment, result.reservationId(), result.participationId());\n// 커밋과 동시에 대기 중이던 요청 B가 락을 획득할 수 있게 된다.`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "PaymentCompletionTransactionService.java:52",
      stateChanges: { transactionState: "요청 A 커밋됨", paymentLock: "요청 A 해제 → 요청 B가 획득 가능" },
      explanation: { whatRuns: ["요청 A의 변경이 커밋되고 락이 풀린다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "dup-post-b-lock", layer: "Lock", title: "대기하던 요청 B가 락 획득 → 최신 상태 재조회",
      filePath: "src/main/java/com/bobfull/payment/repository/PaymentRepository.java",
      className: "PaymentRepository", methodName: "findWithLockByPaymentId", codeType: "actual",
      code: `@Lock(LockModeType.PESSIMISTIC_WRITE)\nOptional<Payment> findWithLockByPaymentId(String paymentId);\n// 요청 B가 락을 얻은 시점에는 요청 A의 커밋이 이미 반영되어 있다.`,
      externalCall: "없음", repositoryAction: "요청 B: SELECT ... FOR UPDATE 성공, status는 이미 PAID", error: null,
      evidence: "PaymentRepository.java:28-29",
      stateChanges: { paymentLock: "요청 B가 보유" },
      explanation: {
        whatRuns: ["요청 B가 락을 얻고 Payment를 다시 읽으면, 이미 요청 A가 커밋한 PAID 상태가 보인다."],
        whatChanged: [], safeguards: ["같은 행에 대한 락이므로 커밋되지 않은 중간 상태를 볼 수 없다."], whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "dup-post-b-idempotent", layer: "Domain", title: "요청 B: PAID 멱등 확인 → 기존 결과 반환, Port 미호출",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
      className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
      code:
`if (payment.getStatus() == PaymentStatus.PAID) {
    return new PaymentCompletionResult(payment, payment.getReservationId(), payment.getReservationParticipantId());
}`,
      externalCall: "호출 안 함", repositoryAction: "없음", error: null,
      evidence: "PaymentCompletionTransactionService.java:35-37 / 테스트: PaymentCompletionIdempotencyIntegrationTest.완료_API와_웹훅이_동시에_같은_Payment을_처리해도_Reservation과_Participant는_한번만_생성된다 (reservationConfirmationPort.calls()==1로 검증)",
      stateChanges: {},
      explanation: {
        whatRuns: ["요청 B는 이미 PAID임을 확인하고 예약 확정을 다시 호출하지 않는다."],
        whatChanged: ["없음 — Reservation/Participant는 요청 A가 만든 것 그대로 1건씩만 존재."],
        safeguards: ["ReservationConfirmationPort.confirm 호출 횟수가 정확히 1회임을 통합 테스트가 직접 검증한다."],
        whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "dup-post-response", layer: "Response", title: "두 요청 모두 HTTP 200, 동일한 결과 반환",
      filePath: "src/main/java/com/bobfull/payment/dto/PaymentCompletionResponse.java",
      className: "PaymentCompletionResponse", methodName: "from", codeType: "actual",
      code: `return new PaymentCompletionResponse(payment.getPaymentId(), payment.getStatus(), reservationId, participationId);`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "PaymentCompletionResponse.java:11",
      httpResponse: `요청 A, 요청 B 모두 200 OK, 동일한 reservationId/participationId`,
      stateChanges: {},
      explanation: { whatRuns: ["두 요청 모두 같은 최종 결과를 사용자/PortOne에게 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: Object.assign(baseInitialState(), { capacity: 4, currentParticipants: 0 }),
    groups: makeGroups(steps, [
      { title: "두 요청 도착", count: 1 },
      { title: "PortOne 병렬 조회", count: 1 },
      { title: "요청 A 락·완료·커밋", count: 3 },
      { title: "요청 B 락·멱등 확인", count: 2 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["paymentStatus", "paymentLock", "transactionState", "participantCreated"]
  };
}

/* ------------------------------------------------------------------ */
/* Ch4-C. 내부 Payment 만료 (락 내부 재검증 경로)                             */
/* ------------------------------------------------------------------ */

function expiredSteps() {
  const steps = [
    {
      id: "exp-http", layer: "HTTP", title: "만료 임박 Payment에 대한 완료 요청",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: "POST /api/payments/{paymentId}/complete", httpResponse: null,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "시나리오 전제: Payment.status는 아직 READY이고(만료 배치가 처리하기 전), expiresAt은 이미 지났거나 락 대기 중 지나간다.",
      stateChanges: {},
      explanation: { whatRuns: ["결제 유효 시간(10분)이 지난 뒤 뒤늦게 도착한 완료 요청."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "exp-controller", layer: "Controller", title: "PaymentController.complete 진입",
      filePath: "src/main/java/com/bobfull/payment/controller/PaymentController.java",
      className: "PaymentController", methodName: "complete", codeType: "actual",
      code: `PaymentCompletionTransactionService.PaymentCompletionResult result =\n        paymentCompletionService.complete(paymentId, authMember.id());`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "PaymentController.java:29-30",
      stateChanges: {},
      explanation: { whatRuns: ["다른 시나리오와 동일한 Controller 진입."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "exp-lookup", layer: "Service", title: "Payment 사전 조회 → 아직 READY",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionService.java",
      className: "PaymentCompletionService", methodName: "complete", codeType: "actual",
      code: `Payment payment = paymentRepository.findByPaymentId(paymentId)\n        .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));\nif (!payment.isOwnedBy(memberId)) throw new CustomException(PaymentErrorCode.PAYMENT_ACCESS_DENIED);`,
      externalCall: "없음", repositoryAction: "PaymentRepository.findByPaymentId(paymentId)", error: null,
      evidence: "PaymentCompletionService.java:32-35",
      stateChanges: {},
      explanation: {
        whatRuns: ["만료 배치(별도 프로세스)가 아직 이 Payment를 EXPIRED로 바꾸지 않아 READY로 조회된다."],
        whatChanged: [], safeguards: [], whySequence: [], ifFails: [],
        fact: ["이 시나리오는 'READY 상태에서 락 안에서 만료가 발견되는 경로'만 다룬다. 이미 status=EXPIRED로 선점된 경로는 근거가 제한적(env-gated 통합 테스트만)이라 1차 범위에서 제외했다."]
      }
    },
    {
      id: "exp-portone", layer: "External Port", title: "PortOne 조회 → 외부는 정상 PAID",
      filePath: "src/main/java/com/bobfull/payment/adapter/PortOneSdkPaymentReader.java",
      className: "PortOneSdkPaymentReader", methodName: "read", codeType: "actual",
      code: `Payment payment = portOneClient.getPayment().getPayment(paymentId).join();\n// PaidPayment 응답: paid=true, 금액/통화 일치`,
      externalCall: "PortOne SDK 조회 — 결제 자체는 실제로 승인되어 PAID", repositoryAction: "없음", error: null,
      evidence: "PortOneSdkPaymentReader.java:19-27",
      stateChanges: { externalPaymentStatus: "PAID (조회됨)" },
      explanation: {
        whatRuns: ["실제 결제는 이미 승인되어 외부 상태는 PAID다 — 문제는 '내부' 만료 시각이다."],
        whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "exp-verify-pass", layer: "Service", title: "외부 검증 통과 (READY이므로 진행)",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionService.java",
      className: "PaymentCompletionService", methodName: "completeVerified", codeType: "actual",
      code: `if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.EXPIRED) {\n    throw new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);\n}\n// status==READY이므로 통과, 이후 completeAfterExternalPaid 호출`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "PaymentCompletionService.java:57-59, 66",
      stateChanges: {},
      explanation: { whatRuns: ["금액·통화·상태가 모두 일치하므로 트랜잭션 단계로 넘어간다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "exp-tx-lock", layer: "Lock", title: "트랜잭션 시작 + Payment 비관적 락 획득",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
      className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
      code: `@Transactional\npublic PaymentCompletionResult complete(String paymentId, Long memberId) {\n    Payment payment = paymentRepository.findWithLockByPaymentId(paymentId)\n            .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));`,
      externalCall: "없음", repositoryAction: "PaymentRepository.findWithLockByPaymentId — 예상 SQL: SELECT ... FOR UPDATE", error: null,
      evidence: "PaymentCompletionTransactionService.java:28-31",
      stateChanges: { transactionState: "시작됨", paymentLock: "보유" },
      explanation: {
        whatRuns: ["트랜잭션이 열리고 락을 얻는다. 락 대기 시간 동안 만료 시각이 지날 수 있다는 점이 이 시나리오의 핵심이다."],
        whatChanged: ["transactionState/paymentLock 갱신."], safeguards: [], whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "exp-status-gate", layer: "Domain", title: "상태 게이트 통과 (여전히 READY)",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
      className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
      code: `if (payment.getStatus() == PaymentStatus.PAID) { /* 해당 없음 */ }\nif (payment.getStatus() == PaymentStatus.EXPIRED) { /* 해당 없음: 아직 READY */ }\nif (payment.getStatus() != PaymentStatus.READY) { /* 해당 없음 */ }`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "PaymentCompletionTransactionService.java:35-43",
      stateChanges: {},
      branches: [{ label: "PAID였다면", note: "멱등 반환 (→ '중복 요청' 시나리오 참고)" }],
      explanation: { whatRuns: ["status는 여전히 READY라 이 게이트를 통과한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "exp-detected", layer: "Domain", title: "만료 재검증에서 만료 발견",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
      className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
      code:
`Instant now = clock.instant();
if (!payment.getExpiresAt().isAfter(now)) {
    throw new PaymentExpiredException(payment.getStatus(), payment.getExpiresAt());
}`,
      externalCall: "없음", repositoryAction: "없음",
      error: "PaymentExpiredException → PaymentErrorCode.PAYMENT_EXPIRED (HTTP 409)",
      evidence: "PaymentCompletionTransactionService.java:45-48 / 테스트: PaymentCompletionTransactionServiceTest.락_획득_대기중_만료된_Payment은_READY를_유지하고_Port를_호출하지_않는다",
      stateChanges: {},
      explanation: {
        whatRuns: ["락을 얻은 뒤의 현재 시각으로 다시 비교하면 expiresAt이 이미 지나 있다."],
        whatChanged: ["Payment 상태는 변경되지 않는다(READY 그대로 유지) — 이 시나리오의 핵심 사실."],
        safeguards: ["이 재검증이 없다면, status만 보고 만료된 결제를 그대로 완료 처리할 위험이 있다."],
        whySequence: ["PAID 전이(다음 단계여야 했을 코드) 이전에 배치돼 있어, 전이 자체가 실행되지 않는다."],
        ifFails: ["ReservationConfirmationPort는 호출되지 않는다(verifyNoInteractions로 검증됨)."],
        fact: ["이 테스트는 PaymentCompletionTransactionService를 직접 호출하는 유닛 테스트다. 1~7단계(HTTP~락 획득)는 PaymentCompletionService/PaymentCompletionTransactionService의 실제 호출 순서를 조합한 것이며, '외부 PAID 승인 후 이 지점에 도달'하는 전체 경로 자체를 하나의 통합 테스트로 검증한 것은 아니다."]
      }
    },
    {
      id: "exp-compensation-log", layer: "Service", title: "보상 필요 구조화 로그 기록",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionService.java",
      className: "PaymentCompletionService", methodName: "completeAfterExternalPaid", codeType: "actual",
      code:
`} catch (PaymentExpiredException exception) {
    log.error("event=PAYMENT_COMPENSATION_REQUIRED paymentId={} externalStatus={} internalStatus={} expiresAt={} reason={}",
            paymentId, "PAID", exception.getInternalStatus(), exception.getExpiresAt(), exception.getErrorCode().getCode());
    throw exception;
}`,
      externalCall: "없음", repositoryAction: "없음",
      error: "PaymentExpiredException을 다시 던짐(rethrow)",
      evidence: "PaymentCompletionService.java:69-77 / 테스트: PaymentWebhookCompensationLogTest.외부_PAID와_내부_만료가_갈리면_보상필요_구조화로그의_필수필드를_기록한다",
      stateChanges: {},
      explanation: {
        whatRuns: ["외부는 PAID인데 내부는 만료로 갈리는 이 경계를 error 레벨 구조화 로그로 남긴 뒤 예외를 그대로 재던진다."],
        whatChanged: ["없음(로그만 기록)."],
        safeguards: ["이 로그가 운영자가 사후에 수동 환불·보상 처리를 판단할 수 있는 유일한 단서다."],
        whySequence: ["예외를 삼키지 않고 재던져 HTTP 응답도 정확히 실패로 내려간다."],
        ifFails: [],
        fact: ["실제 자동 환불/보상 처리 코드는 존재하지 않는다 — 로그만 남기고 종료된다. 이는 미구현 상태이며 구현된 것처럼 표현하지 않는다."],
        futureNotes: ["외부 PAID·내부 만료 충돌에 대한 자동 보상(환불 트리거 등)은 2차 개선 후보다."]
      }
    },
    {
      id: "exp-rollback", layer: "Rollback", title: "트랜잭션 롤백",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
      className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
      code: `// PaymentExpiredException(RuntimeException)이 @Transactional 메서드 밖으로 전파되어\n// Spring이 이 트랜잭션을 롤백한다. Payment.complete(now)는 호출된 적이 없다.`,
      externalCall: "없음", repositoryAction: "변경된 것 없음(원래 READY 상태 그대로 유지)", error: null,
      evidence: "Spring @Transactional 기본 롤백 규칙(RuntimeException) — PaymentExpiredException은 CustomException(RuntimeException)의 서브타입",
      stateChanges: { paymentStatus: "READY 유지(전이 없었음)", transactionState: "시작됨 → 롤백됨" },
      explanation: {
        whatRuns: ["Payment.complete(now)가 아예 호출되지 않았으므로 되돌릴 상태 변경 자체가 없다 — '아무 일도 일어나지 않은 것처럼' 종료된다."],
        whatChanged: ["없음. Payment/Reservation/Participant 모두 원래 상태 그대로."],
        safeguards: ["예약 확정(ReservationConfirmationPort.confirm)도 호출되지 않아 좌석·인원 집계에 영향이 없다."],
        whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "exp-lock-release", layer: "Lock", title: "락 해제",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "트랜잭션 종료(커밋 또는 롤백)와 함께 DB가 행 락을 해제한다.",
      stateChanges: { paymentLock: "보유 → 해제" },
      explanation: { whatRuns: ["롤백도 트랜잭션 종료의 한 형태이므로 락이 해제된다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "exp-response", layer: "Response", title: "HTTP 409 PAYMENT_EXPIRED",
      filePath: "src/main/java/com/bobfull/common/exception/GlobalExceptionHandler.java",
      className: "GlobalExceptionHandler", methodName: "handleCustomException", codeType: "actual",
      code:
`@ExceptionHandler(CustomException.class)
public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
    BaseErrorCode errorCode = e.getErrorCode();
    return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.fail(errorCode));
}`,
      externalCall: "없음", repositoryAction: "없음", error: "PAYMENT_EXPIRED",
      evidence: "GlobalExceptionHandler.java:22-27, PaymentErrorCode.java:14 (PAYMENT_EXPIRED → HttpStatus.CONFLICT)",
      httpResponse: `409 CONFLICT\n{ "success": false, "message": "결제 가능 시간이 만료되었습니다.", "code": "PAYMENT_EXPIRED" }`,
      stateChanges: {},
      explanation: { whatRuns: ["PaymentExpiredException(CustomException)이 전역 예외 처리기에서 409로 변환된다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: Object.assign(baseInitialState(), {
      expiresAt: "2026-07-28T00:00:00Z (이미 지남)",
      capacity: 4, currentParticipants: 0
    }),
    groups: makeGroups(steps, [
      { title: "요청 접수", count: 3 },
      { title: "PortOne 검증", count: 2 },
      { title: "트랜잭션·락", count: 2 },
      { title: "만료 발견·보상 로그", count: 2 },
      { title: "Rollback", count: 2 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["paymentStatus", "transactionState", "paymentLock"]
  };
}

/* ------------------------------------------------------------------ */
/* Ch4-D. Reservation/Participant 저장 실패 Rollback                       */
/* ------------------------------------------------------------------ */

function rollbackSteps() {
  const steps = [
    {
      id: "rb-http", layer: "HTTP", title: "결제 완료 요청 (정상 흐름과 동일하게 시작)",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: "POST /api/payments/{paymentId}/complete", httpResponse: null,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "테스트: PaymentReservationConfirmationTransactionIntegrationTest.Participant_저장_실패는_Payment과_Reservation을_함께_롤백한다",
      stateChanges: {},
      explanation: { whatRuns: ["여기까지는 정상 결제 완료 시나리오와 동일하게 진행된다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "rb-verify", layer: "Service", title: "사전 조회·소유권·PortOne 검증 통과",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionService.java",
      className: "PaymentCompletionService", methodName: "completeVerified", codeType: "actual",
      code: `PortOnePaymentReader.PortOnePayment external = portOnePaymentReader.read(paymentId);\n// paymentId 일치, paid=true, 금액/통화 일치 → completeAfterExternalPaid 호출`,
      externalCall: "PortOne 조회 — 정상 PAID", repositoryAction: "없음", error: null,
      evidence: "PaymentCompletionService.java:60-66",
      stateChanges: { externalPaymentStatus: "PAID (조회됨)" },
      explanation: { whatRuns: ["결제 자체는 정상이다. 실패는 이후 내부 저장 단계에서 발생한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "rb-tx-lock", layer: "Lock", title: "트랜잭션 시작 + Payment 비관적 락 획득",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
      className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
      code: `@Transactional\npublic PaymentCompletionResult complete(String paymentId, Long memberId) {\n    Payment payment = paymentRepository.findWithLockByPaymentId(paymentId)\n            .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));`,
      externalCall: "없음", repositoryAction: "PaymentRepository.findWithLockByPaymentId — 예상 SQL: SELECT ... FOR UPDATE", error: null,
      evidence: "PaymentCompletionTransactionService.java:28-31",
      stateChanges: { transactionState: "시작됨", paymentLock: "보유" },
      explanation: { whatRuns: ["정상 흐름과 동일하게 락을 잡고 트랜잭션에 진입한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "rb-status-gate", layer: "Domain", title: "상태·만료 게이트 통과",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
      className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
      code: `if (payment.getStatus() != PaymentStatus.READY) { /* 해당 없음: READY */ }\nInstant now = clock.instant();\nif (!payment.getExpiresAt().isAfter(now)) { /* 해당 없음: 만료 전 */ }`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "PaymentCompletionTransactionService.java:41-48",
      stateChanges: {},
      branches: [{ label: "만료였다면", note: "PaymentExpiredException (→ '내부 만료' 시나리오 참고)" }],
      explanation: { whatRuns: ["READY이고 만료 전이므로 모두 통과한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "rb-ready-to-paid", layer: "Domain", title: "READY → PAID 전이 (트랜잭션 내부 메모리 상태)",
      filePath: "src/main/java/com/bobfull/payment/entity/Payment.java",
      className: "Payment", methodName: "complete", codeType: "actual",
      code: `status = PaymentStatus.PAID;\nthis.paidAt = paidAt;`,
      externalCall: "없음", repositoryAction: "없음(아직 flush 전)", error: null,
      evidence: "Payment.java:200-201",
      stateChanges: { paymentStatus: "READY → PAID (아직 커밋 전)" },
      explanation: {
        whatRuns: ["Payment 엔티티는 PAID로 바뀐다. 하지만 이 트랜잭션이 커밋되기 전까지는 최종 확정이 아니다."],
        whatChanged: ["paymentStatus가 메모리상 PAID로 바뀐다."],
        safeguards: [], whySequence: ["이후 예약 확정이 실패하면 이 변경도 함께 취소된다는 것이 이 시나리오의 핵심이다."],
        ifFails: [], fact: []
      }
    },
    {
      id: "rb-reservation-ok", layer: "Repository", title: "Reservation 생성은 정상적으로 저장됨",
      filePath: "src/main/java/com/bobfull/reservation/service/ReservationConfirmationService.java",
      className: "ReservationConfirmationService", methodName: "confirm", codeType: "actual",
      code: `Reservation reservation = (purpose == PaymentPurpose.CREATE)\n        ? reservationRepository.save(Reservation.create(timeSlotId, memberId))\n        : findReservationWithLockOrThrow(reservationId);`,
      externalCall: "없음", repositoryAction: "ReservationRepository.save(...) — 이 저장 자체는 성공한다", error: null,
      evidence: "ReservationConfirmationService.java:55-57",
      stateChanges: { reservationStatus: "RECRUITING (임시 저장됨, 아직 커밋 전)" },
      explanation: {
        whatRuns: ["CREATE 분기의 Reservation 저장 자체는 이 시점에는 성공한다."],
        whatChanged: ["reservationStatus가 임시로 RECRUITING이 된다."],
        safeguards: [], whySequence: ["바로 다음 단계인 Participant 저장에서 실패가 발생하므로, 이 성공도 결국 함께 롤백된다."],
        ifFails: [], fact: []
      }
    },
    {
      id: "rb-participant-fail", layer: "Repository", title: "Participant 저장 중 무결성 제약 위반 발생",
      filePath: "src/main/java/com/bobfull/reservation/service/ReservationConfirmationService.java",
      className: "ReservationConfirmationService", methodName: "confirm", codeType: "actual",
      code: `ReservationParticipant participant = reservationParticipantRepository.save(\n        ReservationParticipant.create(reservation.getId(), memberId, partySize));`,
      externalCall: "없음",
      repositoryAction: "ReservationParticipantRepository.save(...) 도중 DataIntegrityViolationException 발생",
      error: "DataIntegrityViolationException (CustomException이 아닌 순수 데이터 무결성 예외)",
      evidence:
        "ReservationConfirmationService.java:59-60 / 테스트: PaymentReservationConfirmationTransactionIntegrationTest.Participant_저장_실패는_Payment과_Reservation을_함께_롤백한다 " +
        "— 이 테스트는 ReservationConfirmationPort를 테스트 전용 구현으로 교체해 정상 저장 뒤 추가로 잘못된 참여자 레코드(FK null)를 저장하도록 강제해 위반을 재현한다. 실제 운영 데이터로는 이 정확한 위반이 흔하지 않지만, 트랜잭션 원자성 자체를 검증하기 위한 의도적 결함 주입이다.",
      stateChanges: {},
      branches: [{ label: "저장이 성공했다면", note: "예약 상태 갱신 → Commit (→ '정상 완료' 시나리오 참고)" }],
      explanation: {
        whatRuns: ["ReservationParticipant를 저장하는 실제 프로덕션 코드 라인에서(테스트는 이 지점 근처에 결함을 주입해) 무결성 제약 위반이 발생한다."],
        whatChanged: ["없음 — 예외가 발생한 순간 이후 코드는 실행되지 않는다."],
        safeguards: [],
        whySequence: [],
        ifFails: ["예외가 그대로 위로 전파된다 — ReservationConfirmationService는 이 예외를 잡지 않는다."],
        fact: ["이 정확한 실패는 테스트가 의도적으로 주입한 결함으로 재현된 것이며, 저장 대상 코드 라인(59-60행) 자체는 실제 프로덕션 코드다."]
      }
    },
    {
      id: "rb-rollback", layer: "Rollback", title: "트랜잭션 전체 롤백",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",
      className: "PaymentCompletionTransactionService", methodName: "complete", codeType: "actual",
      code:
`// DataIntegrityViolationException(RuntimeException)이 @Transactional 메서드 밖으로 전파되어
// Spring이 이 트랜잭션 전체를 롤백한다.
// ReservationConfirmationService는 @Transactional(propagation = MANDATORY)로
// 같은 트랜잭션에 강제로 참여하므로 별도 트랜잭션으로 분리되어 있지 않다.`,
      externalCall: "없음",
      repositoryAction: "Payment PAID 전이, Reservation 저장, Participant 저장이 모두 취소된다",
      error: null,
      evidence:
        "assertPaymentAndReservationRolledBack (PaymentReservationConfirmationTransactionIntegrationTest.java:170-177): " +
        "Payment.status==READY, reservationId/participantId==null, reservationRepository.count()==0, reservationParticipantRepository.count()==0",
      stateChanges: {
        paymentStatus: "PAID(임시) → READY (원상 복구)",
        reservationId: "임시 값 → null",
        participationId: "null 유지",
        reservationStatus: "RECRUITING(임시) → 저장되지 않음(0건)"
      },
      explanation: {
        whatRuns: ["Payment.complete()와 Reservation 저장까지 이미 실행됐지만, 하나의 물리 트랜잭션이므로 전부 되돌아간다."],
        whatChanged: ["Payment는 READY로 남고, Reservation·Participant는 DB에 전혀 존재하지 않는 상태(count==0)로 돌아간다."],
        safeguards: [
          "ReservationConfirmationService가 Propagation.MANDATORY라는 사실이 이 원자성의 근거다 — 별도 트랜잭션(REQUIRES_NEW)이 아니므로 부분 성공이 불가능하다.",
          "이 전파 속성 자체를 리플렉션으로 직접 검증하는 테스트도 별도로 존재한다(ReservationConfirmationService는_MANDATORY이고_REQUIRES_NEW를_사용하지_않는다)."
        ],
        whySequence: ["결제 확정과 예약 확정을 같은 트랜잭션으로 묶은 설계 의도가 바로 이 시나리오에서 드러난다 — 결제만 되고 예약이 안 되는 상태(또는 반대)를 원천적으로 방지한다."],
        ifFails: ["해당 없음(이 단계 자체가 실패 처리 경로)."],
        fact: []
      }
    },
    {
      id: "rb-lock-release", layer: "Lock", title: "락 해제 (Rollback과 함께)",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "트랜잭션 종료(이번에는 Rollback)와 함께 DB가 행 락을 해제한다.",
      stateChanges: { paymentLock: "보유 → 해제" },
      explanation: { whatRuns: ["커밋이든 롤백이든 트랜잭션이 끝나면 락은 해제된다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "rb-response", layer: "Response", title: "서버 오류 응답 (구체적 코드는 근거 기반 추론)",
      filePath: "src/main/java/com/bobfull/common/exception/GlobalExceptionHandler.java",
      className: "GlobalExceptionHandler", methodName: "handleException", codeType: "actual",
      code:
`@ExceptionHandler(Exception.class)
public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
    log.error("처리되지 않은 예외가 발생했습니다.", e);
    return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
            .body(ApiResponse.fail(CommonErrorCode.INTERNAL_SERVER_ERROR));
}`,
      externalCall: "없음", repositoryAction: "없음", error: "DataIntegrityViolationException (미분류 Exception)",
      evidence:
        "GlobalExceptionHandler.java:46-51 (CommonErrorCode.INTERNAL_SERVER_ERROR → HttpStatus.INTERNAL_SERVER_ERROR). " +
        "주의: 이 정확한 조합(Participant 저장 실패가 실제 HTTP 요청을 통해 500으로 응답되는 것)을 컨트롤러 엔드투엔드로 직접 검증하는 테스트는 확인되지 않았다. " +
        "테스트는 PaymentCompletionTransactionService.complete를 직접 호출해 DataIntegrityViolationException이 던져짐을 확인할 뿐이다. " +
        "이 단계의 HTTP 500은 GlobalExceptionHandler의 일반 Exception 처리기 코드로부터의 추론이다.",
      httpResponse: `500 INTERNAL SERVER ERROR (추론)\n{ "success": false, "message": "서버 오류가 발생했습니다.", "code": "INTERNAL_SERVER_ERROR" }`,
      stateChanges: {},
      explanation: {
        whatRuns: ["DataIntegrityViolationException은 CustomException이 아니므로 전용 ErrorCode 매핑이 없고, GlobalExceptionHandler의 catch-all Exception 처리기로 떨어진다."],
        whatChanged: [], safeguards: [],
        whySequence: [],
        ifFails: [],
        fact: [],
        futureNotes: ["이 경계를 컨트롤러 레벨 통합 테스트(WebTest)로 직접 검증하는 것은 후속 보강 후보다."]
      }
    }
  ];
  return {
    steps,
    initialState: Object.assign(baseInitialState(), { capacity: 4, currentParticipants: 0 }),
    groups: makeGroups(steps, [
      { title: "요청·검증", count: 2 },
      { title: "트랜잭션·락", count: 2 },
      { title: "PAID 전이", count: 1 },
      { title: "예약 저장 시도", count: 2 },
      { title: "Rollback", count: 2 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["paymentStatus", "reservationId", "reservationStatus", "transactionState"]
  };
}

/* ------------------------------------------------------------------ */
/* Ch1/Ch2 공용. 로그인 (사장님/일반 사용자 — 코드 경로는 완전히 동일)             */
/* ------------------------------------------------------------------ */

function loginSteps(roleKorean, roleValue, emailExample) {
  const steps = [
    {
      id: "login-http", layer: "HTTP", title: "로그인 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: `POST /api/auth/login\n{ "email": "${emailExample}", "password": "********" }`,
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SecurityConfig.java:77 — /api/auth/** 는 permitAll(), 인증 불필요",
      stateChanges: {},
      explanation: {
        whatRuns: [`${roleKorean}이 이메일·비밀번호로 로그인 요청을 보낸다(Flow Lab 내부 가상 테스트 데이터).`],
        whatChanged: [], safeguards: [], whySequence: [], ifFails: [],
        fact: ["로그인 API는 사장님/일반 사용자 구분 없이 완전히 동일한 단일 경로(POST /api/auth/login)를 사용한다."]
      }
    },
    {
      id: "login-controller", layer: "Controller", title: "AuthController.login 진입",
      filePath: "src/main/java/com/bobfull/auth/controller/AuthController.java",
      className: "AuthController", methodName: "login", codeType: "actual",
      code:
`@PostMapping("/login")
public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
    LoginResponse response = authService.login(request);
    return ResponseEntity.ok(ApiResponse.success(response));
}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "AuthController.java:40-44 (LoginRequest는 email/password @NotBlank 필드만 가짐)",
      stateChanges: {},
      explanation: {
        whatRuns: ["email/password @NotBlank 검증 후 AuthService.login으로 위임한다."],
        whatChanged: [], safeguards: ["요청 DTO에 역할(role) 필드가 없다 — 역할은 가입 시 DB에 고정된 값을 그대로 읽는다."],
        whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "login-member-lookup", layer: "Service", title: "Member 조회",
      filePath: "src/main/java/com/bobfull/auth/service/AuthService.java",
      className: "AuthService", methodName: "login", codeType: "actual",
      code:
`@Transactional(readOnly = true)
public LoginResponse login(LoginRequest request) {
    Member member = memberRepository.findByEmail(request.email())
            .orElseThrow(() -> new CustomException(MemberErrorCode.INVALID_CREDENTIALS));
    // ...`,
      externalCall: "없음", repositoryAction: "MemberRepository.findByEmail(email) — SELECT, MySQL", error: null,
      evidence: "AuthService.java:75-78, MemberRepository.java:15",
      stateChanges: { memberFound: `조회 전 → 존재함(${roleKorean})`, transactionState: "없음 → 읽기전용 진행중" },
      explanation: {
        whatRuns: ["이메일로 Member를 조회한다. 없으면 이메일 존재 여부를 노출하지 않기 위해 이후 비밀번호 불일치와 동일한 에러(INVALID_CREDENTIALS)를 던진다."],
        whatChanged: ["memberFound 갱신."],
        safeguards: ["이메일 없음과 비밀번호 불일치를 같은 ErrorCode/401로 통일해 계정 존재 여부를 추측하지 못하게 한다."],
        whySequence: [], ifFails: ["INVALID_CREDENTIALS(401) — 근거: AuthServiceTest.존재하지_않는_이메일로_로그인하면_INVALID_CREDENTIALS_예외가_발생한다"], fact: []
      }
    },
    {
      id: "login-password-check", layer: "Domain", title: "비밀번호 검증(BCrypt)",
      filePath: "src/main/java/com/bobfull/auth/service/AuthService.java",
      className: "AuthService", methodName: "login", codeType: "actual",
      code: `if (!passwordEncoder.matches(request.password(), member.getPasswordHash())) {\n    throw new CustomException(MemberErrorCode.INVALID_CREDENTIALS);\n}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "AuthService.java:80-82, SecurityConfig.java:28 (BCryptPasswordEncoder Bean) / 테스트: AuthServiceTest.비밀번호가_일치하지_않으면_INVALID_CREDENTIALS_예외가_발생한다",
      stateChanges: { passwordValid: "확인 전 → 일치" },
      explanation: {
        whatRuns: ["요청 비밀번호와 저장된 BCrypt 해시를 PasswordEncoder.matches로 비교한다."],
        whatChanged: ["passwordValid 갱신."], safeguards: [], whySequence: [],
        ifFails: ["불일치 시 INVALID_CREDENTIALS(401), 이메일 없음과 동일한 응답."], fact: []
      }
    },
    {
      id: "login-jwt-issue", layer: "Domain", title: "JWT 발급",
      filePath: "src/main/java/com/bobfull/common/security/JwtTokenProvider.java",
      className: "JwtTokenProvider", methodName: "createAccessToken", codeType: "actual",
      code:
`public String createAccessToken(Long memberId, MemberRole role) {
    Instant now = clock.instant();
    Instant expiration = now.plusSeconds(accessTokenExpirationSeconds);
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put(CLAIM_MEMBER_ID, memberId);
    claims.put(CLAIM_ROLE, role.name());
    claims.put(CLAIM_ISSUED_AT, now.getEpochSecond());
    claims.put(CLAIM_EXPIRATION, expiration.getEpochSecond());
    // HS256 서명 후 "header.payload.signature" 형태로 반환
}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "JwtTokenProvider.java:49-63 / 테스트: AuthServiceTest.로그인_성공시_AccessToken을_발급한다",
      stateChanges: { tokenIssued: "발급 전 → 발급됨", role: roleValue },
      explanation: {
        whatRuns: [`member.getRole()(=${roleValue}) 값을 role 클레임에 그대로 실어 JWT를 발급한다. 별도 JWT 라이브러리 없이 JDK Mac(HmacSHA256)으로 직접 서명한다.`],
        whatChanged: ["tokenIssued, role 갱신."],
        safeguards: ["로그인 로직 자체에는 역할 분기가 전혀 없다 — OWNER든 MEMBER든 이 메서드 하나를 그대로 공유한다."],
        whySequence: [],
        ifFails: [],
        fact: ["MemberRole enum 값은 정확히 MEMBER, OWNER, ADMIN이다(MemberRole.java:7-11). '사장님 로그인'을 별도로 검증하는 테스트는 없으며(로그인 코드가 역할 분기가 없으므로 설계상 당연), 이후 /api/owner/** 접근 시 JwtAuthenticationFilter가 이 role 클레임으로 권한을 부여한다(SecurityConfig.java:85)."]
      }
    },
    {
      id: "login-response", layer: "Response", title: "HTTP 200 (accessToken 응답)",
      filePath: "src/main/java/com/bobfull/auth/dto/LoginResponse.java",
      className: "LoginResponse", methodName: "of", codeType: "actual",
      code: `public static LoginResponse of(String accessToken) {\n    return new LoginResponse(accessToken, "Bearer");\n}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "LoginResponse.java:3-9 / 테스트: AuthControllerWebTest.로그인_성공시_AccessToken을_반환한다",
      httpResponse: `200 OK\n{ "success": true, "data": { "accessToken": "<JWT>", "tokenType": "Bearer" } }`,
      stateChanges: {},
      explanation: {
        whatRuns: ["accessToken과 tokenType만 응답한다. role은 응답 바디에 노출되지 않고 JWT 내부에만 존재한다."],
        whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: []
      }
    }
  ];
  return {
    steps,
    initialState: { memberFound: "조회 전", passwordValid: "확인 전", tokenIssued: "발급 전", role: "-", transactionState: "없음" },
    groups: makeGroups(steps, [
      { title: "로그인 요청 접수", count: 2 },
      { title: "회원 조회·비밀번호 확인", count: 2 },
      { title: "JWT 발급", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["memberFound", "passwordValid", "tokenIssued", "role"]
  };
}

/* ------------------------------------------------------------------ */
/* Ch1. 사장님 준비 — 식당 등록 / 테이블 등록 / 회차 등록                        */
/* ------------------------------------------------------------------ */

function restaurantRegisterSteps() {
  const steps = [
    {
      id: "rr-http", layer: "HTTP", title: "식당 등록 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: `POST /api/owner/restaurants\n{ "name": "밥풀식당", "address": "서울시 ...", "category": "한식", "description": "...", "keyword": "합석,회식", "depositPerPerson": 10000 }`,
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SecurityConfig.java:85 — /api/owner/** 는 hasRole(\"OWNER\")",
      stateChanges: {}, explanation: { whatRuns: ["사장님이 식당 정보를 입력해 등록을 요청한다(가상 테스트 데이터)."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "rr-controller", layer: "Controller", title: "OwnerRestaurantController.register 진입",
      filePath: "src/main/java/com/bobfull/restaurant/controller/OwnerRestaurantController.java",
      className: "OwnerRestaurantController", methodName: "register", codeType: "actual",
      code:
`@PostMapping
public ResponseEntity<ApiResponse<RestaurantIdResponse>> register(
        @AuthenticationPrincipal AuthMember authMember,
        @Valid @RequestBody RestaurantCreateRequest request
) {
    RestaurantIdResponse response = restaurantService.register(authMember.id(), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "OwnerRestaurantController.java:37-44",
      stateChanges: {},
      explanation: {
        whatRuns: ["소유자 ID는 요청 값이 아니라 인증된 AuthMember.id()로 결정한다."],
        whatChanged: [], safeguards: ["소유자를 요청 바디에서 신뢰하지 않고 SecurityContext에서만 가져온다."], whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "rr-service", layer: "Service", title: "RestaurantService.register — Restaurant 생성",
      filePath: "src/main/java/com/bobfull/restaurant/service/RestaurantService.java",
      className: "RestaurantService", methodName: "register", codeType: "actual",
      code:
`@Transactional
public RestaurantIdResponse register(Long ownerMemberId, RestaurantCreateRequest request) {
    Restaurant restaurant = Restaurant.create(
            ownerMemberId, request.name(), request.address(), request.category(),
            request.description(), request.keyword(), request.depositPerPerson());
    Restaurant savedRestaurant = restaurantRepository.save(restaurant);
    return RestaurantIdResponse.from(savedRestaurant);
}`,
      externalCall: "없음", repositoryAction: "RestaurantRepository.save(restaurant) — INSERT, MySQL", error: null,
      evidence: "RestaurantService.java:38-52",
      stateChanges: { transactionState: "없음 → 쓰기 트랜잭션 진행중", restaurantSaved: "저장 전 → 저장됨", restaurantId: "발급됨" },
      explanation: {
        whatRuns: ["Restaurant.create로 도메인 객체를 만들고 즉시 저장한다."],
        whatChanged: ["restaurantSaved, restaurantId 갱신."],
        safeguards: [], whySequence: [],
        ifFails: [], fact: []
      }
    },
    {
      id: "rr-response", layer: "Response", title: "HTTP 201 (restaurantId 응답)",
      filePath: "src/main/java/com/bobfull/restaurant/controller/OwnerRestaurantController.java",
      className: "OwnerRestaurantController", methodName: "register", codeType: "actual",
      code: `return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "OwnerRestaurantController.java:43 / 테스트: OwnerRestaurantControllerWebTest.OWNER가_식당을_등록하면_201과_restaurantId를_반환한다",
      httpResponse: `201 CREATED\n{ "success": true, "data": { "restaurantId": <실제 값> } }`,
      stateChanges: { transactionState: "쓰기 트랜잭션 진행중 → 커밋됨" },
      explanation: { whatRuns: ["트랜잭션 커밋 후 201과 restaurantId를 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: { restaurantSaved: "저장 전", restaurantId: null, transactionState: "없음" },
    groups: makeGroups(steps, [
      { title: "등록 요청 접수", count: 2 },
      { title: "Restaurant 생성·저장", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["restaurantSaved", "restaurantId", "transactionState"]
  };
}

function tableRegisterSteps() {
  const steps = [
    {
      id: "tr-http", layer: "HTTP", title: "테이블 등록 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: `POST /api/owner/restaurants/{restaurantId}/tables\n{ "capacity": 4 }`,
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SecurityConfig.java:85 — /api/owner/** 는 hasRole(\"OWNER\")",
      stateChanges: {}, explanation: { whatRuns: ["사장님이 등록해 둔 식당의 합석 테이블 정원을 입력해 등록을 요청한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "tr-controller", layer: "Controller", title: "SharedTableController.register 진입",
      filePath: "src/main/java/com/bobfull/sharedtable/controller/SharedTableController.java",
      className: "SharedTableController", methodName: "register", codeType: "actual",
      code:
`@PostMapping("/restaurants/{restaurantId}/tables")
public ResponseEntity<ApiResponse<SharedTableIdResponse>> register(
        @AuthenticationPrincipal AuthMember authMember,
        @PathVariable Long restaurantId,
        @Valid @RequestBody SharedTableRequest request
) {
    SharedTableIdResponse response = sharedTableService.register(authMember.id(), restaurantId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SharedTableController.java:35-43",
      stateChanges: {}, explanation: { whatRuns: ["경로의 restaurantId와 인증된 사장님 ID를 Service로 전달한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "tr-validate", layer: "Domain", title: "소유권·정원 검증",
      filePath: "src/main/java/com/bobfull/sharedtable/service/SharedTableService.java",
      className: "SharedTableService", methodName: "register", codeType: "actual",
      code:
`Restaurant restaurant = findActiveRestaurantOrThrow(restaurantId);
validateOwnership(restaurant, ownerMemberId);
validateCapacity(request.capacity());
// ALLOWED_CAPACITIES = Set.of(2, 4, 6, 8)`,
      externalCall: "없음", repositoryAction: "RestaurantRepository.findByIdAndDeletedAtIsNull — SELECT, MySQL", error: null,
      evidence: "SharedTableService.java:28, 48-51 / 테스트: SharedTableServiceTest.허용되지_않는_capacity로_등록하면_400_예외가_발생하고_저장하지_않는다",
      stateChanges: { ownershipValid: "확인 전 → 확인됨", capacityValid: "확인 전 → 허용값(2/4/6/8)" },
      explanation: {
        whatRuns: ["식당 소유권과 capacity 허용값(2,4,6,8)을 검증한다."],
        whatChanged: ["ownershipValid, capacityValid 갱신."],
        safeguards: ["허용되지 않는 capacity는 저장 전에 400으로 거절된다."],
        whySequence: [], ifFails: ["소유권 불일치 시 403, capacity 불허 시 400 — 저장은 시도되지 않는다."], fact: []
      }
    },
    {
      id: "tr-save", layer: "Repository", title: "SharedTable 생성·저장",
      filePath: "src/main/java/com/bobfull/sharedtable/service/SharedTableService.java",
      className: "SharedTableService", methodName: "register", codeType: "actual",
      code: `SharedTable sharedTable = SharedTable.create(restaurantId, request.capacity());\nSharedTable savedTable = sharedTableRepository.save(sharedTable);\nreturn SharedTableIdResponse.from(savedTable);`,
      externalCall: "없음", repositoryAction: "SharedTableRepository.save(...) — INSERT, MySQL", error: null,
      evidence: "SharedTableService.java:53-56 / 테스트: SharedTableServiceTest.합석_테이블을_등록하면_ACTIVE_상태와_식당_ID를_저장한다",
      stateChanges: { tableSaved: "저장 전 → 저장됨", tableId: "발급됨", transactionState: "없음 → 쓰기 트랜잭션 진행중 → 커밋됨" },
      explanation: { whatRuns: ["검증을 통과한 요청만 SharedTable로 저장된다."], whatChanged: ["tableSaved, tableId 갱신."], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "tr-response", layer: "Response", title: "HTTP 201 (tableId 응답)",
      filePath: "src/main/java/com/bobfull/sharedtable/controller/SharedTableController.java",
      className: "SharedTableController", methodName: "register", codeType: "actual",
      code: `return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SharedTableController.java:42 / 테스트: SharedTableControllerWebTest.OWNER가_합석_테이블을_등록하면_201과_tableId를_반환한다",
      httpResponse: `201 CREATED\n{ "success": true, "data": { "tableId": <실제 값> } }`,
      stateChanges: {}, explanation: { whatRuns: ["201과 tableId를 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: { ownershipValid: "확인 전", capacityValid: "확인 전", tableSaved: "저장 전", tableId: null, transactionState: "없음" },
    groups: makeGroups(steps, [
      { title: "등록 요청 접수", count: 2 },
      { title: "소유권·정원 검증", count: 1 },
      { title: "SharedTable 생성·저장", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["ownershipValid", "capacityValid", "tableSaved", "tableId"]
  };
}

function sessionRegisterSteps() {
  const steps = [
    {
      id: "sr-http", layer: "HTTP", title: "회차 등록 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: `POST /api/owner/tables/{tableId}/dining-sessions\n{ "startAt": "2026-08-01T19:00:00", "endAt": "2026-08-01T21:00:00" }`,
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SecurityConfig.java:85 — /api/owner/** 는 hasRole(\"OWNER\")",
      stateChanges: {}, explanation: { whatRuns: ["사장님이 등록해 둔 테이블에 예약 가능 회차(시작~종료 시각)를 등록 요청한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "sr-controller", layer: "Controller", title: "DiningSessionController.register 진입",
      filePath: "src/main/java/com/bobfull/timeslot/controller/DiningSessionController.java",
      className: "DiningSessionController", methodName: "register", codeType: "actual",
      code:
`@PostMapping("/owner/tables/{tableId}/dining-sessions")
public ResponseEntity<ApiResponse<DiningSessionIdResponse>> register(
        @AuthenticationPrincipal AuthMember authMember,
        @PathVariable Long tableId,
        @Valid @RequestBody DiningSessionRequest request
) {
    DiningSessionIdResponse response = timeSlotService.register(authMember.id(), tableId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "DiningSessionController.java:40-48",
      stateChanges: {}, explanation: { whatRuns: ["대상 tableId와 인증된 사장님 ID를 Service로 전달한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "sr-ownership", layer: "Domain", title: "테이블 소유권 검증",
      filePath: "src/main/java/com/bobfull/timeslot/service/TimeSlotService.java",
      className: "TimeSlotService", methodName: "register", codeType: "actual",
      code: `SharedTable sharedTable = findActiveTableOrThrow(tableId);\nvalidateRestaurantOwnership(sharedTable.getRestaurantId(), ownerMemberId);`,
      externalCall: "없음", repositoryAction: "SharedTableRepository 조회 — SELECT, MySQL", error: null,
      evidence: "TimeSlotService.java:76-78",
      stateChanges: { ownershipValid: "확인 전 → 확인됨" },
      explanation: { whatRuns: ["회차를 등록할 테이블이 요청 사장님 소유인지 확인한다."], whatChanged: ["ownershipValid 갱신."], safeguards: [], whySequence: [], ifFails: ["소유권 불일치 시 403"], fact: [] }
    },
    {
      id: "sr-duplicate", layer: "Domain", title: "활성 회차 중복 검증",
      filePath: "src/main/java/com/bobfull/timeslot/service/TimeSlotService.java",
      className: "TimeSlotService", methodName: "register", codeType: "actual",
      code: `TimeRange timeRange = toTimeRange(request.startAt(), request.endAt());\nvalidateActiveDuplicate(sharedTable.getId(), timeRange.startAt());\n// DB unique 제약: uk_time_slot_active_start (같은 테이블·같은 시작시각의 활성 회차 중복 방지)`,
      externalCall: "없음", repositoryAction: "TimeSlotRepository 조회 — SELECT, MySQL", error: null,
      evidence: "TimeSlotService.java:80-81, TimeSlot.java:18-25(생성 컬럼 active_start_at + UNIQUE 제약) / 테스트: DiningSessionControllerWebTest.중복_회차를_등록하면_409를_반환한다",
      stateChanges: { duplicateChecked: "확인 전 → 중복 없음" },
      explanation: {
        whatRuns: ["같은 테이블·같은 시작시각의 활성(soft-delete 안 된) 회차가 이미 있는지 확인한다."],
        whatChanged: ["duplicateChecked 갱신."],
        safeguards: ["애플리케이션 검증과 별개로 DB에도 활성 회차 시작시각 UNIQUE 제약이 있어 이중으로 방지된다."],
        whySequence: [], ifFails: ["중복이면 409 DUPLICATE_DINING_SESSION"], fact: []
      }
    },
    {
      id: "sr-save", layer: "Repository", title: "TimeSlot 저장",
      filePath: "src/main/java/com/bobfull/timeslot/service/TimeSlotService.java",
      className: "TimeSlotService", methodName: "register", codeType: "actual",
      code: `TimeSlot savedTimeSlot = saveTimeSlotOrThrowDuplicate(\n        TimeSlot.create(sharedTable.getId(), timeRange.startAt(), timeRange.endAt()));\nreturn DiningSessionIdResponse.from(savedTimeSlot);`,
      externalCall: "없음", repositoryAction: "TimeSlotRepository.save(...) — INSERT, MySQL", error: null,
      evidence: "TimeSlotService.java:83-85 / 테스트: DiningSessionControllerWebTest.OWNER가_회차를_등록하면_201과_sessionId를_반환한다",
      stateChanges: { sessionSaved: "저장 전 → 저장됨", sessionId: "발급됨", transactionState: "없음 → 쓰기 트랜잭션 진행중 → 커밋됨" },
      explanation: { whatRuns: ["서울 로컬 시각으로 받은 startAt/endAt을 UTC Instant로 변환해 저장한다."], whatChanged: ["sessionSaved, sessionId 갱신."], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "sr-response", layer: "Response", title: "HTTP 201 (sessionId 응답)",
      filePath: "src/main/java/com/bobfull/timeslot/controller/DiningSessionController.java",
      className: "DiningSessionController", methodName: "register", codeType: "actual",
      code: `return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "DiningSessionController.java:47",
      httpResponse: `201 CREATED\n{ "success": true, "data": { "sessionId": <실제 값> } }`,
      stateChanges: {}, explanation: { whatRuns: ["201과 sessionId를 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: { ownershipValid: "확인 전", duplicateChecked: "확인 전", sessionSaved: "저장 전", sessionId: null, transactionState: "없음" },
    groups: makeGroups(steps, [
      { title: "등록 요청 접수", count: 2 },
      { title: "소유권·중복 검증", count: 2 },
      { title: "TimeSlot 저장", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["ownershipValid", "duplicateChecked", "sessionSaved", "sessionId"]
  };
}

/* ------------------------------------------------------------------ */
/* Ch2. 사용자 탐색 — 식당 검색 / 상세 조회 / 예약 가능 회차 조회                 */
/* ------------------------------------------------------------------ */

function restaurantSearchSteps() {
  const steps = [
    {
      id: "rs-http", layer: "HTTP", title: "식당 검색 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: `GET /api/restaurants?keyword=합석&category=한식&date=2026-08-01&time=19:00`,
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "RestaurantController.java:19-21 — 인증 불필요",
      stateChanges: {}, explanation: { whatRuns: ["일반 사용자가 키워드/카테고리/날짜/시간으로 식당을 검색한다(인증 불필요)."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "rs-controller", layer: "Controller", title: "RestaurantController.searchRestaurants 진입",
      filePath: "src/main/java/com/bobfull/restaurant/controller/RestaurantController.java",
      className: "RestaurantController", methodName: "searchRestaurants", codeType: "actual",
      code:
`@GetMapping
public ApiResponse<PageResponse<RestaurantSearchResponse>> searchRestaurants(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) LocalTime time,
        @PageableDefault(size = 20) Pageable pageable
) {
    RestaurantSearchRequest request = new RestaurantSearchRequest(keyword, category, date, time);
    return ApiResponse.success(restaurantService.searchRestaurants(request, pageable));
}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "RestaurantController.java:29-39",
      stateChanges: {}, explanation: { whatRuns: ["쿼리 파라미터를 RestaurantSearchRequest로 묶어 Service에 위임한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "rs-query", layer: "Repository", title: "QueryDSL 동적 조건 조회",
      filePath: "src/main/java/com/bobfull/restaurant/repository/RestaurantSearchRepositoryImpl.java",
      className: "RestaurantSearchRepositoryImpl", methodName: "search", codeType: "actual",
      code:
`// restaurant.deletedAt.isNull(), status.eq(ACTIVE) 기본 조건
// keyword 지정 시 이름/keyword 포함검색(ignoreCase)
// category 지정 시 정확 일치
// date/time 지정 시 SharedTable·TimeSlot을 join해 활성(deletedAt.isNull()) 회차 존재 여부까지 필터`,
      externalCall: "없음", repositoryAction: "QueryDSL 동적 쿼리 — SELECT, MySQL (JOIN SharedTable/TimeSlot)", error: null,
      evidence: "RestaurantSearchRepositoryImpl.java:42-75 / 테스트: RestaurantSearchRepositoryTest.키워드_카테고리_날짜_시간이_모두_맞는_식당만_검색한다",
      stateChanges: { searchExecuted: "실행 전 → 실행됨" },
      explanation: {
        whatRuns: ["조건이 없으면 삭제되지 않은 ACTIVE 식당 기본 목록, 조건이 있으면 키워드·카테고리·날짜·시간을 모두 만족하는 식당만 필터한다."],
        whatChanged: ["searchExecuted 갱신."], safeguards: [], whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "rs-response", layer: "Response", title: "HTTP 200 (검색 결과 페이지 응답)",
      filePath: "src/main/java/com/bobfull/restaurant/controller/RestaurantController.java",
      className: "RestaurantController", methodName: "searchRestaurants", codeType: "actual",
      code: `return ApiResponse.success(restaurantService.searchRestaurants(request, pageable));`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "RestaurantController.java:38 / 테스트: RestaurantControllerWebTest.인증_없이_식당_목록을_검색할_수_있다",
      httpResponse: `200 OK\n{ "success": true, "data": { "content": [...], "totalElements": <N> } }`,
      stateChanges: {}, explanation: { whatRuns: ["조건에 맞는 식당 목록을 페이지 형태로 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: { searchExecuted: "실행 전" },
    groups: makeGroups(steps, [
      { title: "검색 요청 접수", count: 2 },
      { title: "DB 조회", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["searchExecuted"]
  };
}

function restaurantDetailSteps() {
  const steps = [
    {
      id: "rd-http", layer: "HTTP", title: "식당 상세 조회 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: `GET /api/restaurants/{restaurantId}`, httpResponse: null,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "RestaurantController.java:41-44 — 인증 불필요",
      stateChanges: {}, explanation: { whatRuns: ["일반 사용자가 특정 식당의 상세 정보를 조회한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "rd-controller", layer: "Controller", title: "RestaurantController.getRestaurant 진입",
      filePath: "src/main/java/com/bobfull/restaurant/controller/RestaurantController.java",
      className: "RestaurantController", methodName: "getRestaurant", codeType: "actual",
      code: `@GetMapping("/{restaurantId}")\npublic ApiResponse<RestaurantDetailResponse> getRestaurant(@PathVariable Long restaurantId) {\n    return ApiResponse.success(restaurantService.getRestaurantDetail(restaurantId));\n}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "RestaurantController.java:41-44",
      stateChanges: {}, explanation: { whatRuns: ["restaurantId를 그대로 Service에 위임한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "rd-service", layer: "Service", title: "RestaurantService.getRestaurantDetail 조회",
      filePath: "src/main/java/com/bobfull/restaurant/service/RestaurantService.java",
      className: "RestaurantService", methodName: "getRestaurantDetail", codeType: "actual",
      code: `Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)\n        .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));\nreturn RestaurantDetailResponse.from(restaurant);`,
      externalCall: "없음", repositoryAction: "RestaurantRepository.findByIdAndDeletedAtIsNull — SELECT, MySQL", error: null,
      evidence: "RestaurantService.java 내 getRestaurantDetail / 테스트: RestaurantControllerWebTest.존재하지_않는_식당을_조회하면_404를_반환한다",
      stateChanges: { restaurantFound: "조회 전 → 조회됨" },
      explanation: {
        whatRuns: ["soft-delete되지 않은 식당만 조회한다."],
        whatChanged: ["restaurantFound 갱신."],
        safeguards: [],
        whySequence: [],
        ifFails: ["없으면 404 RESTAURANT_ID_NOT_FOUND"],
        fact: ["응답(RestaurantDetailResponse)에는 name/address/category/description/keyword/depositPerPerson만 포함되며, 테이블(SharedTable)·정원 정보는 의도적으로 포함하지 않는다(주석에 명시)."]
      }
    },
    {
      id: "rd-response", layer: "Response", title: "HTTP 200 (상세 응답, 테이블 정보 미포함)",
      filePath: "src/main/java/com/bobfull/restaurant/controller/RestaurantController.java",
      className: "RestaurantController", methodName: "getRestaurant", codeType: "actual",
      code: `return ApiResponse.success(restaurantService.getRestaurantDetail(restaurantId));`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "RestaurantControllerWebTest.인증_없이_식당_상세를_조회할_수_있다",
      httpResponse: `200 OK\n{ "success": true, "data": { "restaurantId":..,"name":..,"address":..,"category":..,"description":..,"keyword":..,"depositPerPerson":.. } }`,
      stateChanges: {}, explanation: { whatRuns: ["식당 기본 정보를 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: { restaurantFound: "조회 전" },
    groups: makeGroups(steps, [
      { title: "조회 요청 접수", count: 2 },
      { title: "식당 조회", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["restaurantFound"]
  };
}

function sessionSearchSteps() {
  const steps = [
    {
      id: "ss-http", layer: "HTTP", title: "예약 가능 회차 조회 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: `GET /api/restaurants/{restaurantId}/dining-sessions?date=2026-08-01&partySize=2`,
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "DiningSessionController.java:71-78 — 인증 불필요, date는 필수",
      stateChanges: {}, explanation: { whatRuns: ["일반 사용자가 날짜(필수)와 인원수(선택)로 예약 가능한 회차를 조회한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: ["date가 없으면 400 INVALID_INPUT_VALUE"], fact: [] }
    },
    {
      id: "ss-controller", layer: "Controller", title: "DiningSessionController.getAvailableDiningSessions 진입",
      filePath: "src/main/java/com/bobfull/timeslot/controller/DiningSessionController.java",
      className: "DiningSessionController", methodName: "getAvailableDiningSessions", codeType: "actual",
      code:
`@GetMapping("/restaurants/{restaurantId}/dining-sessions")
public ApiResponse<AvailableDiningSessionListResponse> getAvailableDiningSessions(
        @PathVariable Long restaurantId,
        @RequestParam LocalDate date,
        @RequestParam(required = false) Integer partySize
) {
    return ApiResponse.success(timeSlotService.getAvailableDiningSessions(restaurantId, date, partySize));
}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "DiningSessionController.java:71-78",
      stateChanges: {}, explanation: { whatRuns: ["restaurantId/date/partySize를 Service로 전달한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "ss-tables", layer: "Repository", title: "활성 테이블·회차 조회",
      filePath: "src/main/java/com/bobfull/timeslot/service/TimeSlotService.java",
      className: "TimeSlotService", methodName: "getAvailableDiningSessions", codeType: "actual",
      code:
`List<SharedTable> sharedTables = sharedTableRepository.findAllByRestaurantIdAndDeletedAtIsNull(restaurantId);
List<AvailableDiningSessionResponse> content = timeSlotRepository
        .findAllBySharedTableIdInAndStartAtGreaterThanEqualAndStartAtLessThanAndDeletedAtIsNullOrderByStartAtAsc(
                capacityByTableId.keySet(), dateRange.startAt(), dateRange.endAt())
        .stream().map(...)`,
      externalCall: "없음", repositoryAction: "SharedTableRepository/TimeSlotRepository 조회 — SELECT, MySQL (deletedAt IS NULL만 대상)", error: null,
      evidence: "TimeSlotService.java:134-156",
      stateChanges: { sessionsLoaded: "조회 전 → 조회됨" },
      explanation: {
        whatRuns: ["식당의 활성(soft-delete 안 된) 테이블과, 그 테이블들의 활성 회차만 날짜 범위로 조회한다."],
        whatChanged: ["sessionsLoaded 갱신."], safeguards: [], whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "ss-capacity", layer: "Domain", title: "잔여 좌석(availableCapacity) 실시간 계산",
      filePath: "src/main/java/com/bobfull/reservation/service/AvailableCapacityCalculator.java",
      className: "AvailableCapacityCalculator", methodName: "calculate", codeType: "actual",
      code:
`public int calculate(Long timeSlotId, Integer tableCapacity) {
    int currentParticipantCount = reservationRepository
            .findByTimeSlotIdAndReservationStatusIn(timeSlotId, ACTIVE_STATUSES)
            .map(this::activeParticipantCount).orElse(0);
    int pendingHoldCount = paymentHoldReader.sumActiveReadyPartySize(timeSlotId);
    return Math.max(0, tableCapacity - currentParticipantCount - pendingHoldCount);
}`,
      externalCall: "없음", repositoryAction: "ReservationRepository/ReservationParticipantRepository/PaymentRepository 조회 — SELECT, MySQL", error: null,
      evidence: "AvailableCapacityCalculator.java:36-43 / 테스트: TimeSlotServiceTest.사용자용_예약_가능_회차는_availableCapacity를_현재_capacity로_반환하고_partySize로_필터한다",
      stateChanges: { availableCapacityCalculated: "계산 전 → 계산됨" },
      explanation: {
        whatRuns: ["테이블 정원에서 활성 예약 참여 인원과, 만료되지 않은 READY 결제 임시선점 인원을 함께 빼서 잔여 좌석을 계산한다(음수 방지)."],
        whatChanged: ["availableCapacityCalculated 갱신."],
        safeguards: ["TimeSlot 엔티티 자체에는 잔여좌석 컬럼이 없다 — 매 조회 시 실시간으로 계산한다."],
        whySequence: [], ifFails: [], fact: ["partySize가 지정되면 availableCapacity >= partySize인 회차만 필터한다(TimeSlotService.java:157)."]
      }
    },
    {
      id: "ss-response", layer: "Response", title: "HTTP 200 (예약 가능 회차 목록 응답)",
      filePath: "src/main/java/com/bobfull/timeslot/controller/DiningSessionController.java",
      className: "DiningSessionController", methodName: "getAvailableDiningSessions", codeType: "actual",
      code: `return ApiResponse.success(timeSlotService.getAvailableDiningSessions(restaurantId, date, partySize));`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "DiningSessionControllerWebTest.사용자용_예약_가능_회차는_인증_없이_조회한다",
      httpResponse: `200 OK\n{ "success": true, "data": { "restaurantId":..,"sessions":[{"sessionId":..,"startAt":..,"availableCapacity":..}] } }`,
      stateChanges: {}, explanation: { whatRuns: ["회차별 잔여 좌석을 포함한 목록을 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: { sessionsLoaded: "조회 전", availableCapacityCalculated: "계산 전" },
    groups: makeGroups(steps, [
      { title: "조회 요청 접수", count: 2 },
      { title: "활성 테이블·회차 조회", count: 1 },
      { title: "잔여 좌석 계산", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["sessionsLoaded", "availableCapacityCalculated"]
  };
}

/* ------------------------------------------------------------------ */
/* Ch3. 예약·결제 준비 (CREATE / JOIN 변형) — READY Payment 생성                */
/* ------------------------------------------------------------------ */

function reservationPrepareSteps(purpose) {
  const isCreate = purpose === "CREATE";
  const steps = [
    {
      id: "pp-http", layer: "HTTP", title: "예약·결제 준비 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: isCreate
        ? `POST /api/reservations/prepare\n{ "type": "CREATE", "targetId": <timeSlotId>, "partySize": 3 }`
        : `POST /api/reservations/prepare\n{ "type": "JOIN", "targetId": <reservationId>, "partySize": 1 }`,
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "ReservationPrepareRequest — type(CREATE/JOIN)에 따라 targetId 의미가 다르다(CREATE=TimeSlot, JOIN=Reservation)",
      stateChanges: {},
      explanation: {
        whatRuns: [isCreate ? "새 모임을 만드는 CREATE 결제를 준비 요청한다." : "기존 모임에 참여하는 JOIN 결제를 준비 요청한다."],
        whatChanged: [], safeguards: [], whySequence: [], ifFails: [],
        fact: ["PortOne은 이 단계에서 호출되지 않는다 — 백엔드는 내부 UUID paymentId만 발급하며, 실제 PortOne 결제창은 프론트엔드가 별도로 연다."]
      }
    },
    {
      id: "pp-controller", layer: "Controller", title: "ReservationController.prepare 진입",
      filePath: "src/main/java/com/bobfull/reservation/controller/ReservationController.java",
      className: "ReservationController", methodName: "prepare", codeType: "actual",
      code: `@PostMapping("/prepare")\npublic ApiResponse<ReservationPrepareResponse> prepare(\n        @AuthenticationPrincipal AuthMember authMember,\n        @Valid @RequestBody ReservationPrepareRequest request\n) {\n    return ApiResponse.success(reservationPreparationService.prepare(authMember.id(), request));\n}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "ReservationController.java:67-73",
      stateChanges: {}, explanation: { whatRuns: ["인증된 memberId와 요청을 그대로 Service에 위임한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "pp-target-resolve", layer: "Service", title: isCreate ? "CREATE 대상 검증: TimeSlot 락 조회·정원·중복 확인" : "JOIN 대상 검증: Reservation 조회·모집상태·중복 확인",
      filePath: "src/main/java/com/bobfull/reservation/service/ReservationPreparationService.java",
      className: "ReservationPreparationService", methodName: isCreate ? "resolveCreateTarget" : "resolveJoinTarget", codeType: "actual",
      code: isCreate
        ? `private ValidatedTarget resolveCreateTarget(Long timeSlotId, Integer partySize, boolean lock) {
    TimeSlot timeSlot = findTimeSlotOrThrow(timeSlotId, lock); // findWithLockByIdAndDeletedAtIsNull
    SharedTable sharedTable = findTableOrThrow(timeSlot.getSharedTableId());
    Restaurant restaurant = findRestaurantOrThrow(sharedTable.getRestaurantId());
    validatePartySizeAgainstCapacity(partySize, sharedTable.getCapacity());
    validateNoActiveCreate(timeSlot.getId()); // 활성 예약 또는 만료 안 된 CREATE READY Payment 존재 시 409
    int availableCapacity = availableCapacityCalculator.calculate(timeSlot.getId(), sharedTable.getCapacity());
    return new ValidatedTarget(timeSlot.getId(), null, restaurant.getDepositPerPerson(), availableCapacity);
}`
        : `private ValidatedTarget resolveJoinTarget(Long memberId, Long reservationId, Integer partySize, boolean lock) {
    Reservation reservation = findReservationOrThrow(reservationId);
    // TimeSlot 잠금을 먼저 획득한 뒤 검증해야 동시 JOIN 요청이 직렬화된다(ADR 0001).
    TimeSlot timeSlot = findTimeSlotOrThrow(reservation.getTimeSlotId(), lock);
    validateJoinable(reservation); // RECRUITING/CONFIRMED + OPEN 아니면 INVALID_STATE
    validateNotAlreadyParticipating(reservation.getId(), memberId);
    validateNoActiveJoinReady(reservation.getId(), memberId); // 만료 안 된 JOIN READY Payment 중복 방지
    int availableCapacity = availableCapacityCalculator.calculate(timeSlot.getId(), sharedTable.getCapacity());
    validatePartySizeAgainstRemainingCapacity(partySize, availableCapacity);
    return new ValidatedTarget(timeSlot.getId(), reservation.getId(), restaurant.getDepositPerPerson(), availableCapacity);
}`,
      externalCall: "없음",
      repositoryAction: isCreate
        ? "TimeSlotRepository.findWithLockByIdAndDeletedAtIsNull — @Lock(PESSIMISTIC_WRITE), 예상 SQL: SELECT ... FOR UPDATE"
        : "ReservationRepository.findById + TimeSlotRepository.findWithLockByIdAndDeletedAtIsNull — 후자에 @Lock(PESSIMISTIC_WRITE)",
      error: null,
      evidence: isCreate
        ? "ReservationPreparationService.java:96-105, 124-131 / 테스트: ReservationPreparationServiceTest.CREATE_partySize가_테이블_정원을_초과하면_400_예외가_발생한다"
        : "ReservationPreparationService.java:107-122 / 테스트: ReservationPreparationServiceTest.JOIN_partySize가_잔여_인원을_초과하면_409_예외가_발생한다",
      stateChanges: { timeSlotLock: "없음 → 보유", targetValidated: "확인 전 → 확인됨" },
      explanation: {
        whatRuns: [isCreate
          ? "TimeSlot을 비관적 락으로 조회하고, 정원 초과와 활성 예약/CREATE READY 중복을 검증한다."
          : "대상 Reservation을 먼저 조회한 뒤 그 TimeSlot을 비관적 락으로 조회하고, 모집 가능 상태·중복 참여·중복 JOIN READY를 검증한다."],
        whatChanged: ["timeSlotLock, targetValidated 갱신."],
        safeguards: ["TimeSlot 행 락으로 같은 회차에 대한 동시 CREATE/JOIN 요청을 직렬화한다(ADR 0001)."],
        whySequence: [], ifFails: ["검증 실패 시 400/409(ACTIVE_RESERVATION_ALREADY_EXISTS, INSUFFICIENT_REMAINING_CAPACITY 등)로 즉시 종료, Payment는 생성되지 않는다."],
        fact: []
      }
    },
    {
      id: "pp-amount", layer: "Domain", title: "예약금 계산",
      filePath: "src/main/java/com/bobfull/reservation/service/ReservationPreparationService.java",
      className: "ReservationPreparationService", methodName: "prepare", codeType: "actual",
      code: `BigDecimal amount = BigDecimal.valueOf(target.depositPerPerson()).multiply(BigDecimal.valueOf(request.partySize()));\nCreateReadyPaymentCommand command = new CreateReadyPaymentCommand(\n        memberId, target.timeSlotId(), target.reservationId(), request.type(), request.partySize(), amount);`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "ReservationPreparationService.java:89-91",
      stateChanges: { amountCalculated: "계산 전 → 계산됨(1인 예약금 × partySize)" },
      explanation: { whatRuns: ["식당의 1인당 예약금(depositPerPerson)에 partySize를 곱해 결제 금액을 계산한다."], whatChanged: ["amountCalculated 갱신."], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "pp-payment-service", layer: "Service", title: "PaymentService.createReadyPayment — 만료 시각 설정",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentService.java",
      className: "PaymentService", methodName: "createReadyPayment", codeType: "actual",
      code:
`@Transactional
public CreateReadyPaymentResult createReadyPayment(CreateReadyPaymentCommand command) {
    Instant expiresAt = clock.instant().plus(READY_PAYMENT_EXPIRATION); // Duration.ofMinutes(10)
    Payment payment = Payment.createReady(
            UUID.randomUUID().toString(), command.memberId(), command.timeSlotId(),
            command.reservationId(), command.purpose(), command.partySize(), command.amount(), expiresAt);
    // ...`,
      externalCall: "없음", repositoryAction: "없음(다음 단계에서 저장)", error: null,
      evidence: "PaymentService.java:35-48",
      stateChanges: { expiresAt: "설정 전 → now+10분", transactionState: "없음 → 쓰기 트랜잭션 진행중" },
      explanation: {
        whatRuns: ["결제 식별자로 UUID 문자열을 발급하고, 생성 시점 기준 10분 뒤를 expiresAt으로 설정한다."],
        whatChanged: ["expiresAt 갱신."],
        safeguards: [],
        whySequence: [],
        ifFails: [],
        fact: ["여기서 paymentId는 PortOne이 아니라 UUID.randomUUID()로 백엔드가 직접 발급한다 — 이 단계에서 PortOne API 호출은 없다."]
      }
    },
    {
      id: "pp-payment-create", layer: "Domain", title: "Payment.createReady — 도메인 불변식 검증",
      filePath: "src/main/java/com/bobfull/payment/entity/Payment.java",
      className: "Payment", methodName: "createReady", codeType: "actual",
      code:
`if (purpose == PaymentPurpose.CREATE && reservationId != null) {
    throw new IllegalArgumentException("CREATE 결제는 reservationId를 가질 수 없습니다.");
}
if (purpose == PaymentPurpose.JOIN && (reservationId == null || reservationId <= 0)) {
    throw new IllegalArgumentException("JOIN 결제는 reservationId가 필요합니다.");
}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "Payment.java:93-138",
      stateChanges: { paymentStatus: "없음 → READY" },
      explanation: {
        whatRuns: [`${isCreate ? "CREATE" : "JOIN"} 결제이므로 reservationId가 ${isCreate ? "null이어야" : "양수여야"} 하는 불변식을 검증한 뒤 status=READY로 생성한다.`],
        whatChanged: ["paymentStatus: 없음 → READY."],
        safeguards: ["CREATE/JOIN 각각의 reservationId 불변식을 도메인 엔티티 스스로 강제한다."],
        whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "pp-save", layer: "Repository", title: "Payment 저장(saveAndFlush)",
      filePath: "src/main/java/com/bobfull/payment/service/PaymentService.java",
      className: "PaymentService", methodName: "createReadyPayment", codeType: "actual",
      code: `try {\n    return CreateReadyPaymentResult.from(paymentRepository.saveAndFlush(payment));\n} catch (DataIntegrityViolationException e) {\n    throw new CustomException(PaymentErrorCode.DUPLICATE_PAYMENT_ID);\n}`,
      externalCall: "없음", repositoryAction: "PaymentRepository.saveAndFlush(payment) — INSERT 즉시 flush, portone_payment_id UNIQUE 제약", error: null,
      evidence: "PaymentService.java:50-54",
      stateChanges: { paymentSaved: "저장 전 → 저장됨(saveAndFlush)", transactionState: "쓰기 트랜잭션 진행중 → 커밋됨" },
      explanation: {
        whatRuns: ["일반 save가 아니라 saveAndFlush로 즉시 INSERT해, UNIQUE 제약(portone_payment_id) 위반을 같은 트랜잭션에서 바로 감지한다."],
        whatChanged: ["paymentSaved 갱신."],
        safeguards: ["UUID 충돌(극히 낮은 확률)이 발생해도 DUPLICATE_PAYMENT_ID로 안전하게 처리된다."],
        whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "pp-response", layer: "Response", title: "HTTP 200 (paymentId·READY·expiresAt 응답)",
      filePath: "src/main/java/com/bobfull/reservation/dto/ReservationPrepareResponse.java",
      className: "ReservationPrepareResponse", methodName: "from", codeType: "actual",
      code: `// expiresAt을 Asia/Seoul 오프셋으로 변환해 응답\nreturn new ReservationPrepareResponse(result.paymentId(), result.status(), result.amount(), seoulExpiresAt);`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "ReservationPrepareResponse.java / 테스트: ReservationControllerWebTest.결제를_준비하면_paymentId와_만료시각을_반환한다",
      httpResponse: `200 OK\n{ "success": true, "data": { "paymentId": "<UUID>", "paymentStatus": "READY", "amount": <값>, "expiresAt": "<+09:00 오프셋>" } }`,
      stateChanges: {}, explanation: { whatRuns: ["발급된 paymentId, READY 상태, 계산된 금액, 서울 시간 기준 만료시각을 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: {
      timeSlotLock: "없음", targetValidated: "확인 전", amountCalculated: "계산 전",
      expiresAt: "설정 전", paymentStatus: "없음", paymentSaved: "저장 전", transactionState: "없음"
    },
    groups: makeGroups(steps, [
      { title: "예약 준비 요청", count: 2 },
      { title: "대상 검증(CREATE/JOIN 분기)", count: 1 },
      { title: "예약금 계산", count: 1 },
      { title: "Payment READY 생성", count: 3 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["targetValidated", "paymentStatus", "paymentSaved", "expiresAt"]
  };
}

/* ------------------------------------------------------------------ */
/* Ch5. 예약·정산 확인 — 지급예정 총액/목록/상세 조회                          */
/* ------------------------------------------------------------------ */

function expectedSettlementSteps() {
  const steps = [
    {
      id: "es-http", layer: "HTTP", title: "지급 예정 총액 조회 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: `GET /api/owner/restaurants/{restaurantId}/settlements/expected?startDate=2026-07-01&endDate=2026-07-31`,
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SecurityConfig.java:85 — /api/owner/** 는 hasRole(\"OWNER\")",
      stateChanges: {}, explanation: { whatRuns: ["사장님이 기간을 지정해 자신의 식당에 지급될 예정 금액 총액을 조회한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "es-controller", layer: "Controller", title: "SettlementController.getExpectedSettlement 진입",
      filePath: "src/main/java/com/bobfull/payment/controller/SettlementController.java",
      className: "SettlementController", methodName: "getExpectedSettlement", codeType: "actual",
      code:
`@GetMapping("/restaurants/{restaurantId}/settlements/expected")
public ApiResponse<ExpectedSettlementResponse> getExpectedSettlement(
        @AuthenticationPrincipal AuthMember authMember,
        @PathVariable Long restaurantId,
        @RequestParam(required = false) LocalDate startDate,
        @RequestParam(required = false) LocalDate endDate
) {
    return ApiResponse.success(settlementQueryService.getExpectedSettlement(authMember.id(), restaurantId, startDate, endDate));
}`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SettlementController.java:30-38",
      stateChanges: {}, explanation: { whatRuns: ["인증된 사장님 ID와 기간 파라미터를 Service에 위임한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "es-ownership", layer: "Domain", title: "식당 소유권 검증",
      filePath: "src/main/java/com/bobfull/payment/service/SettlementQueryService.java",
      className: "SettlementQueryService", methodName: "validateOwnership", codeType: "actual",
      code:
`private void validateOwnership(Long ownerMemberId, Long restaurantId) {
    Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
            .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));
    if (!restaurant.isOwnedBy(ownerMemberId)) {
        throw new CustomException(CommonErrorCode.ACCESS_DENIED);
    }
}`,
      externalCall: "없음", repositoryAction: "RestaurantRepository.findByIdAndDeletedAtIsNull — SELECT, MySQL", error: null,
      evidence: "SettlementQueryService.java:138-144 / 테스트: SettlementQueryServiceTest.타인식당의_정산은_403을_반환한다",
      stateChanges: { ownershipValid: "확인 전 → 확인됨" },
      explanation: { whatRuns: ["요청자가 이 식당의 실제 소유자인지 확인한다."], whatChanged: ["ownershipValid 갱신."], safeguards: [], whySequence: [], ifFails: ["불일치 시 403 ACCESS_DENIED"], fact: [] }
    },
    {
      id: "es-sum", layer: "Repository", title: "지급 예정액 합계 조회",
      filePath: "src/main/java/com/bobfull/payment/repository/PaymentRepository.java",
      className: "PaymentRepository", methodName: "sumSettlementAmounts", codeType: "actual",
      code:
`@Query("select coalesce(sum(p.amount), 0), coalesce(sum(case when f.status = :completedStatus then f.amount else 0 end), 0) "
        + "from Payment p join TimeSlot ts on p.timeSlotId = ts.id join SharedTable st on ts.sharedTableId = st.id "
        + "left join Refund f on f.payment = p "
        + "where st.restaurantId = :restaurantId and p.paidAt is not null "
        + "and (:startAt is null or ts.startAt >= :startAt) and (:endAt is null or ts.startAt < :endAt)")
List<Object[]> sumSettlementAmounts(...);`,
      externalCall: "없음", repositoryAction: "JPQL 집계 쿼리 — SELECT SUM, MySQL (paidAt IS NOT NULL 기준, COMPLETED 환불만 차감)", error: null,
      evidence: "PaymentRepository.java:52-62 / 테스트: SettlementAmountRepositoryTest.REFUNDED여도_paidAt이_있는_결제완료이력은_한번만_차감한다, 완료되지않은_환불은_지급예정액에서_차감하지않는다",
      stateChanges: { amountsCalculated: "계산 전 → 계산됨" },
      explanation: {
        whatRuns: ["paidAt이 있는 Payment 합계에서 COMPLETED 상태 Refund 합계만 차감해 지급예정액을 계산한다. 날짜 범위는 서울 시간 '시작일 포함~종료일 다음날 미만'."],
        whatChanged: ["amountsCalculated 갱신."],
        safeguards: ["REQUESTED/PROCESSING/FAILED 환불은 차감에서 배제된다. Refund는 Payment당 1건(unique)이라 중복 차감 위험이 없다."],
        whySequence: [], ifFails: [],
        fact: ["soft delete된 TimeSlot도 이 쿼리에서 deletedAt 필터를 걸지 않아 과거 정산에 계속 포함된다(의도된 동작, 테스트로 확인됨)."]
      }
    },
    {
      id: "es-response", layer: "Response", title: "HTTP 200 (총액 응답)",
      filePath: "src/main/java/com/bobfull/payment/service/SettlementQueryService.java",
      className: "SettlementQueryService", methodName: "getExpectedSettlement", codeType: "actual",
      code: `return new ExpectedSettlementResponse(paid, refunded, paid.subtract(refunded));`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SettlementQueryService.java:76 / 테스트: SettlementControllerWebTest.OWNER가_지급예정금액을_조회한다",
      httpResponse: `200 OK\n{ "success": true, "data": { "totalPaidAmount":.., "totalRefundedAmount":.., "expectedSettlementAmount":.. } }`,
      stateChanges: {}, explanation: { whatRuns: ["결제 합계·환불 합계·순 지급예정액을 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: { ownershipValid: "확인 전", amountsCalculated: "계산 전" },
    groups: makeGroups(steps, [
      { title: "조회 요청 접수", count: 2 },
      { title: "소유권 검증", count: 1 },
      { title: "지급예정액 계산", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["ownershipValid", "amountsCalculated"]
  };
}

function settlementListSteps() {
  const steps = [
    {
      id: "sl-http", layer: "HTTP", title: "정산 목록(예약별) 조회 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: `GET /api/owner/restaurants/{restaurantId}/settlements/reservations?startDate=2026-07-01&endDate=2026-07-31&page=0&size=20`,
      httpResponse: null, externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SettlementController.java:40-50",
      stateChanges: {}, explanation: { whatRuns: ["같은 기간의 예약별 지급예정 내역을 페이지로 조회한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "sl-ownership", layer: "Domain", title: "소유권 검증 + 기간 범위 계산(공통)",
      filePath: "src/main/java/com/bobfull/payment/service/SettlementQueryService.java",
      className: "SettlementQueryService", methodName: "getReservationSettlements", codeType: "actual",
      code: `validateOwnership(ownerMemberId, restaurantId);\nDateRange range = dateRange(startDate, endDate);\n// 총액 조회와 동일한 validateOwnership·dateRange를 공유한다`,
      externalCall: "없음", repositoryAction: "RestaurantRepository 조회", error: null,
      evidence: "SettlementQueryService.java:79-84, 146-152 / 테스트: SettlementQueryServiceTest.삭제된_TimeSlot의_총액과_예약별목록_지급예정액은_같은_범위를_사용한다",
      stateChanges: { ownershipValid: "확인 전 → 확인됨" },
      explanation: {
        whatRuns: ["총액 조회와 완전히 같은 validateOwnership/dateRange 메서드를 공유해 조회 범위 일치를 보장한다."],
        whatChanged: ["ownershipValid 갱신."], safeguards: [], whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "sl-query", layer: "Repository", title: "예약별 정산 목록 조회",
      filePath: "src/main/java/com/bobfull/reservation/repository/ReservationRepository.java",
      className: "ReservationRepository", methodName: "findSettlementReservations", codeType: "actual",
      code: `Page<Reservation> reservations = reservationRepository.findSettlementReservations(\n        restaurantId, range.startAt(), range.endAt(), pageable);`,
      externalCall: "없음", repositoryAction: "ReservationRepository.findSettlementReservations — SELECT, MySQL, 페이징", error: null,
      evidence: "ReservationRepository.java:29-38, SettlementQueryService.java:85-92 / 테스트: SettlementControllerWebTest.OWNER가_예약별지급예정내역을_조회한다",
      stateChanges: { listLoaded: "조회 전 → 조회됨" },
      explanation: {
        whatRuns: ["기간 내 예약 목록을 페이징으로 조회한 뒤, 각 예약별로 amountsByReservation(총액과 동일한 paidAt/COMPLETED 필터)을 계산해 매핑한다."],
        whatChanged: ["listLoaded 갱신."], safeguards: [], whySequence: [], ifFails: [], fact: []
      }
    },
    {
      id: "sl-response", layer: "Response", title: "HTTP 200 (예약별 목록 페이지 응답)",
      filePath: "src/main/java/com/bobfull/payment/controller/SettlementController.java",
      className: "SettlementController", methodName: "getReservationSettlements", codeType: "actual",
      code: `return ApiResponse.success(settlementQueryService.getReservationSettlements(\n        authMember.id(), restaurantId, startDate, endDate, pageable));`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SettlementController.java:48-49",
      httpResponse: `200 OK\n{ "success": true, "data": { "content":[{"reservationId":..,"diningSessionAt":..,"totalPaidAmount":..,"totalRefundedAmount":..,"expectedSettlementAmount":..}], "totalElements":.. } }`,
      stateChanges: {}, explanation: { whatRuns: ["예약별 지급예정액 목록을 페이지 형태로 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    }
  ];
  return {
    steps,
    initialState: { ownershipValid: "확인 전", listLoaded: "조회 전" },
    groups: makeGroups(steps, [
      { title: "조회 요청 접수", count: 1 },
      { title: "소유권·기간 검증", count: 1 },
      { title: "예약별 목록 조회", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["ownershipValid", "listLoaded"]
  };
}

function settlementDetailSteps() {
  const steps = [
    {
      id: "sd-http", layer: "HTTP", title: "정산 상세(예약 1건) 조회 요청 도착",
      filePath: null, className: null, methodName: null, codeType: "framework", code: null,
      httpRequest: `GET /api/owner/settlements/reservations/{reservationId}`, httpResponse: null,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SettlementController.java:52-58",
      stateChanges: {}, explanation: { whatRuns: ["특정 예약 1건의 결제·환불 이력과 지급예정액 상세를 조회한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: [] }
    },
    {
      id: "sd-lookup", layer: "Service", title: "Reservation→TimeSlot→SharedTable 경유 소유권 검증",
      filePath: "src/main/java/com/bobfull/payment/service/SettlementQueryService.java",
      className: "SettlementQueryService", methodName: "getReservationSettlement", codeType: "actual",
      code:
`Reservation reservation = reservationRepository.findById(reservationId)
        .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
TimeSlot slot = timeSlotRepository.findById(reservation.getTimeSlotId())
        .orElseThrow(...);
SharedTable table = sharedTableRepository.findById(slot.getSharedTableId())
        .orElseThrow(...);
validateOwnership(ownerMemberId, table.getRestaurantId());`,
      externalCall: "없음", repositoryAction: "Reservation/TimeSlot/SharedTable 조회 — SELECT, MySQL (deletedAt 필터 없음)", error: null,
      evidence: "SettlementQueryService.java:96-103 / 테스트: SettlementQueryServiceTest.타인식당의_예약상세정산은_403을_반환한다",
      stateChanges: { ownershipValid: "확인 전 → 확인됨" },
      explanation: {
        whatRuns: ["예약→회차→테이블 경로로 식당 ID를 역추적해 소유권을 검증한다."],
        whatChanged: ["ownershipValid 갱신."],
        safeguards: [],
        whySequence: [],
        ifFails: ["예약이 없으면 404, 소유자가 아니면 403"],
        fact: ["이 경로는 TimeSlot의 findById(soft-delete 무필터)를 쓴다 — 삭제된 회차의 예약 상세도 계속 조회 가능(테스트로 확인)."]
      }
    },
    {
      id: "sd-history", layer: "Repository", title: "결제·환불 이력 조회",
      filePath: "src/main/java/com/bobfull/payment/service/SettlementQueryService.java",
      className: "SettlementQueryService", methodName: "getReservationSettlement", codeType: "actual",
      code: `List<Payment> payments = paymentRepository.findAllByReservationIdAndPaidAtIsNotNull(reservationId);\nList<Refund> refunds = refundRepository.findAllByPayment_ReservationId(reservationId);\nAmounts amounts = amounts(payments, refunds); // COMPLETED 환불만 차감`,
      externalCall: "없음", repositoryAction: "PaymentRepository/RefundRepository 조회 — SELECT, MySQL", error: null,
      evidence: "SettlementQueryService.java:104-106, 131-136 / 테스트: SettlementQueryServiceTest.삭제된_TimeSlot의_예약상세도_결제환불내역과_지급예정액을_반환한다",
      stateChanges: { historyLoaded: "조회 전 → 조회됨" },
      explanation: {
        whatRuns: ["이 예약에 연결된 paidAt 있는 결제 전부와 환불 전부를 조회하고, 순액(paid-refunded, COMPLETED만 차감)을 계산한다."],
        whatChanged: ["historyLoaded 갱신."],
        safeguards: [],
        whySequence: [],
        ifFails: [],
        fact: ["상세 응답의 refunds 목록에는 상태와 무관하게 모든 환불이 나열되지만, 금액 계산에는 COMPLETED만 반영된다 — 이력 노출과 금액 계산 기준이 분리되어 있다."]
      }
    },
    {
      id: "sd-response", layer: "Response", title: "HTTP 200 (상세 응답)",
      filePath: "src/main/java/com/bobfull/payment/service/SettlementQueryService.java",
      className: "SettlementQueryService", methodName: "getReservationSettlement", codeType: "actual",
      code: `return new SettlementReservationDetailResponse(reservationId, amounts.expected(), paymentItems, refundItems);`,
      externalCall: "없음", repositoryAction: "없음", error: null,
      evidence: "SettlementQueryService.java:107-111 — 다만 이 200 성공 흐름을 Controller 레벨(WebTest)로 직접 검증하는 happy-path 테스트는 확인되지 않았고, Service 레벨 테스트로만 근거가 있다.",
      httpResponse: `200 OK\n{ "success": true, "data": { "reservationId":..,"expectedSettlementAmount":..,"payments":[...],"refunds":[...] } }`,
      stateChanges: {}, explanation: { whatRuns: ["예약 1건의 결제·환불 상세와 지급예정액을 응답한다."], whatChanged: [], safeguards: [], whySequence: [], ifFails: [], fact: ["Controller 레벨 200 happy-path WebTest가 없어 '테스트 근거 제한' 상태로 분류했다."] }
    }
  ];
  return {
    steps,
    initialState: { ownershipValid: "확인 전", historyLoaded: "조회 전" },
    groups: makeGroups(steps, [
      { title: "조회 요청 접수", count: 1 },
      { title: "소유권 검증", count: 1 },
      { title: "결제·환불 이력 조회", count: 1 },
      { title: "HTTP 응답", count: 1 }
    ]),
    headline: ["ownershipValid", "historyLoaded"]
  };
}

/* ------------------------------------------------------------------ */
/* Chapter / UserAction 레지스트리                                       */
/* ------------------------------------------------------------------ */

const CHAPTERS = [
  {
    id: "owner-setup", title: "사장님 준비", tabLabel: "Ch1. 사장님 준비",
    actorLabel: "사장님", actorCode: "OWNER", status: "connected",
    purpose: "사장님이 로그인 후 식당·테이블·회차를 등록해 손님을 받을 준비를 하는 여정입니다.",
    userActions: [
      {
        id: "owner-login", title: "사장님 로그인", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "로그인 요청 보내기",
        inputs: [{ label: "이메일", value: "owner@bobfull.com" }, { label: "비밀번호", value: "********(마스킹)" }],
        variants: { DEFAULT: () => loginSteps("사장님", "OWNER", "owner@bobfull.com") }, variantLabels: {}, defaultVariant: "DEFAULT"
      },
      {
        id: "restaurant-register", title: "식당 등록", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "식당 등록 요청 보내기",
        inputs: [{ label: "식당명", value: "밥풀식당" }, { label: "주소", value: "서울시 ..." }, { label: "카테고리", value: "한식" }, { label: "1인 예약금", value: "10,000원" }],
        variants: { DEFAULT: restaurantRegisterSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      },
      {
        id: "table-register", title: "테이블 등록", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "테이블 등록 요청 보내기",
        inputs: [{ label: "대상 식당ID", value: "1" }, { label: "정원(capacity)", value: "4 (허용값: 2/4/6/8)" }],
        variants: { DEFAULT: tableRegisterSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      },
      {
        id: "session-register", title: "회차 등록", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "회차 등록 요청 보내기",
        inputs: [{ label: "대상 테이블ID", value: "1" }, { label: "시작 시각", value: "2026-08-01 19:00" }, { label: "종료 시각", value: "2026-08-01 21:00" }],
        variants: { DEFAULT: sessionRegisterSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      }
    ]
  },
  {
    id: "user-explore", title: "사용자 탐색", tabLabel: "Ch2. 사용자 탐색",
    actorLabel: "일반 사용자", actorCode: "MEMBER", status: "connected",
    purpose: "일반 사용자가 로그인 후 식당을 검색·조회하고 예약 가능한 회차를 찾는 여정입니다.",
    userActions: [
      {
        id: "member-login", title: "사용자 로그인", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "로그인 요청 보내기",
        inputs: [{ label: "이메일", value: "user@bobfull.com" }, { label: "비밀번호", value: "********(마스킹)" }],
        variants: { DEFAULT: () => loginSteps("일반 사용자", "MEMBER", "user@bobfull.com") }, variantLabels: {}, defaultVariant: "DEFAULT"
      },
      {
        id: "restaurant-search", title: "식당 검색", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "검색 요청 보내기",
        inputs: [{ label: "키워드", value: "합석" }, { label: "카테고리", value: "한식" }, { label: "날짜/시간", value: "2026-08-01 19:00" }],
        variants: { DEFAULT: restaurantSearchSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      },
      {
        id: "restaurant-detail", title: "식당 상세 조회", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "상세 조회 요청 보내기",
        inputs: [{ label: "대상 식당ID", value: "1" }],
        variants: { DEFAULT: restaurantDetailSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      },
      {
        id: "session-search", title: "예약 가능 회차 조회", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "회차 조회 요청 보내기",
        inputs: [{ label: "대상 식당ID", value: "1" }, { label: "날짜", value: "2026-08-01" }, { label: "인원수(선택)", value: "2" }],
        variants: { DEFAULT: sessionSearchSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      }
    ]
  },
  {
    id: "reservation-payment-prepare", title: "예약·결제 준비", tabLabel: "Ch3. 예약·결제 준비",
    actorLabel: "일반 사용자", actorCode: "MEMBER", status: "connected",
    purpose: "일반 사용자가 CREATE(새 모임)/JOIN(기존 모임 참여)을 선택해 예약금을 계산하고 READY Payment를 발급받는 여정입니다.",
    userActions: [
      {
        id: "reservation-prepare", title: "예약·결제 준비", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "예약·결제 준비 요청 보내기",
        inputs: [{ label: "대상(TimeSlot 또는 Reservation ID)", value: "CREATE=TimeSlot ID / JOIN=Reservation ID" }, { label: "인원수(partySize)", value: "CREATE=3 / JOIN=1" }],
        variants: { CREATE: () => reservationPrepareSteps("CREATE"), JOIN: () => reservationPrepareSteps("JOIN") },
        variantLabels: { CREATE: "CREATE (새 모임 생성)", JOIN: "JOIN (기존 모임 참여)" }, defaultVariant: "CREATE"
      }
    ]
  },
  {
    id: "payment-completion", title: "결제 완료·예약 확정", tabLabel: "Ch4. 결제 완료·예약 확정",
    actorLabel: "일반 사용자", actorCode: "MEMBER", status: "connected",
    purpose: "PortOne 결제 검증부터 트랜잭션·락·상태 전이·예약 확정까지, BobFull V1의 가장 핵심적인 백엔드 흐름입니다.",
    userActions: [
      {
        id: "success", title: "정상 완료", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "결제 완료 요청 보내기",
        inputs: [{ label: "paymentId", value: "READY 상태의 실제 결제 ID" }, { label: "외부 결제 상태(PortOne)", value: "PAID" }, { label: "금액/통화", value: "일치(KRW)" }],
        variants: { CREATE: () => successSteps("CREATE"), JOIN: () => successSteps("JOIN") },
        variantLabels: { CREATE: "CREATE (새 모임 생성)", JOIN: "JOIN (기존 모임 참여)" }, defaultVariant: "CREATE"
      },
      {
        id: "duplicate", title: "중복 요청", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "결제 완료 요청 다시 보내기",
        inputs: [{ label: "paymentId", value: "이미 처리된(또는 처리 중인) 결제 ID" }],
        variants: { PRE_LOCK: duplicatePreLockSteps, POST_LOCK: duplicatePostLockSteps },
        variantLabels: { PRE_LOCK: "락 이전 PAID 확인", POST_LOCK: "락 이후 경합 중 PAID 확인" }, defaultVariant: "PRE_LOCK"
      },
      {
        id: "expired", title: "내부 만료", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "결제 완료 요청 보내기(만료 임박)",
        inputs: [{ label: "paymentId", value: "expiresAt이 이미 지난 결제 ID" }],
        variants: { DEFAULT: expiredSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      },
      {
        id: "rollback", title: "저장 실패 Rollback", status: "connected", statusLabel: "실행 가능(테스트 결함 주입 기반)",
        primaryActionLabel: "결제 완료 요청 보내기(저장 실패 재현)",
        inputs: [{ label: "paymentId", value: "READY 상태의 결제 ID" }],
        variants: { DEFAULT: rollbackSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      }
    ]
  },
  {
    id: "reservation-settlement-check", title: "예약·정산 확인", tabLabel: "Ch5. 예약·정산 확인",
    actorLabel: "사장님", actorCode: "OWNER", status: "connected",
    purpose: "사장님이 자신의 식당 예약 현황과 지급 예정 정산 금액을 확인하는 여정입니다.",
    userActions: [
      {
        id: "owner-reservation-status", title: "예약 현황 확인", status: "unsupported", statusLabel: "백엔드 미구현",
        unsupportedNote:
          "src/main/java/com/bobfull/reservation 아래에 사장님이 회차별 참여 인원·예약 상태(ReservationStatus/RecruitmentStatus)를 확인하는 API가 존재하지 않습니다. " +
          "사장님 전용 회차 목록 조회(GET /api/owner/restaurants/{restaurantId}/dining-sessions, DiningSessionController.getOwnerDiningSessions)는 있지만 응답에 참여 인원이 포함되지 않고, " +
          "참여 인원이 포함된 고객용 검색(GET /api/reservations/search)에는 소유권 검증이 없습니다. 추측으로 시나리오를 만들지 않고 미구현으로 표시합니다.",
        variants: null
      },
      {
        id: "expected-settlement", title: "지급 예정 총액 조회", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "총액 조회 요청 보내기",
        inputs: [{ label: "대상 식당ID", value: "1" }, { label: "시작일/종료일", value: "2026-07-01 ~ 2026-07-31" }],
        variants: { DEFAULT: expectedSettlementSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      },
      {
        id: "settlement-list", title: "정산 목록(예약별) 조회", status: "connected", statusLabel: "실행 가능",
        primaryActionLabel: "목록 조회 요청 보내기",
        inputs: [{ label: "대상 식당ID", value: "1" }, { label: "시작일/종료일", value: "2026-07-01 ~ 2026-07-31" }],
        variants: { DEFAULT: settlementListSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      },
      {
        id: "settlement-detail", title: "정산 상세(예약 1건) 조회", status: "limited", statusLabel: "테스트 근거 제한",
        primaryActionLabel: "상세 조회 요청 보내기",
        inputs: [{ label: "대상 예약ID", value: "1" }],
        variants: { DEFAULT: settlementDetailSteps }, variantLabels: {}, defaultVariant: "DEFAULT"
      }
    ]
  }
];

const LAYER_ICONS = {
  HTTP: "\u{1F310}", Controller: "\u{1F6AA}", Service: "\u{2699}", "External Port": "\u{1F50C}",
  Transaction: "\u{1F4D2}", Lock: "\u{1F512}", Domain: "\u{1F9E9}", Repository: "\u{1F5C4}",
  Commit: "\u{2705}", Rollback: "\u{21A9}", Response: "\u{1F4E4}"
};

const STATUS_BADGES = {
  connected: { label: "실행 가능", cls: "status-connected" },
  limited: { label: "테스트 근거 제한", cls: "status-limited" },
  pending: { label: "Flow Lab 상세 연결 준비 중", cls: "status-pending" },
  unsupported: { label: "백엔드 미구현", cls: "status-unsupported" }
};

/* ------------------------------------------------------------------ */
/* 엔진 상태                                                             */
/* ------------------------------------------------------------------ */

const engine = {
  chapterKey: CHAPTERS[0].id,
  actionKey: CHAPTERS[0].userActions[0].id,
  variantKey: CHAPTERS[0].userActions[0].defaultVariant,
  stepIndex: -1,
  timerId: null,
  showFullState: false,
  detailTab: "evidence",
  cache: {}
};

function currentChapter() {
  return CHAPTERS.find((c) => c.id === engine.chapterKey);
}

function currentAction() {
  return currentChapter().userActions.find((a) => a.id === engine.actionKey);
}

function currentVariantData() {
  const action = currentAction();
  if (!action.variants) return null;
  const key = `${engine.chapterKey}::${engine.actionKey}::${engine.variantKey}`;
  if (!engine.cache[key]) {
    engine.cache[key] = action.variants[engine.variantKey]();
  }
  return engine.cache[key];
}

function computeStateAtStep(index) {
  const data = currentVariantData();
  if (!data) return {};
  let state = Object.assign({}, data.initialState);
  for (let i = 0; i <= index; i += 1) {
    const changes = data.steps[i].stateChanges || {};
    Object.keys(changes).forEach((k) => {
      if (changes[k] !== undefined) state[k] = changes[k];
    });
  }
  return state;
}

function changedKeysAtStep(index) {
  const data = currentVariantData();
  if (!data || index < 0) return [];
  const changes = data.steps[index].stateChanges || {};
  return Object.keys(changes).filter((k) => changes[k] !== undefined);
}

function stopAutoPlay() {
  if (engine.timerId !== null) {
    clearInterval(engine.timerId);
    engine.timerId = null;
  }
}

function setChapter(chapterKey) {
  stopAutoPlay();
  engine.chapterKey = chapterKey;
  const chapter = currentChapter();
  engine.actionKey = chapter.userActions[0].id;
  const action = currentAction();
  engine.variantKey = action.defaultVariant || null;
  engine.stepIndex = -1;
  engine.showFullState = false;
  engine.detailTab = "evidence";
  renderAll();
}

function setAction(actionKey) {
  stopAutoPlay();
  engine.actionKey = actionKey;
  const action = currentAction();
  engine.variantKey = action.defaultVariant || null;
  engine.stepIndex = -1;
  engine.showFullState = false;
  engine.detailTab = "evidence";
  renderAll();
}

function setVariant(variantKey) {
  stopAutoPlay();
  engine.variantKey = variantKey;
  engine.stepIndex = -1;
  engine.showFullState = false;
  engine.detailTab = "evidence";
  renderAll();
}

function goToStep(index) {
  const data = currentVariantData();
  if (!data) return;
  const clamped = Math.max(-1, Math.min(index, data.steps.length - 1));
  engine.stepIndex = clamped;
  renderAll();
}

function runFromStart() {
  stopAutoPlay();
  engine.stepIndex = 0;
  renderAll();
}

function nextStep() {
  const data = currentVariantData();
  if (!data) return;
  if (engine.stepIndex >= data.steps.length - 1) {
    stopAutoPlay();
    return;
  }
  goToStep(engine.stepIndex + 1);
}

function prevStep() {
  goToStep(engine.stepIndex - 1);
}

function resetAll() {
  stopAutoPlay();
  engine.stepIndex = -1;
  renderAll();
}

function toggleAutoPlay() {
  if (engine.timerId !== null) {
    stopAutoPlay();
    renderAll();
    return;
  }
  if (engine.stepIndex < 0) engine.stepIndex = 0;
  renderAll();
  engine.timerId = setInterval(() => {
    const data = currentVariantData();
    if (!data || engine.stepIndex >= data.steps.length - 1) {
      stopAutoPlay();
      renderAll();
      return;
    }
    engine.stepIndex += 1;
    renderAll();
  }, 1400);
}

function toggleFullState() {
  engine.showFullState = !engine.showFullState;
  renderAll();
}

function setDetailTab(tab) {
  engine.detailTab = tab;
  renderAll();
}

/* ------------------------------------------------------------------ */
/* 렌더링 유틸                                                            */
/* ------------------------------------------------------------------ */

function el(tag, attrs, children) {
  const node = document.createElement(tag);
  if (attrs) {
    Object.keys(attrs).forEach((key) => {
      if (key === "class") node.className = attrs[key];
      else if (key === "text") node.textContent = attrs[key];
      else node.setAttribute(key, attrs[key]);
    });
  }
  (children || []).forEach((child) => { if (child) node.appendChild(child); });
  return node;
}

function codeTypeBadge(codeType) {
  if (codeType === "pseudo") return el("span", { class: "badge badge-pseudo", text: "의사 코드" });
  if (codeType === "framework") return el("span", { class: "badge badge-framework", text: "프레임워크 동작(코드 없음)" });
  return el("span", { class: "badge badge-fact", text: "실제 코드" });
}

function renderCodeBlock(code) {
  const pre = el("pre", { class: "code-block" });
  pre.textContent = code;
  return pre;
}

function renderCurrentCodePanel() {
  const body = document.getElementById("code-col");
  body.innerHTML = "";
  const action = currentAction();
  if (!action.variants || engine.stepIndex === -1) {
    body.appendChild(el("p", { class: "placeholder", text: "실행을 누르거나 세부 단계를 선택하면 현재 실행 코드가 표시됩니다." }));
    return;
  }

  const step = currentVariantData().steps[engine.stepIndex];
  const meta = el("div", { class: "code-meta" }, [codeTypeBadge(step.codeType)]);
  if (step.className) meta.appendChild(el("span", { text: `${step.className}${step.methodName ? "." + step.methodName + "()" : ""}` }));
  body.appendChild(meta);
  if (step.filePath) body.appendChild(el("div", { class: "code-meta file-path", text: step.filePath }));
  if (step.code) body.appendChild(renderCodeBlock(step.code));
  else body.appendChild(el("p", { class: "placeholder", text: "이 단계는 애플리케이션 소스 코드가 아니라 프레임워크/DB의 표준 동작입니다." }));
}

function badgeForKind(kind) {
  if (kind === "interpretation") return "badge-interpretation";
  if (kind === "improvement") return "badge-improvement";
  return "badge-fact";
}

function designBlock(heading, items, kind) {
  if (!items || items.length === 0) return null;
  const block = el("div", { class: "design-block" });
  const headingEl = el("div", { class: "design-heading" });
  headingEl.appendChild(el("span", { class: `badge ${badgeForKind(kind)}`, text: kind === "improvement" ? "개선" : kind === "interpretation" ? "해석" : "사실" }));
  headingEl.appendChild(el("span", { text: heading }));
  block.appendChild(headingEl);
  const ul = el("ul");
  items.forEach((item) => ul.appendChild(el("li", { text: item })));
  block.appendChild(ul);
  return block;
}

function evidenceRow(label, value, isPre) {
  if (value === undefined || value === null || value === "") return null;
  const row = el("div", { class: "evidence-row" });
  row.appendChild(el("span", { class: "evidence-label", text: label }));
  if (isPre) {
    const pre = el("pre");
    pre.textContent = value;
    row.appendChild(pre);
  } else {
    row.appendChild(el("span", { class: "evidence-value", text: value }));
  }
  return row;
}

/* ------------------------------------------------------------------ */
/* 렌더링: 헤더 / 챕터 네비 / 히어로                                         */
/* ------------------------------------------------------------------ */

function renderHeader() {
  document.getElementById("top-meta").textContent =
    `${CONFIG.branch} · ${CONFIG.sha.slice(0, 8)} · ${CONFIG.generatedDate}`;
}

function renderChapterNav() {
  const nav = document.getElementById("chapter-nav");
  nav.innerHTML = "";
  CHAPTERS.forEach((ch) => {
    const isSelected = ch.id === engine.chapterKey;
    const btn = el("button", { type: "button", role: "tab", "aria-selected": String(isSelected), class: "chapter-tab" }, [
      el("span", { class: "chapter-tab-label", text: ch.tabLabel })
    ]);
    btn.addEventListener("click", () => setChapter(ch.id));
    nav.appendChild(btn);
  });
}

function renderActionTabs() {
  const wrap = document.getElementById("action-tabs");
  wrap.innerHTML = "";
  const chapter = currentChapter();
  if (chapter.userActions.length <= 1) { wrap.style.display = "none"; return; }
  wrap.style.display = "";
  chapter.userActions.forEach((action) => {
    const isSelected = action.id === engine.actionKey;
    const badge = STATUS_BADGES[action.status];
    const btn = el("button", { type: "button", role: "tab", "aria-selected": String(isSelected), class: "action-tab" }, [
      el("span", { text: action.title }),
      el("span", { class: `status-dot ${badge.cls}`, title: badge.label })
    ]);
    btn.addEventListener("click", () => setAction(action.id));
    wrap.appendChild(btn);
  });
}

/* ------------------------------------------------------------------ */
/* 렌더링: 역할 조작 영역                                                   */
/* ------------------------------------------------------------------ */

function renderRolePanel() {
  const body = document.getElementById("role-panel-body");
  body.innerHTML = "";
  const chapter = currentChapter();
  const action = currentAction();
  const badge = STATUS_BADGES[action.status];

  const statusRow = el("div", { class: "role-status-row" }, [
    el("span", { class: `badge status-badge ${badge.cls}`, text: badge.label }),
    el("span", { class: "role-action-title", text: action.title })
  ]);
  body.appendChild(statusRow);

  if (action.status === "unsupported" || !action.variants) {
    body.appendChild(el("p", { class: "unsupported-note", text: action.unsupportedNote || "이 기능은 백엔드에 실제 구현이 없어 실행할 수 없습니다." }));
    return;
  }

  if (action.status === "limited") {
    body.appendChild(el("p", { class: "limited-note", text: "백엔드 구현은 존재하지만 Controller 레벨 200 happy-path 테스트 근거가 제한적입니다(아래 설계 설명 탭 참고)." }));
  }

  const inputsWrap = el("div", { class: "role-inputs" });
  (action.inputs || []).forEach((input) => {
    const row = el("div", { class: "role-input-row" }, [
      el("span", { class: "role-input-label", text: input.label }),
      el("span", { class: "role-input-value", text: input.value })
    ]);
    inputsWrap.appendChild(row);
  });
  body.appendChild(inputsWrap);
  body.appendChild(el("p", { class: "sim-note", text: "위 입력값은 Flow Lab 내부 가상 테스트 데이터입니다. 실제 서버 요청은 전송되지 않습니다." }));

  const variantKeys = Object.keys(action.variants);
  if (variantKeys.length > 1) {
    const toggle = el("div", { class: "variant-toggle", role: "radiogroup" });
    variantKeys.forEach((key) => {
      const isSelected = key === engine.variantKey;
      const label = action.variantLabels[key] || key;
      const btn = el("button", { type: "button", role: "radio", "aria-checked": String(isSelected), text: label });
      btn.addEventListener("click", () => setVariant(key));
      toggle.appendChild(btn);
    });
    body.appendChild(toggle);
  }

  const runBtn = el("button", { type: "button", class: "primary-action-btn", text: action.primaryActionLabel });
  runBtn.addEventListener("click", runFromStart);
  body.appendChild(runBtn);
}

/* ------------------------------------------------------------------ */
/* 렌더링: 실행 제어(VCR)                                                  */
/* ------------------------------------------------------------------ */

function renderControls() {
  const action = currentAction();
  const footer = document.getElementById("bottom-bar");
  if (!action.variants) { footer.style.display = "none"; return; }
  footer.style.display = "";
  const data = currentVariantData();
  const last = data.steps.length - 1;
  document.getElementById("btn-prev").disabled = engine.stepIndex <= -1;
  document.getElementById("btn-next").disabled = engine.stepIndex >= last;
  document.getElementById("btn-auto").textContent = engine.timerId !== null ? "자동 실행 중..." : "자동 실행";
  document.getElementById("btn-pause").disabled = engine.timerId === null;
  document.getElementById("step-counter").textContent =
    engine.stepIndex === -1 ? `단계 0/${data.steps.length} (시작 전)` : `단계 ${engine.stepIndex + 1}/${data.steps.length}`;
  const timeline = document.getElementById("timeline");
  timeline.innerHTML = "";
  data.steps.forEach((step, index) => {
    const dot = el("button", { type: "button", title: `${index + 1}. ${step.title}`, class: `timeline-step${index < engine.stepIndex ? " is-done" : ""}${index === engine.stepIndex ? " is-current" : ""}` });
    dot.addEventListener("click", () => goToStep(index));
    timeline.appendChild(dot);
  });
}

/* ------------------------------------------------------------------ */
/* 렌더링: 주체 기반 실행 흐름(Swimlane)                                     */
/* ------------------------------------------------------------------ */

function renderSwimlane() {
  // 큰 흐름과 세부 단계를 같은 데이터에서 파생한다. 별도의 시나리오 데이터를 만들지 않는다.
  renderFlowchart();
}

/* ------------------------------------------------------------------ */
/* 렌더링: 세로 플로우차트(단계별 강조 + 분기 표시)                             */
/* ------------------------------------------------------------------ */

function renderFlowchart() {
  const section = document.getElementById("flowchart-section");
  const wrap = document.getElementById("swimflow");
  const substeps = document.getElementById("substeps");
  wrap.innerHTML = "";
  substeps.innerHTML = "";
  const action = currentAction();
  if (!action.variants) { section.style.display = "none"; return; }
  section.style.display = "";
  const data = currentVariantData();

  const chapter = currentChapter();
  const lanes = [chapter.actorCode, "BACKEND", "PORTONE", "MYSQL"];
  const laneLabels = { OWNER: "사장님", MEMBER: "사용자", BACKEND: "BobFull Backend", PORTONE: "PortOne", MYSQL: "MySQL" };
  lanes.forEach((lane, index) => wrap.appendChild(el("span", { class: "lane-heading", style: `left:calc(${index * 25}% + 8px)`, text: laneLabels[lane] })));
  data.groups.forEach((group, gi) => {
    const groupSteps = data.steps.slice(group.startIdx, group.endIdx + 1);
    const anchorIndex = engine.stepIndex >= group.startIdx && engine.stepIndex <= group.endIdx ? engine.stepIndex : group.startIdx;
    const anchor = data.steps[anchorIndex];
    const target = deriveActorTarget(anchor, chapter.actorCode).target;
    const laneIndex = Math.max(0, lanes.indexOf(target));
    const isCurrent = engine.stepIndex >= group.startIdx && engine.stepIndex <= group.endIdx;
    const isDone = engine.stepIndex > group.endIdx;
    const node = el("button", { type: "button", class: `flow-group${isCurrent ? " is-current" : ""}${isDone ? " is-done" : ""}`, style: `grid-column:${laneIndex + 1}` }, [
      el("span", { class: "flow-group-layer", text: `${gi + 1}. ${anchor.layer}` }),
      el("span", { class: "flow-group-title", text: group.title }),
      el("span", { class: "flow-group-step", text: `${groupSteps.length}개 세부 단계 · ${isCurrent ? anchor.title : groupSteps[0].title}` })
    ]);
    node.addEventListener("click", () => goToStep(group.startIdx));
    wrap.appendChild(node);
    const branches = groupSteps.flatMap((step) => step.branches || []);
    branches.forEach((branch) => wrap.appendChild(el("div", { class: "flow-branch-stub", style: `grid-column:${laneIndex + 1}`, text: `분기: ${branch.label} — ${branch.note}` })));
  });
  data.groups.forEach((group, gi) => {
    substeps.appendChild(el("span", { class: "substep-group", text: `${gi + 1}.` }));
    for (let i = group.startIdx; i <= group.endIdx; i += 1) {
      const step = data.steps[i];
      const btn = el("button", { type: "button", class: `substep${i < engine.stepIndex ? " is-done" : ""}${i === engine.stepIndex ? " is-current" : ""}`, text: `${i + 1} ${step.title}` });
      btn.addEventListener("click", () => goToStep(i));
      substeps.appendChild(btn);
    }
  });
}

/* ------------------------------------------------------------------ */
/* 렌더링: 핵심 상태 / 전체 상태                                              */
/* ------------------------------------------------------------------ */

const STATE_LABELS = {
  externalPaymentStatus: "외부 결제 상태(PortOne)", paymentStatus: "Payment 상태", paidAt: "paidAt", expiresAt: "expiresAt",
  reservationId: "reservationId", participationId: "participationId", reservationStatus: "Reservation 상태",
  recruitmentStatus: "모집 상태", participantCreated: "Participant 생성 여부", currentParticipants: "현재 참여 인원",
  capacity: "정원", transactionState: "트랜잭션 상태", paymentLock: "Payment 락 상태", reservationLock: "Reservation 락 상태",
  timeSlotLock: "TimeSlot 락 상태", memberFound: "Member 조회", passwordValid: "비밀번호 검증", tokenIssued: "JWT 발급", role: "역할(role)",
  restaurantSaved: "Restaurant 저장", restaurantId: "restaurantId", ownershipValid: "소유권 검증", capacityValid: "정원 검증",
  tableSaved: "SharedTable 저장", tableId: "tableId", duplicateChecked: "중복 검증", sessionSaved: "TimeSlot 저장", sessionId: "sessionId",
  searchExecuted: "검색 실행", restaurantFound: "식당 조회", sessionsLoaded: "회차 조회", availableCapacityCalculated: "잔여좌석 계산",
  targetValidated: "대상 검증", amountCalculated: "예약금 계산", paymentSaved: "Payment 저장", amountsCalculated: "지급예정액 계산",
  listLoaded: "목록 조회", historyLoaded: "결제·환불 이력 조회"
};

function renderCoreState() {
  const body = document.getElementById("core-state-body");
  body.innerHTML = "";
  const action = currentAction();
  if (!action.variants) { document.getElementById("core-state").style.display = "none"; return; }
  document.getElementById("core-state").style.display = "";
  const data = currentVariantData();
  const state = computeStateAtStep(engine.stepIndex);
  const changed = changedKeysAtStep(engine.stepIndex);
  const headline = data.headline || [];
  const shown = [];
  changed.forEach((k) => { if (!shown.includes(k)) shown.push(k); });
  headline.forEach((k) => { if (!shown.includes(k) && shown.length < 5 && state[k] !== undefined) shown.push(k); });

  if (engine.stepIndex === -1) {
    body.appendChild(el("p", { class: "placeholder", text: "실행을 누르면 이 단계에서 바뀌는 핵심 상태가 여기 표시됩니다." }));
  } else if (shown.length === 0) {
    body.appendChild(el("p", { class: "placeholder", text: "이 단계에서는 표시할 핵심 상태 변경이 없습니다." }));
  } else {
    const grid = el("div", { class: "core-state-grid" });
    shown.forEach((key) => {
      const isChanged = changed.includes(key);
      const label = STATE_LABELS[key] || key;
      const value = state[key];
      const displayValue = value === null ? "null" : value === true ? "예" : value === false ? "아니오" : String(value);
      const cell = el("div", { class: `core-state-cell${isChanged ? " is-changed" : ""}` }, [
        el("span", { class: "state-label", text: label }),
        el("span", { class: "state-value", text: displayValue })
      ]);
      grid.appendChild(cell);
    });
    body.appendChild(grid);
  }

  const toggleBtn = el("button", { type: "button", class: "full-state-toggle", text: engine.showFullState ? "전체 상태 접기" : "전체 상태 보기" });
  toggleBtn.addEventListener("click", toggleFullState);
  body.appendChild(toggleBtn);

  if (engine.showFullState) {
    const fullGrid = el("div", { class: "state-grid" });
    Object.keys(state).forEach((key) => {
      const value = state[key];
      if (value === undefined) return;
      const isChanged = changed.includes(key);
      const label = STATE_LABELS[key] || key;
      const displayValue = value === null ? "null" : value === true ? "예" : value === false ? "아니오" : String(value);
      fullGrid.appendChild(el("div", { class: `state-cell${isChanged ? " is-changed" : ""}` }, [
        el("span", { class: "state-label", text: label }),
        el("span", { class: "state-value", text: displayValue })
      ]));
    });
    body.appendChild(fullGrid);
  }
}

/* ------------------------------------------------------------------ */
/* 렌더링: 상세 탭(증거 / 설계 / 성능)                                            */
/* ------------------------------------------------------------------ */

function renderDetailTabs() {
  const section = document.getElementById("detail-section");
  const action = currentAction();
  if (!action.variants) { section.style.display = "none"; return; }
  section.style.display = "";
  const wrap = document.getElementById("detail-tabs");
  wrap.innerHTML = "";
  const tabs = [["evidence", "실행 증거"], ["design", "설계 설명"], ["performance", "성능 관찰"]];
  tabs.forEach(([key, label]) => {
    const btn = el("button", { type: "button", role: "tab", "aria-selected": String(engine.detailTab === key), text: label });
    btn.addEventListener("click", () => setDetailTab(key));
    wrap.appendChild(btn);
  });
}

function renderDetailBody() {
  const action = currentAction();
  if (!action.variants) return;
  const body = document.getElementById("detail-body");
  body.innerHTML = "";
  if (engine.stepIndex === -1) {
    body.appendChild(el("p", { class: "placeholder", text: "실행을 누르고 단계를 선택하면 상세 정보가 표시됩니다." }));
    return;
  }
  const data = currentVariantData();
  const step = data.steps[engine.stepIndex];

  if (engine.detailTab === "evidence") {
    const rows = [
      evidenceRow("HTTP 요청", step.httpRequest, true),
      evidenceRow("HTTP 응답", step.httpResponse, true),
      evidenceRow("외부(PortOne) 호출 여부", step.externalCall, false),
      evidenceRow("Repository / JPA 동작 (예상 SQL 또는 락 동작 설명)", step.repositoryAction, false),
      evidenceRow("예외", step.error, false)
    ].filter(Boolean);
    if (rows.length === 0) body.appendChild(el("p", { class: "evidence-none", text: "이 단계에서 별도로 표시할 증거가 없습니다." }));
    else rows.forEach((r) => body.appendChild(r));
  } else if (engine.detailTab === "design") {
    const ex = step.explanation || {};
    const blocks = [
      designBlock("지금 무엇이 실행되는가", ex.whatRuns, "fact"),
      designBlock("무엇이 변경됐는가", ex.whatChanged, "fact"),
      designBlock("어떤 보호 장치가 작동했는가", ex.safeguards, "interpretation"),
      designBlock("왜 이 순서인가", ex.whySequence, "interpretation"),
      designBlock("실패하면 무엇이 실행되지 않는가", ex.ifFails, "fact"),
      designBlock("실제 구현 사실(근거·한계)", ex.fact, "fact"),
      designBlock("현재 제한사항·후속 개선", ex.futureNotes, "improvement")
    ].filter(Boolean);
    if (blocks.length === 0) body.appendChild(el("p", { class: "placeholder", text: "이 단계에는 별도 설계 설명이 없습니다." }));
    else blocks.forEach((b) => body.appendChild(b));
  } else if (engine.detailTab === "performance") {
    const state = computeStateAtStep(engine.stepIndex);
    const perf = derivePerformance(step, state);
    const rows = [
      ["작업 유형", perf.category],
      ["트랜잭션", perf.transactionScope],
      ["DB 락", perf.dbLock],
      ["쿼리 발생 여부", perf.queryInvolved],
      ["외부 호출 여부", perf.externalCallInvolved],
      ["병목 가능성", perf.bottleneckRisk],
      ["실제 소요시간", perf.measured ? `${perf.duration}ms` : "측정 전"],
      ["측정 근거", perf.measurementSource]
    ];
    const grid = el("div", { class: "perf-grid" });
    rows.forEach(([label, value]) => {
      grid.appendChild(el("div", { class: "perf-cell" }, [
        el("span", { class: "state-label", text: label }),
        el("span", { class: "state-value", text: value })
      ]));
    });
    body.appendChild(grid);
    const baseline = PERFORMANCE_BASELINES[action.id];
    if (baseline) {
      body.appendChild(el("div", { class: "perf-baseline" }, [
        el("strong", { text: "현재 V1 실행 관찰 — " + baseline.status }),
        el("p", { text: "전체 HTTP 응답시간: " + baseline.http }),
        el("p", { text: "DB 쿼리 수: " + baseline.queries }),
        el("p", { text: "기준: " + CONFIG.sha + " / " + baseline.measuredAt + " / 근거: " + baseline.source }),
        el("p", { text: "개선 후보: 회차별 반복 조회·정산 집계 방식은 실제 쿼리 수와 EXPLAIN 확인 후 향후 검토한다. Redis·인덱스·Projection은 현재 적용하지 않았다." }),
        el("p", { text: "향후 성능 비교: 개선 후 응답시간·DB 쿼리 수·K6·개선율 모두 측정 전" })
      ]));
    }
    body.appendChild(el("p", { class: "perf-disclaimer", text: "자동 실행의 재생 간격(약 1.4초)은 시뮬레이션 진행 속도이며 실제 API 실행 시간이 아닙니다." }));
  }
}

/* ------------------------------------------------------------------ */
/* 렌더링: 하단 설명                                                        */
/* ------------------------------------------------------------------ */

function renderActionDescription() {
  // 긴 여정 설명은 기본 화면 대신 "설계 설명" 탭의 단계별 근거로 제공한다.
}

/* ------------------------------------------------------------------ */
/* renderAll + 초기화                                                    */
/* ------------------------------------------------------------------ */

function renderAll() {
  renderHeader();
  renderChapterNav();
  renderActionTabs();
  renderRolePanel();
  renderControls();
  renderSwimlane();
  renderCurrentCodePanel();
  renderCoreState();
  renderDetailTabs();
  renderDetailBody();
  renderActionDescription();
}

document.getElementById("btn-run") && document.getElementById("btn-run").addEventListener("click", runFromStart);
document.getElementById("btn-next") && document.getElementById("btn-next").addEventListener("click", nextStep);
document.getElementById("btn-prev") && document.getElementById("btn-prev").addEventListener("click", prevStep);
document.getElementById("btn-auto") && document.getElementById("btn-auto").addEventListener("click", toggleAutoPlay);
document.getElementById("btn-pause") && document.getElementById("btn-pause").addEventListener("click", () => { stopAutoPlay(); renderControls(); });
document.getElementById("btn-reset") && document.getElementById("btn-reset").addEventListener("click", resetAll);

renderAll();
