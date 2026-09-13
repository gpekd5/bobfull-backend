# Issue #22 Restaurant 공급 영역 패키지 경계 정리 Evidence

## 검증 대상

Restaurant 자체 기능, SharedTable, TimeSlot, Restaurant Image를 하나의 Restaurant 공급 영역 아래에 두고,
각 기능의 현재 책임을 `presentation/application/domain/infrastructure`로 구분한다. 클래스 책임과 도메인 간
계약은 재설계하지 않고 파일 위치, package declaration, import와 test reference만 정리한다.

## 유지할 기존 동작

- Restaurant, SharedTable, TimeSlot, Image API path/request/response/status
- `Restaurant`, `SharedTable`, `TimeSlot` JPA Entity와 table/column/index mapping
- Restaurant 상태, 좌석 공유, TimeSlot 계산·조회·예약 가능성 정책
- Transaction annotation, propagation과 비관적 Lock 위치·순서
- 기존 4개 Port의 메서드와 Adapter 호출 관계
- Reservation, Payment, RestaurantInsight 등 외부 도메인과의 기존 직접 협력 구조

## 기준 코드

- Before SHA: `995c96cc657f229706ed70b10c2d8c7e7f4bc0d8`
- After source SHA: `fc33f26d3b1f6874ff9526ab3038876077a89a4e`
- 기준 브랜치: `origin/develop`에서 생성한 `refactor/22-restaurant-supply-packages`

## 환경·실행 조건

- 측정일: 2026-09-13 KST
- OS: Windows 11 amd64
- Gradle Wrapper: 9.5.1
- Java toolchain: 17
- SonarQube: `26.9.0.129388-community`
- SonarScanner for Gradle: 7.5.0.8588
- SonarQube project key: `bobfull-backend`

## 측정 방법

### 파일·패키지 구조

```powershell
Get-ChildItem src/main/java/com/bobfull/restaurant -Recurse -Filter *.java
Get-ChildItem src/main/java/com/bobfull/sharedtable -Recurse -Filter *.java
Get-ChildItem src/main/java/com/bobfull/timeslot -Recurse -Filter *.java
Select-String -Pattern '^package '
```

- Before production 파일은 위 세 root에서 실제 Java 파일을 집계했다.
- After production 파일은 `src/main/java/com/bobfull/restaurant` 아래 Java 파일을 기능과 책임의 앞 두 경로로
  그룹화했다.
- 테스트 파일과 source의 `@Test` annotation을 같은 세 Before root와 After Restaurant root에서 집계했다.
- 이전 package 잔존은 다음 FQCN 패턴을 전체 `src/main/java`, `src/test/java`에서 검색했다.

```text
com.bobfull.sharedtable.*
com.bobfull.timeslot.*
com.bobfull.restaurant.(controller|dto|service|entity|repository|cache).*
com.bobfull.restaurant.image.(controller|dto|service|port|adapter|config).*
```

### 동작 보존 Guardrail

```powershell
Select-String -Pattern '^\s*@(Request|Get|Post|Put|Patch|Delete)Mapping'
Select-String -Pattern '^\s*@Transactional'
Select-String -Pattern 'Propagation\.'
Select-String -Pattern '^\s*@Lock'
git diff --find-renames=40% --unified=0 <Before SHA>..<After SHA> -- '*.java'
git diff --check <Before SHA>..<After SHA>
```

- API, JPA와 Transaction/Lock annotation line을 Before/After에서 정렬한 뒤 `Compare-Object`로 비교했다.
- 외부 Repository/Entity/Service 직접 참조는 공급 영역 production source의 import line 수로 집계했다.
- Port는 이름이 `Port`로 끝나는 기존 interface를 집계하고 시그니처 변경 여부를 Diff로 확인했다.
- 외부 직접 참조, Transaction, Lock과 Port 수는 감소 목표가 아니라 기존 동작 동일성 Guardrail이다.

### 테스트·build

```powershell
.\gradlew.bat :test --tests "com.bobfull.restaurant.*" `
  --tests "com.bobfull.sharedtable.*" `
  --tests "com.bobfull.timeslot.*" --rerun-tasks

.\gradlew.bat :test --tests "com.bobfull.restaurant.*" --rerun-tasks
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

Before/After 모두 같은 서버, project key, scanner, Quality Profile과 Quality Gate로 분석했다. Compute Engine
완료와 issue persistence는 SonarQube container log의 task id와 `status=SUCCESS`로 확인하고, 열린 Issue는
Project Analysis Token으로 `api/issues/search`를 조회했다. Token 값은 출력하거나 Evidence에 기록하지 않았다.

## Before 결과

### 구조

| 기존 package 또는 기능 | Java 파일 수 |
|---|---:|
| `restaurant.cache` | 3 |
| `restaurant.controller` | 2 |
| `restaurant.dto` | 8 |
| `restaurant.entity` | 2 |
| `restaurant.repository` | 3 |
| `restaurant.service` | 1 |
| `restaurant.image` 전체 | 10 |
| `sharedtable` 전체 | 14 |
| `timeslot` 전체 | 13 |
| 합계 | 56 |

현재 책임으로 재분류한 목표 수는 presentation 27, application 10, domain 6, infrastructure 13이다.

- 테스트 source: 23 files, `@Test` 144개
- 이전 package 대상 production 파일: 56
- 외부 Repository 직접 import: 3
- 외부 Entity 직접 import: 3
- 외부 Service 직접 import: 2
- 기존 Port interface: 4
- Transaction annotation: 19 (`RestaurantService` 6, `RestaurantSearchRepositoryImpl` 1,
  `SharedTableService` 6, `TimeSlotService` 6)
- 명시적 `Propagation`: 0
- 비관적 Lock annotation: 2 (`RestaurantRepository`, `TimeSlotRepository`)
- API endpoint method mapping: 21
- JPA Entity: 3

### 테스트

- 관련 테스트: 23 suites, 139 test cases, failures/errors 0, 환경 조건 skip 15
- 최초 `test --tests ...` 명령은 Lambda 하위 프로젝트에 같은 filter가 전달돼 `No tests found`로 전체 명령이
  실패했다. 루트 `:test` task로 범위를 고정해 위 결과를 다시 측정했다.

### SonarQube

- 분석 SHA: `995c96cc657f229706ed70b10c2d8c7e7f4bc0d8`
- Gradle 분석: `BUILD SUCCESSFUL`
- Compute Engine: `SUCCESS` (`3c334331-9a42-4728-aa93-6af64e9d18af`)
- issue persistence: inserts 0, updates 0
- 프로젝트 열린 Issue: 328
- 공급 영역 production 열린 Issue: 5
  - `java:S107` CODE_SMELL/MAJOR 2
  - `java:S3655` BUG/MAJOR 1
  - `java:S6242` CODE_SMELL/MAJOR 1
  - `java:S6244` CODE_SMELL/MINOR 1

## 변경 내용

- Restaurant 자체 기능 19개: presentation 10, application 1, domain 2, infrastructure 6으로 이동
- SharedTable 14개: presentation 6, application 4, domain 2, infrastructure 2로 이동
- TimeSlot 13개: presentation 8, application 3, domain 1, infrastructure 1로 이동
- Restaurant Image 10개: presentation 3, application 2, domain 1, infrastructure 4로 이동
- `RestaurantImageKeyGenerator`는 S3 Object Key 생성 책임에 따라 Image infrastructure storage로 이동
- 기존 Controller/DTO/Service/Entity/Repository/Cache와 4개 Port, 기존 Adapter의 package/import 갱신
- 관련 테스트 23개를 production 책임에 대응하는 package로 이동
- 외부 production/test code의 Restaurant/SharedTable/TimeSlot import와 FQCN reference 갱신
- 초기 After Sonar에서 이동으로 재등록된 `java:S1128` 미사용 import 1개만 제거
- 새 Facade/Port/Adapter 또는 비즈니스 로직 변경 없음

## After 결과

### 구조

| 기능 | presentation | application | domain | infrastructure | 합계 |
|---|---:|---:|---:|---:|---:|
| `restaurant.restaurant` | 10 | 1 | 2 | 6 | 19 |
| `restaurant.sharedtable` | 6 | 4 | 2 | 2 | 14 |
| `restaurant.timeslot` | 8 | 3 | 1 | 1 | 13 |
| `restaurant.image` | 3 | 2 | 1 | 4 | 10 |
| 합계 | 27 | 10 | 6 | 13 | 56 |

- 테스트 source: 23 files, `@Test` 144개
- 이전 package 잔존 production 파일: 0
- 이전 package/FQCN reference 파일: 0
- package declaration/path 불일치: 0
- 외부 Repository 직접 import: 3
- 외부 Entity 직접 import: 3
- 외부 Service 직접 import: 2
- 기존 Port interface: 4
- Transaction annotation: 19
- 명시적 `Propagation`: 0
- 비관적 Lock annotation: 2
- API endpoint method mapping: 21
- JPA Entity: 3
- API/JPA/Transaction·Lock annotation line Before/After 차이: 각각 0
- Java Diff: 145 files, 584 insertions, 581 deletions; package/import/FQCN/Javadoc link 외 예상 밖 코드 변경 0,
  whitespace 오류 0

## build/test/Sonar 회귀 검증

| 검증 | 결과 | 증거·한계 |
|---|---|---|
| `compileJava` | PASS | 전체 main source compile 성공 |
| `compileTestJava` | PASS | 전체 test source compile 성공 |
| 관련 테스트 | PASS | 23 suites, 139 test cases, failures/errors 0, 기존 조건부 skip 15 |
| 전체 `clean build` | PASS | 최종 수정 전후 두 번 성공; 최종 root 944 tests, failures/errors 0, skipped 64; Lambda 11 tests 통과 |
| 이전 package 잔존 | PASS | 네 검색 패턴 전체 0건 |
| package/path 정합성 | PASS | 대상 production/test Java 불일치 0건 |
| Java 변경 범위 | PASS | package/import/FQCN/Javadoc link 외 코드 변경 0건, whitespace 오류 0건 |
| API/DB/Transaction/Lock 보존 | PASS | annotation line 차이 0, 관련 테스트와 전체 build 통과 |
| Port/외부 협력 보존 | PASS | Port 4, 외부 직접 참조 3/3/2 동일; 시그니처와 호출 본문 변경 없음 |
| Sonar 분석 실행 | PASS | Before/초기 After/수정 After Gradle과 Compute Engine 모두 SUCCESS |
| Sonar 신규 회귀 판정 | PASS | 초기 이동 재등록 S1128 1건 정리 후 inserts 0, 신규 열린 Issue 0 |

### SonarQube 초기 After

- 분석 SHA: `c646171`
- Gradle 분석: `BUILD SUCCESSFUL`
- Compute Engine: `SUCCESS` (`8d07ea7e-454c-4ca8-bce6-78d0ffd7f1cd`)
- file move 판정: added 79, moved 74
- issue persistence: inserts 1, updates 24
- 신규 key `75fe3132-58a9-45fd-ab43-64108484e471`: `java:S1128`, 이동한 선택적 검색 Cache
  조사 테스트의 미사용 `RestaurantSearchRequest` import
- 같은 원문의 기존 Issue `2d137321-1a23-47ca-a8af-6099625069b1`는 이동 과정에서 `CLOSED/FIXED` 처리됐다.
  새 FQCN으로 message가 바뀌어 새 key로 재등록된 것을 API의 경로·message·생성/갱신 시각으로 확인했다.

### SonarQube 수정 After

- 분석 SHA: `fc33f26d3b1f6874ff9526ab3038876077a89a4e`
- Gradle 분석: `BUILD SUCCESSFUL`
- Compute Engine: `SUCCESS` (`0471f83b-36aa-4ad1-aa79-7889805b06ec`)
- issue persistence: inserts 0, updates 7
- 초기 신규 S1128: `CLOSED/FIXED`
- 초기 After 분석 시각 이후 생성된 미해결 Issue: 0
- 공급 영역 production 열린 Issue: Before와 같은 5건·같은 rule/type 분포
- 프로젝트 열린 Issue: 327 (이동으로 재등록된 기존 S1128 import를 직접 정리해 Before 328보다 1 감소)
- 현재 전체 Quality Gate: `ERROR` (기존 전체 Issue를 이번 리팩토링에서 수정하지 않음)

## 결과 해석

Restaurant 공급 영역 production 파일은 Before/After 모두 56개이며, 기존 세 top-level과 계층형 package의
잔존 파일·reference가 0개가 됐다. 재분류 목표 27/10/6/13이 실제 target tree와 일치한다. 외부 직접 참조,
Port, Transaction, Lock, API와 Entity 수는 Before와 같고 관련 테스트와 전체 build가 통과했다. 따라서
코드 기준으로 위치와 소유권만 변경되고 기존 동작이 보존됐다.

초기 After Sonar 분석의 insert 1건은 이동 전부터 존재한 미사용 import Issue가 새 경로와 FQCN message로
재등록된 것이었다. 이번 이동과 직접 연결된 import만 제거한 재분석은 inserts 0, 해당 Issue `CLOSED/FIXED`,
신규 열린 Issue 0건이다. 기존 production Issue 5건과 전체 Quality Gate `ERROR`는 Baseline으로 유지하며 이번
리팩토링에서 모두 수정하지 않는다.

## 검증 한계

- Project Analysis Token으로 Issue와 Quality Gate API는 조회할 수 있지만 Compute Engine API 권한은 없어 같은
  로컬 SonarQube container log로 task 완료와 issue persistence를 확인했다.
- 전체 build의 선택적 외부 인프라 테스트 64건과 관련 테스트의 15건은 환경 조건에 따라 skip됐다. MySQL 성능·
  동시성, Redis Cache 효과, Kafka/SMTP/AI/AWS 실제 환경 검증은 이번 위치 이동의 직접 검증 범위가 아니다.
- API와 DB 실환경 smoke test는 수행하지 않았고 annotation 동일성, Web/Service/Repository 테스트와 전체 build로
  동작 보존을 확인했다.
- 이 리팩토링은 외부 직접 참조, Transaction/Lock 또는 기존 SonarQube Issue 감소를 개선 효과로 주장하지 않는다.

## 관련

- [Issue #22](https://github.com/gpekd5/bobfull-backend/issues/22)
- [Master Issue #18](https://github.com/gpekd5/bobfull-backend/issues/18)
- [Issue #10 SonarQube Baseline](../../v3/10-sonarqube-baseline/README.md)
