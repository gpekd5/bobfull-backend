# Issue #27 Common 책임 축소 Evidence

## 검증 대상

- 공통 패키지에 있던 7개 도메인 ErrorCode를 각 도메인의 `domain/exception`으로 이동한다.
- `MemberNameMasker`를 목적이 드러나는 `common/privacy`로 이동한다.
- Chat 신고 검토의 낙관적 락 예외 처리를 `chat/presentation/exception`으로 이동한다.
- package declaration, import, 테스트 참조만 함께 정리하고 기존 계약과 동작을 유지한다.

## 유지할 기존 동작

- ErrorCode의 코드, HTTP status, 메시지
- `MemberNameMasker`의 마스킹 규칙과 Admin/OWNER 개인정보 노출 제한
- Chat 신고 검토 충돌의 `409 CHAT_ROOM_REPORT_ALREADY_REVIEWED` 응답
- API, DB schema와 Entity mapping, 비즈니스 정책, Transaction과 Lock
- 공용 Outbox, Monitoring, 공통 응답과 예외 계약

## 기준 코드

- Before SHA: `971709dcfa052aa492d8617d0f898e3d2d0c0157`
- 구현 SHA: `dacbff47eb89ca8801bbf4913b278d625de8f1a9`
- Sonar 회귀 수정 및 최종 After SHA: `567627ff7a0a69883bc9be64a6b1e0a73fc71d41`
- Base branch: `develop`
- 작업 branch: `refactor/27-common-responsibility`

## 환경 및 실행 조건

- Windows PowerShell, 저장소 Gradle Wrapper
- SonarQube Server `26.9.0.129388`, project key `bobfull-backend`
- Before와 After 모두 같은 로컬 SonarQube 서버, project key, 인증 환경을 사용했다.
- `SONAR_TOKEN`과 `SONAR_HOST_URL`의 존재 여부만 확인했으며 token 값은 출력하거나 기록하지 않았다.

## 측정 방법

### 구조와 참조

- `Get-ChildItem src/main/java/com/bobfull/common -Recurse -Filter '*.java'`로 Common production Java 수를 집계했다.
- Common 바로 아래 디렉터리를 기준으로 파일 경로를 그룹화해 하위 package별 Java 수를 집계했다.
- `rg -n 'com\.bobfull\.common\.exception\.(Chat|Image|Payment|Reservation|Restaurant|SharedTable|TimeSlot)ErrorCode' src/main/java src/test/java`로 기존 ErrorCode FQCN 잔존을 확인했다.
- `rg -n 'com\.bobfull\.common\.support\.MemberNameMasker' src/main/java src/test/java`로 기존 Masker FQCN 잔존을 확인했다.
- 각 Java 파일의 source root 상대 경로와 `package + class` FQCN을 비교해 package/path 불일치를 확인했다.
- rename-aware diff에서 package/import/FQCN 변경을 제외하고 production logic 변경을 확인했다.

### 동작 Guardrail

- 7개 ErrorCode는 package/import를 제외한 enum 본문을 Before/After로 비교했다.
- `MemberNameMasker`는 package를 제외한 구현 본문을 Before/After로 비교했다.
- `ObjectOptimisticLockingFailureException`의 handler 수, 반환 status, error code를 Before/After로 비교했다.
- `common/outbox`, API mapping, Entity mapping, Transaction/Lock 관련 변경 여부를 diff로 확인했다.

### 테스트와 build

```powershell
.\gradlew.bat :test `
  --tests "com.bobfull.common.privacy.MemberNameMaskerTest" `
  --tests "com.bobfull.common.exception.GlobalExceptionHandlingWebTest" `
  --tests "com.bobfull.admin.application.service.AdminModerationReportServiceTest" `
  --tests "com.bobfull.chat.presentation.exception.ChatExceptionHandlingWebTest" `
  --rerun-tasks

.\gradlew.bat compileJava compileTestJava --rerun-tasks
.\gradlew.bat clean build
```

Sonar 회귀 수정 후에는 직접 영향 테스트도 다시 실행했다.

```powershell
.\gradlew.bat :test `
  --tests "com.bobfull.payment.application.service.PaymentCompletionServiceTest" `
  --tests "com.bobfull.payment.application.service.PaymentWebhookCompensationLogTest" `
  --rerun-tasks
```

### SonarQube

```powershell
.\gradlew.bat clean classes testClasses sonar `
  "-Dsonar.host.url=$env:SONAR_HOST_URL" `
  "-Dsonar.token=$env:SONAR_TOKEN" `
  "-Dsonar.scm.revision=<검증 SHA>"
```

- 최초 Baseline은 구현 전에 확인한 변경 예상 Java 90개 경로를 대상으로 집계했다.
- compile 단계에서 wildcard import를 사용하던 기존 파일 7개에도 명시적 import 수정이 필요함을 확인했고, 새 handler/test 3개가 추가되어 최종 변경 범위는 Java 100개가 됐다.
- 최종 비교는 최초 90개 경로의 issue identity와, 뒤늦게 범위에 포함된 기존 7개 파일, 새 파일 3개를 분리했다.
- 이동된 issue는 `rule + type + severity + message`로 대응해 기존 issue 재키잉과 실제 신규 회귀를 구분했다.

## Before 결과

### Common 구조

| package | Java 파일 수 |
|---|---:|
| `common/exception` | 11 |
| `common/response` | 2 |
| `common/config` | 2 |
| `common/outbox` | 5 |
| `common/monitoring` | 2 |
| `common/support` | 1 |
| `common/entity` | 1 |
| `common/transaction` | 1 |
| 합계 | 25 |

| 측정 항목 | Before |
|---|---:|
| Common에 있는 도메인 ErrorCode | 7 |
| 기존 ErrorCode FQCN 참조 파일 | 74 |
| 기존 `MemberNameMasker` FQCN 참조 파일 | 5 |
| Common Outbox Java | 5 |
| 공통 handler의 Chat 낙관적 락 관련 일치 행 | 5 |

### 관련 테스트 Baseline

- 3 test classes, 16 tests
- failure 0, error 0, skipped 0

### SonarQube Baseline

- CE task: `c5c04566-41a1-4c80-a69b-b712c133d353` (`SUCCESS`)
- Analysis: `87d4933c-04f4-4aaa-9e0b-c763cb365930`
- 전체 project: 326 issues (`BUG 14`, `VULNERABILITY 1`, `CODE_SMELL 311`)
- 최초 비교 범위 Java 90개: 64 issues (`BUG 1`, `CODE_SMELL 63`)
- Severity: `CRITICAL 2`, `MAJOR 22`, `MINOR 40`
- Quality Gate: `ERROR` (기존 project 전체 issue 기준)

## 변경 내용

| 책임 | Before | After |
|---|---|---|
| Chat ErrorCode | `common/exception` | `chat/domain/exception` |
| Image ErrorCode | `common/exception` | `restaurant/image/domain/exception` |
| Payment ErrorCode | `common/exception` | `payment/domain/exception` |
| Reservation ErrorCode | `common/exception` | `reservation/domain/exception` |
| Restaurant ErrorCode | `common/exception` | `restaurant/restaurant/domain/exception` |
| SharedTable ErrorCode | `common/exception` | `restaurant/sharedtable/domain/exception` |
| TimeSlot ErrorCode | `common/exception` | `restaurant/timeslot/domain/exception` |
| 개인정보 마스킹 | `common/support` | `common/privacy` |
| Chat 낙관적 락 응답 처리 | `common/exception/GlobalExceptionHandler` | `chat/presentation/exception/ChatExceptionHandler` |

- Chat handler는 Admin moderation endpoint에서 발생한 예외도 기존과 같이 처리해야 하므로 controller package로 범위를 제한하지 않았다.
- 공통 catch-all handler보다 먼저 처리되도록 `Ordered.HIGHEST_PRECEDENCE`를 명시했다.
- 새 Facade, Port, Adapter를 만들지 않았고 기존 책임이나 도메인 간 계약을 재설계하지 않았다.
- `GlobalExceptionHandler`의 PortOne monitoring 분기는 기존 Monitoring 동작 보존을 위해 유지했다.

## After 결과

### Common 구조

| package | Java 파일 수 |
|---|---:|
| `common/exception` | 4 |
| `common/response` | 2 |
| `common/config` | 2 |
| `common/outbox` | 5 |
| `common/monitoring` | 2 |
| `common/privacy` | 1 |
| `common/entity` | 1 |
| `common/transaction` | 1 |
| 합계 | 18 |

| 정합성 항목 | After |
|---|---:|
| Common에 있는 도메인 ErrorCode | 0 |
| 기존 ErrorCode FQCN 잔존 | 0 |
| 기존 `MemberNameMasker` FQCN 잔존 | 0 |
| `common/support` production Java | 0 |
| package/path 불일치 | 0 |

### Guardrail Before/After

| Guardrail | Before | After | 결과 |
|---|---:|---:|---|
| 7개 ErrorCode의 코드/status/message 본문 | 7 | 7 | 동일 |
| `MemberNameMasker` 구현 본문 | 1 | 1 | 동일 |
| 낙관적 락 exception handler | 1 | 1 | 소유권만 이동 |
| 충돌 응답 | `409`, `CHAT_ROOM_REPORT_ALREADY_REVIEWED` | 동일 | 동일 |
| Common Outbox Java | 5 | 5 | 동일 |
| API/Entity/Transaction/Lock 계약 변경 | 0 | 0 | 변경 없음 |

## build/test/Sonar 회귀 검증

| 검증 | 결과 |
|---|---|
| 관련 테스트 | PASS, After 4 classes / 17 tests / failure 0 / error 0 / skipped 0 |
| Sonar 수정 영향 Payment 테스트 | PASS, 2 classes |
| `compileJava` / `compileTestJava` | PASS |
| 최종 Head `clean build` | PASS, root 214 classes / 945 tests / failure 0 / error 0 / skipped 64 |
| Lambda build/test | PASS, 3 classes / 11 tests / failure 0 / error 0 / skipped 0 |
| package/path | PASS, 불일치 0 |
| 기존 package/FQCN | PASS, 잔존 0 |

### SonarQube After

- 최초 After SHA `dacbff4`:
  - CE task `17f283bb-8bc8-4709-84bf-81fd9b73a890` (`SUCCESS`)
  - Analysis `0d765f64-c385-4efd-9ea3-2a882bac5e08`
  - 같은 package가 된 `PaymentExpiredException`의 `PaymentErrorCode` import에서 실제 신규 `S1128` 1건 확인
- 최종 After SHA `567627f`:
  - CE task `7e94e869-9253-4244-8dca-40cc35142be3` (`SUCCESS`)
  - Analysis `5829c889-eb7b-43ab-82e6-aea297bc8cf6`
  - 전체 project 326 issues (`BUG 14`, `VULNERABILITY 1`, `CODE_SMELL 311`), Before와 동일
  - 최종 변경 Java 100개 범위 91 issues (`BUG 1`, `CODE_SMELL 90`)
  - 최초 90개 비교 범위의 기존 64건 중 63건은 같은 key 유지
  - `ChatErrorCode`의 기존 `S1659` 1건은 같은 규칙, 유형, severity, message로 새 경로에 재키잉
  - 추가 27건은 wildcard import 정리로 늦게 변경 범위에 포함된 기존 7개 파일의 기존 issue이며 creation date와 비라우팅 코드 동일성을 확인
  - 새 handler와 test 3개에서 issue 0
  - 실제 신규 `BUG`, `VULNERABILITY`, `CODE_SMELL` 회귀 0
  - Quality Gate `ERROR`는 기존 project 전체 issue 기준이며 Before와 동일

## 결과 해석

- Common production Java는 25개에서 18개로 줄었고, 도메인 ErrorCode와 개인정보 마스킹의 소유권이 package tree에 드러난다.
- Chat 낙관적 락 응답은 Admin endpoint에서의 기존 동작을 유지하면서 Chat presentation이 소유한다.
- 관련 테스트, 전체 clean build, 구조와 동작 Guardrail, SonarQube 비교에서 기존 계약 변경 증거가 없다.
- 최초 Sonar After에서 발견한 실제 신규 회귀 1건은 이번 범위 안에서 제거했고 최종 신규 회귀는 0건이다.
- PortOne monitoring 분기의 세부 소유권, 공통 Outbox와 도메인 이벤트 결합, 도메인 간 의존 방향은 이번 작업에서 재설계하지 않았다.

## 검증 한계

- 실제 AWS 배포와 외부 운영 환경 검증은 수행하지 않았다.
- 실제 운영 데이터로 Admin/OWNER API를 호출하지 않았으며 자동화 테스트와 정적 Guardrail로 검증했다.
- 전체 build의 root test 64건은 현재 환경에서 skipped 상태이며 개별 외부 환경 검증을 대체하지 않는다.
- SonarQube Quality Gate의 기존 전체 project 이슈는 이번 구조 이동 범위에서 일괄 수정하지 않았다.
- 이번 Evidence는 위치와 소유권 변경 및 기존 동작 보존만 검증하며 성능, 비용, 신뢰성 개선을 주장하지 않는다.

## 관련

- Issue: https://github.com/gpekd5/bobfull-backend/issues/27
- PR: https://github.com/gpekd5/bobfull-backend/pull/36
