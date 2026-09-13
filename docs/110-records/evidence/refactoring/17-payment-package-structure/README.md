# Issue #17 Payment 패키지 구조 정리 Evidence

## 검증 대상

Payment의 기존 `controller/service/repository/adapter/port/config` 혼합 구조를 실제 책임에 따라
`presentation/application/domain/infrastructure`로 재배치한다. API, DB, 결제·환불 정책, 외부 연동,
Transaction/Lock과 기존 Port 계약은 바꾸지 않고 위치, package declaration, import와 test reference만 정리한다.

## 유지할 기존 동작

- Payment/Refund/Settlement/PortOne Webhook API path, request, response와 status
- `Payment`, `Refund` JPA Entity와 table/column/index mapping
- READY Payment 10분 좌석 선점, 완료 검증, 멱등 응답과 만료 보상 흐름
- Refund 외부 호출, 완료 확정과 reconciliation 흐름
- Transaction annotation, propagation과 Payment/Refund 비관적 Lock 위치·순서
- Reservation과 Payment의 기존 호출 관계 및 PortOne SDK 연동 방식
- Scheduler fixed delay, batch size, 활성화 조건과 처리 정책

## 기준 코드

- Before SHA: `3db860238f75792e5cc11ff8ddda68b6d4f60f0e`
- After source SHA: `4fc512a511cbb2f62df85069ca140b8377d267c1`
- 기준 브랜치: 최신 `develop`에서 생성한 `refactor/17-payment-packages`

## 환경·실행 조건

- 측정일: 2026-09-14 KST
- OS: Windows 11 amd64
- Gradle Wrapper: 9.5.1
- Java toolchain: 17
- SonarQube: `26.9.0.129388-community`
- SonarQube project key: `bobfull-backend`

## 측정 방법

### 파일·패키지 구조

```powershell
Get-ChildItem src/main/java/com/bobfull/payment -Recurse -Filter *.java
Get-ChildItem src/test/java/com/bobfull/payment -Recurse -Filter *.java
Select-String -Pattern '^package '
rg -n 'com\.bobfull\.payment\.(controller|dto|service|port|entity|exception|repository|adapter|config)(\.|;)' `
  src/main/java src/test/java --glob '*.java'
```

- production/test Java 파일을 Payment root에서 실제 경로로 집계했다.
- Before는 첫 하위 package, After는 네 책임 package로 그룹화했다.
- 현재 책임 기준 재분류 수와 실제 After 수를 비교했다.
- package declaration은 Payment root 기준 실제 디렉터리 경로와 비교했다.

### 동작 보존 Guardrail

```powershell
rg -n '@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)'
rg -n '@Transactional|Propagation\.REQUIRES_NEW'
rg -n '@Lock\(|@Scheduled\('
git diff --find-renames=50% --unified=0 <Before SHA>..<After SHA> -- '*.java'
git diff --check <Before SHA>..<After SHA>
```

- 외부 Repository/Entity/Service/Port 직접 참조는 Payment production source의 import line 수로 집계했다.
- 기존 5개 Port와 `ReadyPaymentCreator`, `PaymentHoldReader`, `RefundIdempotencyKeyGenerator`의 interface 및
  메서드 시그니처를 비교했다.
- 모든 변경 Java를 대상으로 package/import/FQCN을 정규화한 원문 비교를 수행했다.
- Scheduler와 Processor가 다른 package로 이동하면서 기존 예외 분기 접근을 유지하도록
  `RefundLookupException` 가시성만 `public`으로 열고, 정규화 비교에서는 이 기계적 변경을 제외했다.
- 외부 직접 참조, Transaction, Lock과 Port 수는 감소 목표가 아니라 기존 동작 동일성 Guardrail이다.

### 테스트·build

```powershell
.\gradlew.bat :test --tests "com.bobfull.payment.*" --rerun-tasks
.\gradlew.bat compileJava compileTestJava
.\gradlew.bat clean build
```

Gradle 결과와 `build/test-results/test/TEST-*.xml`의 tests/failures/errors/skipped 합계를 함께 확인했다.

### SonarQube

```powershell
$revision = git rev-parse HEAD
.\gradlew.bat clean classes testClasses sonar `
  "-Dsonar.host.url=$env:SONAR_HOST_URL" `
  "-Dsonar.token=$env:SONAR_TOKEN" `
  "-Dsonar.scm.revision=$revision"
```

Before/After 모두 같은 로컬 서버, project key, scanner, Quality Profile과 Quality Gate로 분석했다. Compute
Engine 완료와 issue persistence는 같은 SonarQube container log에서 task id와 `status=SUCCESS`를 확인했고,
열린 Issue는 Project Analysis Token으로 `api/issues/search`를 조회했다. Token 값은 출력하거나 기록하지 않았다.

## Before 결과

### 구조

| 기존 package | Java 파일 수 |
|---|---:|
| `adapter` | 8 |
| `config` | 3 |
| `controller` | 5 |
| `dto` | 9 |
| `entity` | 5 |
| `exception` | 1 |
| `port` | 5 |
| `repository` | 2 |
| `service` | 17 |
| 합계 | 55 |

현재 책임으로 재분류한 목표 수는 presentation 12, application 21, domain 6, infrastructure 16이다.

- 테스트 source: 44 files, `@Test` 194개
- 기존 package 대상 production 파일: 55
- 외부 Repository 직접 import: 4
- 외부 Entity 직접 import: 4
- 외부 Service 직접 import: 2
- 외부 Port 직접 import: 1
- 기존 application 계약 interface: 8
- Transaction annotation: 23
- `Propagation.REQUIRES_NEW`: 6
- 비관적 Lock annotation: 5
- Scheduler annotation: 2
- API mapping annotation: 14
- JPA Entity: 2

### 테스트

- 관련 테스트: 44 source files, 실행 report 44 suites/186 test cases, failures/errors 0, 환경 조건 skip 12

### SonarQube

- 분석 SHA: `3db860238f75792e5cc11ff8ddda68b6d4f60f0e`
- Gradle 분석: `BUILD SUCCESSFUL`
- Compute Engine: `SUCCESS` (`10c07ab0-bcc3-4b37-95b2-f346714891eb`)
- analysis id: `71fb1a52-976e-43d5-ad8b-129bb0cc2ed4`
- issue persistence: inserts 0, updates 0
- 프로젝트 열린 Issue: 327
- Payment 열린 Issue: 65
  - BUG 3, CODE_SMELL 62
  - `java:S1128` 5, 그 외 rule/type/severity 분포는 After와 동일

## 변경 내용

- Controller 5개와 HTTP Response DTO 7개를 `presentation`으로 이동
- 내부 Command/Result 2개, 처리 흐름 11개와 기존 계약 interface 8개를 `application`으로 이동
- Payment/Refund Entity·Enum 5개와 Payment 상태 예외 1개를 `domain`으로 이동
- Repository 2개, 기존 Adapter 8개, UUID 구현 1개, Config 3개, Scheduler 2개를 `infrastructure`로 이동
- 관련 테스트 44개를 production 책임에 대응하는 package로 이동
- Admin, Reservation, TimeSlot, 성능/AI 평가 테스트 등 외부 참조 FQCN 갱신
- 초기 After Sonar에서 이동된 기존 S1128이 새 FQCN message로 재등록된 1건의 import만 제거
- 새 Service/Port/Adapter, 외부 계약 또는 비즈니스 로직 변경 없음

## After 결과

### 구조

| 책임 | Production | Test source |
|---|---:|---:|
| `presentation` | 12 | 4 |
| `application` | 21 | 21 |
| `domain` | 6 | 2 |
| `infrastructure` | 16 | 17 |
| 합계 | 55 | 44 |

- 테스트 source의 `@Test`: 194개
- 기존 package 잔존 production 파일: 0
- 기존 package/FQCN reference: 0
- package declaration/path 불일치: 0
- 외부 Repository/Entity/Service/Port 직접 import: 4/4/2/1
- 기존 application 계약 interface: 8
- Transaction annotation: 23
- `Propagation.REQUIRES_NEW`: 6
- 비관적 Lock annotation: 5
- Scheduler annotation: 2
- API mapping annotation: 14
- JPA Entity: 2
- 정규화한 Java 동작 본문 Before/After 차이: 0
- Java Diff: 136 files, 472 insertions, 462 deletions; whitespace 오류 0

## build/test/Sonar 회귀 검증

| 검증 | 결과 | 증거·한계 |
|---|---|---|
| `compileJava` | PASS | 최신 source 전체 compile 성공 |
| `compileTestJava` | PASS | 최신 test source 전체 compile 성공 |
| 관련 테스트 | PASS | 44 source/194 `@Test` 유지, 현재 report 41 suites/183 cases, failures/errors 0, skip 9 |
| 전체 `clean build` | PASS | root 944 tests, failures/errors 0, skipped 64; Lambda 11 tests 통과 |
| 기존 package 잔존 | PASS | 기존 9개 최상위 package와 FQCN 검색 0건 |
| package/path 정합성 | PASS | Payment production/test Java 불일치 0건 |
| Java 변경 범위 | PASS | 정규화한 동작 본문 차이 0, 내부 예외 가시성만 이동에 필요한 최소 보완 |
| API/JPA/Transaction/Lock 보존 | PASS | 14/2/23/5 동일, 관련 테스트와 전체 build 통과 |
| Port/외부 협력 보존 | PASS | 계약 8, 외부 직접 참조 4/4/2/1 동일; 시그니처·호출 본문 변경 없음 |
| Sonar 분석 실행 | PASS | Before/초기 After/수정 After Gradle 및 Compute Engine 모두 SUCCESS |
| Sonar 신규 회귀 판정 | PASS | 이동 재등록 S1128 정리 후 inserts 0, 신규 열린 Issue 0 |

관련 테스트의 After report에서 세 MySQL 환불 동시성 class는 `BOBFULL_MYSQL_CONCURRENCY_TEST=true`가 없어
실행 report에 suite로 생성되지 않았다. 세 source와 test method는 유지되고 `compileTestJava` 및 전체 build 대상에
포함되며, 환경 변수를 임의로 만들지 않았다.

### SonarQube 초기 After

- 분석 SHA: `9f475bd`
- Compute Engine: `SUCCESS` (`a1730fe1-4152-4168-b2cb-73455a424694`)
- issue persistence: inserts 1, updates 67
- Payment 열린 Issue: 65
- 신규 key `7f3b8ddc-596f-47dc-88e2-3817c50b2677`: `java:S1128`, 이동한
  `PaymentHistoryControllerWebTest`의 기존 미사용 `PaymentDetailResponse` import
- 기존 key `632a1156-d521-4961-8b96-431f31b081b2`와 같은 파일·line·rule이지만 새 FQCN으로 message가 바뀌어
  새 key로 재등록된 것을 API에서 확인했다.

### SonarQube 수정 After

- 분석 SHA: `4fc512a511cbb2f62df85069ca140b8377d267c1`
- Gradle 분석: `BUILD SUCCESSFUL`
- Compute Engine: `SUCCESS` (`09a7b917-9d14-49d0-adf0-3efa9a8c842f`)
- analysis id: `e9ff938d-1920-42f7-8d4b-32ddef4f0c18`
- issue persistence: inserts 0, updates 5
- 신규 열린 Issue notification: 0, 초기 신규 S1128: `CLOSED/FIXED`
- 프로젝트 열린 Issue: 326
- Payment 열린 Issue: 64 (`java:S1128` 4, 나머지 rule/type/severity 분포 동일)
- 현재 전체 Quality Gate: `ERROR` (기존 전체 Issue를 이번 리팩토링에서 수정하지 않음)

## 결과 해석

Payment production 파일은 Before/After 모두 55개이며 목표 재분류 12/21/6/16과 실제 tree가 일치한다. 기존
최상위 package와 FQCN 잔존, package/path 불일치는 모두 0이다. API/JPA/Transaction/Lock/Scheduler/Port 및 외부
직접 참조 수가 동일하고 정규화한 동작 본문 차이도 없다. 관련 테스트와 전체 build가 통과했으므로 위치와
소유권을 정리하면서 기존 결제·환불 동작을 보존했다.

초기 After Sonar insert 1건은 새 결함이 아니라 기존 미사용 import가 이동된 경로와 FQCN으로 재등록된 것이다.
이동과 직접 연결된 import만 제거한 최종 분석은 inserts 0, 신규 열린 Issue 0이며 Payment 열린 Issue는 65에서
64로 감소했다. 기존 64건과 전체 Quality Gate `ERROR`는 Baseline으로 유지하며 이번 작업에서 일괄 수정하지 않는다.

## 검증 한계

- 세 MySQL 환불 동시성 테스트는 전용 환경 변수가 없어 실행하지 않았다. MySQL 실환경 동시성·성능 개선은 이
  위치 이동의 검증 대상이 아니다.
- 선택적 외부 인프라 테스트 64건은 전체 build에서 환경 조건에 따라 skip됐다. PortOne 실계정, Redis, Kafka,
  SMTP, AI와 AWS 실환경 검증을 임의 설정으로 우회하지 않았다.
- API/DB 실환경 smoke test는 수행하지 않았고 annotation 동일성, Controller/Service/Repository 테스트와 전체
  build로 동작 보존을 확인했다.
- 이 리팩토링은 외부 직접 참조, Transaction/Lock 또는 기존 SonarQube Issue 감소를 개선 효과로 주장하지 않는다.

## 관련

- [Issue #17](https://github.com/gpekd5/bobfull-backend/issues/17)
- [Master Issue #18](https://github.com/gpekd5/bobfull-backend/issues/18)
- [Issue #10 SonarQube Baseline](../../v3/10-sonarqube-baseline/README.md)
