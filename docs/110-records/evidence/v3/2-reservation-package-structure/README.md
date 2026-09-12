# Issue #2 Reservation 패키지 구조 리팩토링 Evidence

## 검증 대상

`reservation` production/test Java 파일을 실제 책임에 따라
`presentation / application / domain / infrastructure`로 재배치하면서 API, DB, 비즈니스 정책,
트랜잭션, 락 순서와 이벤트 처리 동작이 바뀌지 않았는지 검증한다.

## 측정 계약

- Primary KPI: production Java 73개를 목표 4개 패키지로 재분류하고 기존 최상위 패키지 잔존 파일을 0개로 만든다.
- Secondary KPI: production/test 파일 수와 목표 패키지별 파일 수를 Before/After로 비교한다.
- 안전 확인: 외부 Repository/Entity 직접 참조, Port 접근, Transaction annotation/Propagation 위치가 유지되고 기존 테스트와 전체 clean build가 회귀하지 않는다.

외부 직접 참조와 Transaction 경계는 감소 목표가 아니라 동일성 Guardrail이다.

## 기준 코드

- Before SHA: `2fd977dc25a5d6cec02dbb9758dc0682b0714fef`
- After SHA: `PENDING`
- 기준 브랜치: fork `origin/develop`에서 생성한 `refactor/2-reservation-package-structure`

## 환경·데이터·실행 조건

- 측정일: 2026-09-12 KST
- OS: Windows 11 amd64
- Gradle Wrapper: 9.5.1
- Java toolchain: 17
- 테스트 필터: `com.bobfull.reservation.*`
- SonarQube 목표 환경: `sonarqube:26.9.0.129388-community`, Gradle plugin 7.5.0.8588

## 측정 방법

### Java 파일과 최상위 패키지

```powershell
rg --files src/main/java/com/bobfull/reservation -g '*.java'
rg --files src/test/java/com/bobfull/reservation -g '*.java'
rg --no-filename '^package com\.bobfull\.reservation\.([^.]+)(?:\..*)?;' <root> -g '*.java' -r '$1'
```

최상위 패키지 추출 결과를 `Group-Object`로 집계한다. 기존 패키지 잔존 수는
`adapter/controller/dto/entity/policy/port/repository/service` package 선언 수이고, 목표 패키지 수는
`presentation/application/domain/infrastructure` package 선언 수다.

### 실제 책임 기준 재분류

- presentation: Controller 6개와 HTTP API Request/Response DTO 17개
- application: Spring Scheduler를 제외한 service 15개, reservation Port 4개, repository/service 사이 내부 조회 Result 5개
- domain: Entity/도메인 enum 6개, Policy 1개, 취소 적용 범위를 나타내는 `CancellationScope` 1개
- infrastructure: Repository 11개, Adapter 5개, 외부 시간 트리거인 Spring Scheduler 2개

`*Result`는 HTTP 응답이 아니라 repository 조회 결과를 application service가 소비하는 내부 계약이므로
application으로 분류한다. `RecruitmentDeadlineScheduler`와 `ReservationClosingScheduler`는
`@Scheduled` 기술 진입점이므로 infrastructure로 분류한다.

### 외부 도메인 직접 참조

```powershell
rg -n '^import com\.bobfull\.(?!reservation\.|common\.)[^;]*\.repository\.[A-Za-z0-9_]+;' `
  src/main/java/com/bobfull/reservation -g '*.java' --pcre2
rg -n '^import com\.bobfull\.(?!reservation\.|common\.)[^;]*\.entity\.[A-Za-z0-9_]+;' `
  src/main/java/com/bobfull/reservation -g '*.java' --pcre2
```

import 문 개수를 기준으로 하며 `reservation` 자체와 공통 기반인 `common`은 제외한다. 외부 도메인의
QueryDSL Q type과 enum도 `.entity` package 결합이므로 포함한다. 파일 수와 고유 import type 수도 함께 센다.

### Port 접근

```powershell
rg --files src/main/java/com/bobfull/reservation/port -g '*.java'
rg -n '^import com\.bobfull\.reservation\.port\.[A-Za-z0-9_]+;' `
  src/main/java/com/bobfull/reservation/service -g '*.java'
rg -n 'implements (ReservationTargetReader|ReservationCapacityReader|ReservationCancellationRefundPort|ReservationNotificationPort)' `
  src/main/java -g '*.java'
```

Port 계약 수, application 소비 위치의 import 문 수, 구현체 수를 분리해 기록한다. package 이동 후에는
동일한 type 집합을 새 경로로 검색한다.

### Transaction

```powershell
rg -n -A 2 '@Transactional' src/main/java/com/bobfull/reservation -g '*.java'
```

실제 annotation과 이어지는 메서드 선언을 확인한다. JavaDoc에 포함된 `Propagation` 문자열은 세지 않는다.

### 테스트

```powershell
rg -n '@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b' `
  src/test/java/com/bobfull/reservation -g '*.java'
.\gradlew.bat clean test --tests "com.bobfull.reservation.*" --no-daemon
```

Gradle 출력과 `build/test-results/test/TEST-com.bobfull.reservation*.xml` 합계를 함께 확인한다.

### SonarQube

```powershell
docker version
docker-compose -f docker-compose.sonar.yml ps
```

서버가 기동되면 PR #11과 같은 project key, scanner, image와 API scope로 Reservation component의 Issue 수와
유형을 조회한다. 서버가 없으면 전체 Baseline 수치에서 Reservation 값을 추정하지 않는다.

## Before 결과

### 구조

| 지표 | Before | After | 판정 |
|---|---:|---:|---|
| Reservation production Java | 73 | 73 | PASS |
| Reservation test Java | 40 | 40 | PASS |
| 기존 패키지 잔존 production | 73 | 0 | PASS |
| 기존 패키지 잔존 test | 40 | 0 | PASS |
| 목표 4개 패키지 production | 0 | 73 | PASS |
| 목표 4개 패키지 test | 0 | 40 | PASS |

Production 최상위 패키지는 `adapter 5`, `controller 6`, `dto 23`, `entity 6`, `policy 1`, `port 4`,
`repository 11`, `service 17`이다.

Test 최상위 패키지는 `adapter 5`, `controller 6`, `entity 1`, `policy 1`, `repository 8`, `service 19`다.

계획한 production 재분류는 `presentation 23`, `application 24`, `domain 8`, `infrastructure 18`이다.

After production은 계획과 같은 `presentation 23`, `application 24`, `domain 8`, `infrastructure 18`이다.
After test는 `presentation 6`, `application 17`, `domain 2`, `infrastructure 15`다.

### 의존성 Guardrail

| 지표 | Before | After | 판정 |
|---|---:|---:|---|
| 외부 Repository import 문 | 23 | 23 | PASS |
| 외부 Repository 참조 파일 / 고유 type | 9 / 6 | 9 / 6 | PASS |
| 외부 Entity import 문 | 51 | 51 | PASS |
| 외부 Entity 참조 파일 / 고유 type | 19 / 15 | 19 / 15 | PASS |
| reservation Port 계약 | 4 | 4 | PASS |
| application의 reservation Port import | 8 | 8 | PASS |
| Port 소비 파일 / 고유 type | 7 / 4 | 7 / 4 | PASS |
| Port 구현체 | 4 | 4 | PASS |

Port 소비 import는 `ReservationCancellationRefundPort 4`, `ReservationCapacityReader 2`,
`ReservationNotificationPort 1`, `ReservationTargetReader 1`이다.

### Transaction Guardrail

| 지표 | Before | After | 판정 |
|---|---:|---:|---|
| `@Transactional` | 19 | 19 | PASS |
| annotation 보유 파일 | 9 | 9 | PASS |
| `readOnly = true` | 10 | 10 | PASS |
| 기본 쓰기 Transaction | 7 | 7 | PASS |
| `Propagation.MANDATORY` | 2 | 2 | PASS |

`MANDATORY` 위치는 `ReservationCancellationCompletionService.complete`와
`ReservationConfirmationService.confirm`이다. 나머지는 `MyReservationQueryService 2`,
`OwnerReservationQueryService 3`, `NoShowService 5`, `ReservationCancellationTransactionService 3`,
`ReservationClosingProcessor 1`, `ReservationPreparationService 2`, `ReservationSearchService 1`이다.

### 테스트

첫 Reservation 전체 실행:

```text
205 tests completed, 1 failed, 5 skipped
```

- 결과: `FAIL`
- 실패: `PerformanceTestReservationCompletionHookTest.FAIL_결과_헤더는_지연없이_즉시_예외를_던진다`
- 원인: `elapsed < 45ms` 조건에 실제 46ms
- 단독 class 재실행: 5 tests 중 1 failed, 실제 45ms로 같은 strict less-than 조건 실패

이 타이밍 테스트나 production 구현은 Issue #2 범위에서 수정하지 않고 After에서 같은 명령과 조건으로 비교한다.

### SonarQube

- Reservation 범위 Issue 수와 유형: `NOT_AVAILABLE`
- 원인: Docker Desktop Linux Engine named pipe가 없어 `docker version`과 Compose 상태 조회 실패
- 추가 시도: Docker Desktop 백그라운드 실행이 유지되지 않음
- 제한: PR #11 전체 미해결 Issue 329건을 Reservation 전용 수치로 추정하지 않음

## 변경 내용

- Controller와 HTTP Request/Response DTO를 `presentation`으로 이동했다.
- application service, Port와 내부 조회 Result를 `application`으로 이동했다.
- Entity, Policy와 `CancellationScope` domain enum을 `domain`으로 이동했다.
- Repository, Adapter와 Spring Scheduler를 `infrastructure`로 이동했다.
- production/test 전체의 package 선언과 Reservation 참조 import를 새 경로에 맞췄다.
- 서비스 책임, 외부 직접 참조, Transaction, 락과 Outbox/event 로직은 변경하지 않았다.

## After 결과

구조와 의존성, Transaction 수치는 위 Before/After 표처럼 목표 분류를 달성하고 Guardrail을 유지했다.

Reservation root suite는 하위 프로젝트에 필터가 전파되지 않도록 다음 명령으로 실행했다.

```powershell
.\gradlew.bat :test --tests "com.bobfull.reservation.*" --rerun-tasks --no-daemon
```

결과는 `BUILD SUCCESSFUL`이며 생성된 XML 기준 38개 suite, 203 tests, 실패 0, skip 4다.
`ReservationCancellationCompletionServiceIntegrationTest`는 명시적 class filter로 추가 실행해 1 test가
통과했다. `SettlementReservationQueryScaleInvestigationTest`는 명시적 실행 명령은 성공했지만
`BOBFULL_MYSQL_PERF_TEST=true`가 없어 JUnit XML을 만들지 않고 조건부 제외됐다.

Before에서 실패한 `PerformanceTestReservationCompletionHookTest`의 시간 임계값 case는 After 전체 실행에서
통과했다. 이 테스트의 기준이나 production hook 구현은 변경하지 않았으므로 실행 환경에 민감한 기존 변동성으로
판단한다.

SonarQube Reservation 범위 회귀 분석은 `NOT_RUN`이다. Docker Desktop 서비스는 설치돼 있지만 현재 실행
권한으로 시작할 수 없었고 local Sonar token도 현재 shell에 없었다. 기존 전체 329건 또는 다른 SHA의 수치를
After Reservation 결과로 사용하지 않았다.

## 정합성 회귀 검증

| 검증 | 결과 | 근거 |
|---|---|---|
| production compile | PASS | `.\gradlew.bat compileJava --no-daemon` |
| test compile | PASS | `.\gradlew.bat compileTestJava --no-daemon` |
| Reservation root suite | PASS | 203 tests, 실패 0, skip 4 |
| MANDATORY Spring proxy test | PASS | 명시 class 실행 1 test |
| MySQL 성능 조사 test | NOT_RUN | `BOBFULL_MYSQL_PERF_TEST=true` 미설정 |
| 전체 clean build | PASS | root 944 tests, 실패 0, skip 64; Lambda 11 tests, 실패 0 |
| 기존 package 참조 검색 | PASS | 기존 8개 최상위 package 참조 0건 |
| Java diff 내용 제한 | PASS | 변경된 Java diff line은 모두 Reservation package/import 경로 변경 |
| whitespace 검사 | PASS | `git diff --cached --check` 출력 없음 |
| SonarQube 회귀 | NOT_RUN | Docker service 시작 권한과 local token 부재 |

## 결과 해석

production/test Java 파일 수를 유지한 채 목표 4개 책임 패키지로 재배치했다. 외부 Repository/Entity 참조,
Port 접근과 Transaction annotation 수가 Before와 같고 Java diff에도 package/import 경로 외 변경이 없어
이번 결과는 동작이나 의존성 감소가 아니라 구조 정리와 기존 경계 보존으로 해석한다.

## 검증 한계

- Before SonarQube Reservation component 수치는 로컬 분석 서버를 기동하지 못해 수집하지 못했다.
- 시간 임계값 기반 기존 테스트 1건이 Before부터 재현 가능하게 실패한다.
- Reservation wildcard 실행에서 위 특수 테스트 2개는 자동 결과에 포함되지 않아 MANDATORY test는 명시 실행하고
  MySQL 성능 조사는 환경 조건 미충족으로 `NOT_RUN` 처리했다.
- 이 리팩토링은 외부 직접 참조나 Transaction 수 감소를 효과로 주장하지 않는다.

## 관련

- [Issue #2](https://github.com/gpekd5/bobfull-backend/issues/2)
- [Issue #10 SonarQube Baseline](../10-sonarqube-baseline/README.md)
- [ADR 0005](../../../040-architecture/adr/0005-domain-boundary-dependency-policy.md)
