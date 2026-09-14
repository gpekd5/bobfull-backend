# Issue #26 Admin 패키지 구조 정리 Evidence

## 검증 대상

- Admin Controller와 HTTP DTO를 `admin/presentation`으로 이동한다.
- Admin Service와 내부 조회 결과 모델을 `admin/application`으로 이동한다.
- Admin 전용 조회 및 Repository fragment 구현을 `admin/infrastructure/query`로 이동한다.
- package declaration, import, 테스트 package와 참조만 함께 변경한다.
- Admin은 독자 Entity가 없으므로 `admin/domain` package를 만들지 않는다.

## 유지할 기존 동작

- Admin/OWNER API method, path, request, response와 status
- `/api/admin/**`의 ADMIN 권한과 `/api/owner/**`의 OWNER 권한
- 조회 query의 조건, join, projection, 정렬과 집계 의미
- `MemberNameMasker`를 통한 회원 이름 노출 제한
- read-only를 포함한 Transaction annotation과 propagation
- Admin Repository fragment 계약과 Member, Restaurant, Reservation Repository 연결
- DB schema와 기존 Entity mapping

## 기준 코드

- Before SHA: `45ed63a09b81af6b23c01e08fc50bcfa60946bf7`
- After 구현 SHA: `b56f82998dc70d0700f038f5840bb516888b1116`
- Base branch: `develop`
- 작업 branch: `refactor/26-admin-packages`

## 환경·실행 조건

- Windows PowerShell, 저장소 Gradle Wrapper
- SonarQube Server `26.9.0.129388`, project key `bobfull-backend`
- Before와 After 모두 같은 로컬 SonarQube 서버, project key와 인증 환경변수를 사용했다.
- `SONAR_TOKEN` 값은 명령 출력이나 Evidence에 기록하지 않았다.
- Before Sonar 분석의 source revision은 `f1add964e352d33a50ea5279a61eaad4344aa055`이다. 이 revision과 Before SHA 사이의 Admin 및 외부 Repository fragment 연결 파일 diff가 0임을 확인했다.

## 측정 방법

### 구조와 참조

- 변경 파일과 rename 대응은 `git diff-tree --no-commit-id --name-status -r -M b56f829`으로 집계했다.
- 계층별 Java 파일 수는 `Get-ChildItem <대상 경로> -Recurse -Filter '*.java'` 결과를 집계했다.
- 기존 package/FQCN은 `rg -n 'com\.bobfull\.admin\.(controller|dto|service|repository)' src/main/java src/test/java`로 검색했다.
- package/path는 각 Java 파일의 source root 상대 경로를 FQCN으로 변환해 첫 `package` 선언 및 파일명과 비교했다.
- rename-aware diff에서 package, import, FQCN 변경을 제외한 production logic 변경 여부를 확인했다.

### 동작 Guardrail

- Controller의 `@RequestMapping`, `@GetMapping`, `@PatchMapping` 선언을 Before/After에서 비교했다.
- `SecurityConfig` diff와 `/api/admin/**`, `/api/owner/**` 권한 선언을 비교했다.
- Admin application/infrastructure의 `@Transactional`, `readOnly = true`, 명시적 `Propagation` 사용을 검색했다.
- `MemberNameMasker.mask` 호출과 외부 Repository가 확장하는 Admin fragment 계약을 검색했다.
- query 구현의 rename-aware diff에서 package/import 외 변경이 없는지 확인해 조건, join, projection, 정렬과 집계 코드의 동일성을 검증했다.

### 테스트와 build

```powershell
.\gradlew.bat :test --tests "com.bobfull.admin.*" --rerun-tasks
.\gradlew.bat compileJava compileTestJava --rerun-tasks
.\gradlew.bat clean build
```

### SonarQube

After 분석은 기존 Baseline과 같은 흐름으로 실행했다.

```powershell
.\gradlew.bat clean classes testClasses sonar `
  "-Dsonar.host.url=$env:SONAR_HOST_URL" `
  "-Dsonar.token=$env:SONAR_TOKEN" `
  "-Dsonar.scm.revision=b56f82998dc70d0700f038f5840bb516888b1116"
```

Sonar 비교 범위는 구현 commit에서 변경된 Java 파일의 이동 전후 경로 합집합 80개로 고정했다. old path를 new path로 대응시킨 뒤 `path + rule + type + severity + message` 다중 집합을 비교해 기존 issue 재등록과 실제 신규 회귀를 구분했다.

## Before 결과

### 구조

| package | Java 파일 수 | 책임 |
|---|---:|---|
| `admin/controller` | 9 | HTTP Controller |
| `admin/dto` | 25 | HTTP DTO 18개와 내부 조회 결과 모델 7개 혼재 |
| `admin/service` | 9 | Admin Service |
| `admin/repository` | 12 | Admin 조회 및 Repository fragment 구현 |
| `admin/domain` | 0 | 독자 Entity 없음 |

### SonarQube

- CE task: `4d3607b9-903b-4ce2-be3e-6ab7aa6747ad` (`SUCCESS`)
- Analysis: `17c0cf81-7e72-4792-8284-4dfdf6f732f6`
- 전체 project: 326 issues (`BUG 14`, `VULNERABILITY 1`, `CODE_SMELL 311`)
- 비교 범위 80개 파일: 12 issues, 모두 기존 `CODE_SMELL`
- 규칙별 수량: `S1128` 2, `S2681` 2, `S1659` 3, `S5778` 1, `S6068` 1, `S8924` 1, `S1481` 1, `S1854` 1
- Quality Gate: `ERROR` (기존 project 전체 issue 기준)

## 변경 내용

| 책임 | Before | After | Java 파일 수 |
|---|---|---|---:|
| HTTP Controller | `admin/controller` | `admin/presentation/controller` | 9 |
| HTTP DTO | `admin/dto` | `admin/presentation/dto` | 18 |
| 내부 조회 결과 모델 | `admin/dto` | `admin/application/model` | 7 |
| Admin Service | `admin/service` | `admin/application/service` | 9 |
| 조회 및 Repository fragment 구현 | `admin/repository` | `admin/infrastructure/query` | 12 |

- Admin production 55개와 test 22개의 경로 및 package를 이동했다.
- Member, Reservation, Restaurant Repository 3개 파일의 Admin fragment import를 새 경로로 갱신했다.
- 전체 변경 파일은 80개이며 package/import/FQCN 외 production logic 변경은 0건이다.
- 새 Port, Adapter, domain package 또는 추상화 계층을 만들지 않았다.

## After 결과

### 구조

| package | Java 파일 수 |
|---|---:|
| `admin/presentation/controller` | 9 |
| `admin/presentation/dto` | 18 |
| `admin/application/service` | 9 |
| `admin/application/model` | 7 |
| `admin/infrastructure/query` | 12 |
| `admin/domain` | 0 |

| 잔존/정합성 항목 | After |
|---|---:|
| 기존 `admin/controller`, `dto`, `service`, `repository` Java | 0 |
| 기존 package/FQCN 참조 파일 | 0 |
| 전체 source package/path 불일치 | 0 |

### Guardrail Before/After

| Guardrail | Before | After | 결과 |
|---|---:|---:|---|
| `@RequestMapping` | 9 | 9 | 동일 |
| `@GetMapping` | 15 | 15 | 동일 |
| `@PatchMapping` | 1 | 1 | 동일 |
| Admin `@Transactional` | 16 | 16 | 동일 |
| `readOnly = true` Transaction | 15 | 15 | 동일 |
| 명시적 `Propagation` | 0 | 0 | 동일 |
| `MemberNameMasker.mask` 호출 | 2 | 2 | 동일 |
| 외부 Repository의 Admin fragment 연결 | 3 | 3 | 동일 |
| Admin `@Entity` | 0 | 0 | 동일 |

- `SecurityConfig`는 변경되지 않았고 `/api/admin/**` ADMIN, `/api/owner/**` OWNER 권한 선언이 동일하다.
- query, join, projection, 정렬과 집계 코드는 package/import 외 변경이 없다.
- `AdminMemberRepository`, `AdminRestaurantRepository`, `AdminReservationRepository` 계약과 외부 Repository 확장 관계가 동일하다.

## build/test/Sonar 회귀 검증

| 검증 | 결과 |
|---|---|
| Admin 관련 테스트 | PASS, 22 test classes / 71 tests / 실패 0 / 오류 0 / skip 0 |
| `compileJava` / `compileTestJava` | PASS |
| `clean build` | PASS, root 944 tests / Lambda 11 tests / 실패 0 / 오류 0 |
| package/path | PASS, 불일치 0 |
| 기존 package/FQCN | PASS, 잔존 0 |

`clean build` XML 기준 root test의 skip은 64건이다. Gradle build는 성공했으며, 개별 skip 사유는 #26의 package 이동 범위에서 추가 분석하지 않았다. Lambda test는 skip 0건이다.

### SonarQube After

- CE task: `c5c04566-41a1-4c80-a69b-b712c133d353` (`SUCCESS`)
- Analysis: `87d4933c-04f4-4aaa-9e0b-c763cb365930`
- 전체 project: 326 issues (`BUG 14`, `VULNERABILITY 1`, `CODE_SMELL 311`)
- 비교 범위 80개 파일: 12 issues, 모두 `CODE_SMELL`
- 규칙별 수량: Before와 After 동일
- 기존 key 유지 6건, package 이동으로 기존 issue 재등록 6건
- 실제 신규 `BUG`, `VULNERABILITY`, `CODE_SMELL`: 0
- Quality Gate: `ERROR` (기존 project 전체 issue 기준, Before와 동일)

재등록 6건은 이동된 `AdminModerationReportService`의 `S1659` 3건과 `S2681` 2건, `AdminModerationReportServiceTest`의 `S5778` 1건이다. 각 건의 규칙, 유형, 심각도, 메시지와 대응 코드가 Before와 같아 신규 회귀가 아니라 경로 이동에 따른 기존 issue 재등록으로 판정했다.

## 결과 해석

- package tree에서 Admin의 HTTP, application, query 소유권을 구분할 수 있게 되었다.
- 관련 테스트, 전체 build, 정적 Guardrail과 Sonar 비교에서 기존 동작 또는 계약 변경 증거는 없었다.
- 기존 Sonar issue는 이번 위치 이동 범위에서 수정하지 않았다.
- Repository fragment의 역방향 의존과 외부 Entity/Repository 직접 참조는 2차 리팩토링 설계 부채로 유지한다.

## 검증 한계

- 실제 AWS 배포 및 외부 운영 환경 검증은 수행하지 않았다.
- 실제 운영 데이터에 대한 Admin/OWNER API 수동 호출은 수행하지 않았고 자동화 테스트 및 정적 mapping 비교로 검증했다.
- 전체 build의 root test 64건은 현재 환경에서 skip되었으며 개별 외부 환경 검증을 대체하지 않는다.
- SonarQube 검증은 기존 로컬 Baseline 서버 기준이며 Quality Gate 전체 실패 원인은 이번 Issue에서 수정하지 않았다.
- package 이동과 기존 동작 보존만 검증했으며 query 성능 개선이나 책임/의존성 재설계를 주장하지 않는다.

## 관련

- Issue: https://github.com/gpekd5/bobfull-backend/issues/26
- PR: Draft PR 생성 후 연결
