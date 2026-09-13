# Issue #21 Member/Auth/Security 패키지 정리 Evidence

## 검증 대상

Member와 Auth를 별도 top-level로 유지하면서 기존 Member/Auth/Security 클래스를 현재 책임에 맞는
`presentation/application/domain/infrastructure` 하위 package로 이동한다. 클래스 내부 책임과 도메인 간
계약은 재설계하지 않고 package declaration, import, test reference만 정리한다.

## 유지할 기존 동작

- 회원가입, 로그인, 토큰 재발급, 로그아웃 API path/request/response/status
- Member JPA Entity와 DB schema
- JWT 발급·검증·만료와 인증 주체 해석
- Refresh Token 회전·삭제·TTL과 Access Token blacklist
- Security filter chain, 공개 경로, OWNER/ADMIN 역할 인가와 인증·인가 실패 응답
- Member/Auth Service의 Transaction annotation과 propagation
- Member Repository/Entity를 직접 사용하는 기존 도메인 협력 관계

## 기준 코드

- Before SHA: `9d95e57582498359ec6d980e011405116173bea1`
- After source SHA: `0b75b4f4ea978052ddf8f540b809de9b6447590e`
- 기준 브랜치: `origin/develop`에서 생성한 `refactor/21-member-auth-security-packages`

## 환경·실행 조건

- 측정일: 2026-09-13 KST
- OS: Windows 11 amd64
- Gradle Wrapper: 9.5.1
- Java toolchain: 17
- Redis 선택 테스트: 기존 `redis:7-alpine`, `localhost:6379`, healthy
- SonarQube: `26.9.0.129388-community`
- SonarScanner for Gradle: 7.5.0.8588
- SonarQube project key: `bobfull-backend`

## 측정 방법

### 파일·패키지 구조

```powershell
Get-ChildItem src/main/java/com/bobfull/member -Recurse -Filter *.java
Get-ChildItem src/main/java/com/bobfull/auth -Recurse -Filter *.java
Get-ChildItem src/main/java/com/bobfull/common/security -Recurse -Filter *.java
Select-String -Pattern '^package\s+([^;]+);'
```

대상 production 파일은 Member tree, Auth tree, `common.security` 8개와
`common.exception.MemberErrorCode`를 합산한다. 테스트는 Member/Auth/Common Security 경로의 Java 파일과
`@Test`를 집계한다.

이전 package 잔존은 다음 패턴을 전체 `src/**/*.java`에서 검색한다.

```text
com.bobfull.member.(controller|dto|service|entity|repository)
com.bobfull.auth.(controller|dto|service|token)
com.bobfull.common.security
com.bobfull.common.exception.MemberErrorCode
```

### 동작 보존 Guardrail

```powershell
Select-String -Pattern '^\s*@Transactional(?:\([^)]*\))?'
Select-String -Pattern 'Propagation\.'
Select-String -Pattern '^\s*@(Request|Get|Post|Put|Patch|Delete)Mapping'
git diff --find-renames=50% --unified=0 origin/develop -- '*.java'
git diff --check origin/develop
```

- 외부 Member Repository/Entity 직접 참조는 import가 있는 production 파일 수로 집계한다.
- Java Diff의 변경선은 package/import/FQCN reference 여부를 분류한다.
- 외부 직접 참조와 Transaction 수는 감소 목표가 아니라 기존 동작 동일성 Guardrail이다.

### 테스트·build

```powershell
.\gradlew.bat :test --rerun-tasks `
  --tests "com.bobfull.member.*" `
  --tests "com.bobfull.auth.*" `
  --tests "com.bobfull.common.security.*"

$env:BOBFULL_REDIS_INTEGRATION_TEST = 'true'
.\gradlew.bat :test --rerun-tasks --tests "com.bobfull.auth.token.*"

$env:BOBFULL_REDIS_INTEGRATION_TEST = 'true'
.\gradlew.bat :test --rerun-tasks `
  --tests "com.bobfull.member.*" `
  --tests "com.bobfull.auth.*"

.\gradlew.bat clean build
```

Gradle 결과와 `build/test-results/test/TEST-*.xml`의 tests/failures/errors/skipped 합계를 함께 확인한다.

### SonarQube

```powershell
$revision = git rev-parse HEAD
.\gradlew.bat clean classes testClasses sonar `
  "-Dsonar.host.url=$env:SONAR_HOST_URL" `
  "-Dsonar.token=$env:SONAR_TOKEN" `
  "-Dsonar.scm.revision=$revision"
```

Before/After 모두 같은 서버, project key, scanner, Quality Profile과 Quality Gate로 분석한다. Compute Engine
완료는 SonarQube container log의 task id와 `status=SUCCESS`로 확인했다. 현재 `PROJECT_ANALYSIS_TOKEN`으로
Issue·Measure·Quality Gate API를 조회하고, token 범위상 403인 Compute Engine API는 container log로 대조한다.

## Before 결과

### 구조

| package | Java 파일 수 |
|---|---:|
| `member.controller` | 1 |
| `member.dto` | 3 |
| `member.service` | 1 |
| `member.entity` | 1 |
| `member.repository` | 1 |
| `auth.controller` | 1 |
| `auth.dto` | 8 |
| `auth.service` | 1 |
| `auth.token` | 2 |
| `common.security` | 8 |
| `common.exception.MemberErrorCode` | 1 |
| 합계 | 28 |

- 테스트: 11 files, 84 tests
- 이전 package 대상 파일: 28
- 외부 MemberRepository 직접 import: 7 production files
- 외부 Member/QMember 직접 import: 10 production files
- Transaction annotation: 6 (`@Transactional` 4, `readOnly = true` 2)
- 명시적 `Propagation`: 0
- Member/Auth API mapping: 9

### 테스트

- 관련 테스트: 84 tests 중 73 pass, Redis 선택 테스트 11 skip
- Redis 선택 테스트 별도 실행: 11 tests, failures 0, errors 0, skipped 0
- 최초 `test --tests ...` 명령은 하위 Lambda 모듈에 같은 filter가 전달돼 `No tests found`로 종료했다.
  루트 `:test` task로 범위를 고정해 위 결과를 다시 측정했다.

### SonarQube

- 분석 SHA: `9d95e57582498359ec6d980e011405116173bea1`
- Gradle 분석: `BUILD SUCCESSFUL`
- Compute Engine: `SUCCESS` (`2d30a176-64c4-4172-a8b3-f17c23e5f863`)
- issue persistence: inserts 0, updates 0

## 변경 내용

- Member 9개 소유 코드: presentation 4, application 1, domain 3, infrastructure 1로 이동
- Auth 19개 소유 코드: presentation 9, application 2, infrastructure 8로 이동
- `MemberRole`, `MemberErrorCode`를 Member domain으로 이동
- `AuthMember`를 Auth application model로 이동
- JWT/Security/Redis 구현을 Auth infrastructure로 이동
- 11개 직접 관련 테스트 package 이동
- 전체 production/test import와 FQCN reference 갱신
- 초기 After Sonar 분석에서 확인된 `java:S1128` 불필요한 import 2개 제거
- 새 Facade/Port/Adapter 또는 비즈니스 로직 변경 없음

## After 결과

### 구조

| package | Java 파일 수 |
|---|---:|
| `member.presentation.controller` | 1 |
| `member.presentation.dto` | 3 |
| `member.application.service` | 1 |
| `member.domain.entity` | 2 |
| `member.domain.exception` | 1 |
| `member.infrastructure.repository` | 1 |
| `auth.presentation.controller` | 1 |
| `auth.presentation.dto` | 8 |
| `auth.application.service` | 1 |
| `auth.application.model` | 1 |
| `auth.infrastructure.security` | 4 |
| `auth.infrastructure.jwt` | 2 |
| `auth.infrastructure.redis` | 2 |
| 합계 | 28 |

- 테스트: 11 files, 84 tests
- 이전 package 잔존: 0
- package declaration/path 불일치: 0
- 외부 MemberRepository 직접 import: 7 production files
- 외부 Member/QMember 직접 import: 10 production files
- Transaction annotation: 6 (`@Transactional` 4, `readOnly = true` 2)
- 명시적 `Propagation`: 0
- Member/Auth API mapping: 9
- Git rename 인식: production 28 files, test 11 files
- Java 변경선: package 78, import 545, FQCN reference 6, import 구분용 빈 줄 1, 예상 밖 코드 0

## build/test/Sonar 회귀 검증

| 검증 | 결과 | 증거·한계 |
|---|---|---|
| `compileJava` | PASS | 전체 main source compile 성공 |
| `compileTestJava` | PASS | 전체 test source compile 성공 |
| 관련 테스트 | PASS | Redis 포함 11 suites, 84 tests, failures/errors/skipped 0 |
| Redis 실제 동작 | PASS | 발급·회전·삭제·TTL·blacklist·장애 전파 11 tests |
| 전체 `clean build` | PASS | root 944 tests, failures/errors 0, skipped 64; Lambda 11 tests 모두 통과 |
| 이전 package 잔존 | PASS | 네 검색 패턴 전체 0건 |
| package/path 정합성 | PASS | 전체 Java source 불일치 0건 |
| Java 변경 범위 | PASS | package/import/FQCN reference 외 코드 변경 0건, whitespace 오류 0건 |
| API/DB/Security/Transaction 보존 | PASS | mapping·annotation 수 동일, Entity/Security 본문 변경 없음, 관련 테스트 통과 |
| Sonar 분석 실행 | PASS | Before/초기 After/수정 After Gradle 및 Compute Engine 모두 SUCCESS |
| Sonar 신규 회귀 판정 | PASS | 초기 신규 S1128 2건을 수정하고 재분석에서 inserts 0, 두 Issue `CLOSED/FIXED`, 대상 미해결 0건 확인 |

### SonarQube 초기 After

- 분석 SHA: `0c733bcd946d4c5a48c5779add7771f578662114`
- Gradle 분석: `BUILD SUCCESSFUL`
- Compute Engine: `SUCCESS` (`061c91c3-83c5-44ae-978d-d28157fe503c`)
- issue persistence: inserts 2, updates 15
- 신규 2건: `java:S1128` (`JwtAuthenticationFilter.java`의 미사용 `MemberRole` import,
  `Member.java`의 같은 package `MemberRole` import)

### SonarQube 수정 After

- 분석 SHA: `0b75b4f4ea978052ddf8f540b809de9b6447590e`
- Gradle 분석: `BUILD SUCCESSFUL`
- Compute Engine: `SUCCESS` (`3a74af2f-4285-4f31-ab62-32661a14db5a`)
- issue persistence: inserts 0, updates 4
- 초기 신규 Issue 2건: 모두 `CLOSED/FIXED`
- 대상 두 파일의 미해결 `java:S1128`: 0
- 초기 After 분석 이후 생성된 미해결 Issue: 0
- 현재 전체 Quality Gate: `ERROR` (기존 전체 Issue를 이번 리팩토링에서 수정하지 않음)

## 결과 해석

Member/Auth/Security production 파일 수와 테스트 수는 유지됐고, 이전 package 참조는 28개 대상 파일에서
0개로 정리됐다. 외부 Member Repository/Entity 직접 참조와 Transaction/API mapping은 Before와 같으며
Java Diff에도 package/import/FQCN reference 외 코드 변경이 없다. 따라서 코드·테스트 기준으로는 위치와
소유권만 변경되고 기존 동작이 보존됐다.

초기 After SonarQube 분석에서 package 이동 중 추가된 불필요한 import 2개가 신규 `java:S1128`로 확인됐다.
해당 import만 제거한 같은 환경의 재분석은 inserts 0이었고, 두 Issue가 모두 `CLOSED/FIXED`, 대상 파일과
초기 After 이후의 미해결 신규 Issue가 0건임을 API로 확인했다. 따라서 이번 package 이동으로 남은 신규
SonarQube 회귀는 없다. 전체 기존 SonarQube Issue와 현재 Quality Gate `ERROR`는 이번 범위에서 수정하지 않는다.

## 검증 한계

- 현재 `PROJECT_ANALYSIS_TOKEN`으로 Issue·Measure·Quality Gate 조회는 가능하지만 Component, Analysis history,
  Compute Engine API는 403이다. Compute Engine 완료와 issue persistence는 같은 로컬 SonarQube container log로
  확인했다.
- 전체 build의 선택적 외부 인프라 테스트 64건은 환경 조건에 따라 skip됐다. Redis 대상 11건은 별도 실제
  실행했으며 Kafka/SMTP/AI/AWS 동작은 이 Issue의 직접 검증 범위가 아니다.
- 실제 배포 환경의 로그인·JWT·Redis session smoke test는 수행하지 않았고 Web/Service/JWT/Redis 테스트와
  변경선 제한 검사로 동작 보존을 확인했다.
- 이 리팩토링은 외부 직접 참조 또는 Transaction 수 감소를 개선 효과로 주장하지 않는다.

## 관련

- [Issue #21](https://github.com/gpekd5/bobfull-backend/issues/21)
- [Master Issue #18](https://github.com/gpekd5/bobfull-backend/issues/18)
- [Issue #10 SonarQube Baseline](../../v3/10-sonarqube-baseline/README.md)
