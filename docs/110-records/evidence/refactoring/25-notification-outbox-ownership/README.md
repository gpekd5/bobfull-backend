# Issue #25 Notification 및 Outbox 소유권 정리 Evidence

## 검증 대상

- 범용 Outbox 저장, 상태, Repository, Transaction 기반을 `common/outbox`로 이동한다.
- Email Outbox 처리와 실행 설정을 `notification/infrastructure/outbox`로 이동한다.
- SMTP 구현을 `notification/infrastructure/smtp`로 이동한다.
- package declaration, import, 테스트 package와 참조만 함께 변경한다.
- Chat 전용 Outbox 구현은 #23에서 이미 `chat/infrastructure/outbox`로 이동되었으므로 이번 변경 대상이 아니다.

## 유지할 기존 동작

- `outbox_event`, `email_outbox_delivery` 테이블과 JPA mapping
- Outbox EventType과 생성 Factory 계약
- Email enqueue의 `MANDATORY` propagation과 commit 이후 signal 순서
- claim, complete, fail, stale recovery, manual retry의 `REQUIRES_NEW` 경계
- 최대 재시도 횟수, 지수 backoff, stale 판단, batch 처리 정책
- Email Scheduler 활성화 조건, 주기, batch size와 executor 설정
- Reservation에서 Email Outbox로 이어지는 기존 직접 호출 관계
- SMTP 제목, 본문, 수신자 선택과 발송 방식

## 기준 코드

- Before SHA: `c31fe8d76cbf258cee761a940ec648308d892c99`
- After 구현 SHA: `f1add964e352d33a50ea5279a61eaad4344aa055`
- Base branch: `develop`
- 작업 branch: `refactor/25-notification-outbox-ownership`

## 환경·실행 조건

- Windows PowerShell, 저장소 Gradle Wrapper
- SonarQube Server `26.9.0.129388`, project key `bobfull-backend`
- Before와 After 모두 같은 로컬 SonarQube 서버, project key, 인증 환경변수와 분석 명령을 사용했다.
- `SONAR_TOKEN` 값은 명령 출력이나 Evidence에 기록하지 않았다.

## 측정 방법

### 구조와 참조

- Java 파일 수: `Get-ChildItem <대상 경로> -Recurse -Filter '*.java'` 결과를 집계했다.
- 기존 package/FQCN: `rg -n 'com\.bobfull\.(outbox|notification\.adapter)' src/main/java src/test/java`로 검색했다.
- package/path: 각 Java 파일의 source root 상대 경로를 FQCN으로 변환해 첫 `package` 선언 및 파일명과 비교했다.
- Reservation 직접 의존: Reservation main source에서 Notification Email Outbox FQCN을 참조하는 파일 수를 `rg -l`로 집계했다.
- 비라우팅 변경: rename-aware diff에서 package, import, FQCN 변경 이외 production logic 변경 여부를 확인했다.

### 동작 Guardrail

- `@Transactional`, `Propagation.MANDATORY`, `Propagation.REQUIRES_NEW`, `AfterCommitExecutor.run`, `@Scheduled`를 범용/Email Outbox 소스에서 검색해 개수와 위치를 비교했다.
- Entity table 이름, `MAX_RETRIES`, stale threshold, retry backoff 식, Scheduler와 executor property 기본값을 Before/After 코드에서 대조했다.
- `OutboxEventType` enum 값과 Reservation의 Email Outbox 호출 파일을 대조했다.

### 테스트와 build

관련 테스트는 Before와 After에서 같은 클래스 집합을 사용했다. package 이동분만 새 FQCN으로 바꿨다.

```powershell
.\gradlew.bat :test `
  --tests "com.bobfull.common.outbox.*" `
  --tests "com.bobfull.notification.*" `
  --tests "com.bobfull.reservation.application.service.ReservationConfirmationServiceTest" `
  --tests "com.bobfull.reservation.application.service.ReservationCancellationTransactionServiceTest" `
  --tests "com.bobfull.reservation.application.service.RecruitmentDeadlineNotificationIntegrationTest" `
  --tests "com.bobfull.payment.application.service.PaymentReservationConfirmationTransactionIntegrationTest" `
  --tests "com.bobfull.chat.infrastructure.outbox.*" `
  --tests "com.bobfull.chat.application.service.ChatMessageCommandServiceTest" `
  --rerun-tasks
```

```powershell
.\gradlew.bat compileJava compileTestJava --rerun-tasks
.\gradlew.bat clean build
```

### SonarQube

Before와 After 모두 다음 분석 흐름을 사용했다.

```powershell
.\gradlew.bat clean classes testClasses sonar `
  "-Dsonar.host.url=$env:SONAR_HOST_URL" `
  "-Dsonar.token=$env:SONAR_TOKEN" `
  "-Dsonar.scm.revision=<측정 SHA>"
```

Sonar 범위는 이동 대상 파일과 새 소유권 FQCN을 참조하는 main/test 파일의 합집합 37개로 고정했다. 경로 이동 전후에는 old path와 new path를 명시적으로 대응시켜 `path + rule + type + severity + message` 다중 집합을 비교했다.

## Before 결과

### 구조

| 항목 | Before |
|---|---:|
| top-level `outbox` production Java | 14 |
| `notification/adapter` production Java | 1 |
| 범용 Outbox Core 분류 | 5 |
| Notification Email Outbox 분류 | 9 |
| Notification SMTP 분류 | 1 |
| old `outbox` test Java | 4 |
| old `notification/adapter` test Java | 3 |
| 기존 package/FQCN 일치 line | 103 |
| Reservation -> Email Outbox 직접 참조 파일 | 3 |

### 관련 테스트

- 15개 test class
- 92 tests, failures 0, errors 0, skipped 4
- skip 4건은 실제 SMTP 환경변수가 없을 때 실행하지 않는 기존 manual verification이다.

### SonarQube

- CE task: `4e1bd0f4-8d21-4680-828e-d06b03cf2322` (`SUCCESS`)
- Analysis: `cb2e8712-e1b2-4efd-bb19-f39b5a7f34e2`
- 전체 project: 326 issues (`BUG 14`, `VULNERABILITY 1`, `CODE_SMELL 311`)
- 비교 범위 37개 파일: 36 issues, 모두 `CODE_SMELL`
- Quality Gate: `ERROR` (기존 project 전체 issue 기준)

## 변경 내용

| 책임 | Before | After | Java 파일 수 |
|---|---|---|---:|
| 범용 Outbox Core | `outbox/{entity,repository,service}` | `common/outbox/{entity,repository,service}` | 5 |
| Email Outbox | `outbox/{config,entity,repository,service}` | `notification/infrastructure/outbox` | 9 |
| SMTP | `notification/adapter` | `notification/infrastructure/smtp` | 1 |

- production 15개와 test 7개의 경로 및 package를 이동했다.
- 호출부 16개 파일의 import 또는 FQCN을 새 경로로 갱신했다.
- 새 Port, Adapter, Facade 또는 추상화 계층을 만들지 않았다.
- Reservation -> Notification 직접 의존 구조는 재설계하지 않았다.

## After 결과

### 구조

| 항목 | After |
|---|---:|
| top-level `outbox` production Java 잔존 | 0 |
| old `notification/adapter` production Java 잔존 | 0 |
| `common/outbox` production Java | 5 |
| `notification/infrastructure/outbox` production Java | 9 |
| `notification/infrastructure/smtp` production Java | 1 |
| old package test Java 잔존 | 0 |
| 기존 package/FQCN 일치 line | 0 |
| 전체 source package/path 불일치 | 0 |
| Reservation -> Email Outbox 직접 참조 파일 | 3 |

### Guardrail Before/After

| Guardrail | Before | After | 결과 |
|---|---:|---:|---|
| Outbox 관련 `@Transactional` | 7 | 7 | 동일 |
| `MANDATORY` | 1 | 1 | 동일 |
| `REQUIRES_NEW` | 6 | 6 | 동일 |
| Email enqueue after-commit dispatch | 1 | 1 | 동일 |
| Email `@Scheduled` | 1 | 1 | 동일 |
| Reservation -> Email Outbox 직접 참조 파일 | 3 | 3 | 동일 |
| `OutboxEventType` 값 | 6 | 6 | 동일 |

- JPA table 이름은 `outbox_event`, `email_outbox_delivery`로 동일하다.
- `MAX_RETRIES = 5`, stale threshold 5분, `5 * 2^(attemptCount-1)` backoff 식이 동일하다.
- Scheduler는 enabled true/missing, batch size 100, fixed delay 5000ms 기본값으로 동일하다.
- executor는 core/max 2, queue 100, `email-outbox-` prefix, `AbortPolicy`로 동일하다.
- rename-aware production diff에서 package, import, FQCN 이외 로직 변경은 0건이다.

## build/test/Sonar 회귀 검증

| 검증 | 결과 |
|---|---|
| 관련 테스트 Before | PASS, 92 tests / 실패 0 / 오류 0 / skip 4 |
| 관련 테스트 After | PASS, 92 tests / 실패 0 / 오류 0 / skip 4 |
| `compileJava` / `compileTestJava` | PASS |
| `clean build` | PASS, root 944 tests / Lambda 11 tests, 실패 0 / 오류 0 |
| package/path | PASS, 불일치 0 |
| 기존 package/FQCN | PASS, 잔존 0 |

### SonarQube After

- CE task: `4d3607b9-903b-4ce2-be3e-6ab7aa6747ad` (`SUCCESS`)
- Analysis: `17c0cf81-7e72-4792-8284-4dfdf6f732f6`
- 전체 project: 326 issues (`BUG 14`, `VULNERABILITY 1`, `CODE_SMELL 311`)
- 비교 범위 37개 파일: 36 issues, 모두 `CODE_SMELL`
- 규칙별 수량: Before와 After 전부 동일
- 실제 신규 `BUG`, `VULNERABILITY`, `CODE_SMELL`: 0
- Quality Gate: `ERROR` (기존 project 전체 issue 기준, Before와 동일)

범위 36건 중 35건은 기존 key를 유지했다. `EmailOutboxProcessor`의 기존 `java:S1141` 1건은 파일 경로 이동으로 key가 `dc6bdaeb-ca05-4027-ba11-9850263e153b`에서 `25bb1cff-0c92-4bbf-8dba-131302879da3`로 바뀌었다. 규칙, `MAJOR` 심각도, 메시지와 대응 코드가 같아 신규 회귀가 아니라 기존 issue 재등록으로 판정했다.

## 결과 해석

- 범용 기반과 Notification 전달 기술의 package 소유권이 분리되었다.
- 관련 테스트, 전체 build, 정적 Guardrail과 Sonar 비교에서 기존 동작 또는 계약 변경 증거는 없었다.
- 기존 Sonar issue는 이번 위치 이동 범위에서 일괄 수정하지 않았다.
- `OutboxEventType`과 Factory의 Chat/Email 결합, Reservation -> Notification enqueue 계약은 2차 리팩토링 설계 부채로 유지한다.

## 검증 한계

- 실제 SMTP 서버 발송은 환경 의존 manual verification 4건이 skip되어 수행하지 않았다.
- 실제 AWS 배포 및 외부 운영 환경 검증은 수행하지 않았다.
- SonarQube 검증은 기존 로컬 Baseline 서버 기준이며 Quality Gate 전체 실패 원인은 이번 Issue에서 수정하지 않았다.
- package 이동과 기존 동작 보존만 검증했으며 성능 또는 신뢰성 개선 효과를 주장하지 않는다.

## 관련

- Issue: https://github.com/gpekd5/bobfull-backend/issues/25
- PR: https://github.com/gpekd5/bobfull-backend/pull/34
