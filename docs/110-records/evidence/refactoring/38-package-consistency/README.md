# Issue #38 전체 패키지 구조 정합성 점검 Evidence

## 검증 대상

- [Issue #38](https://github.com/gpekd5/bobfull-backend/issues/38)
- 전체 production package 이름과 실제 파일 책임의 정합성
- 명백한 위치 누락 2건의 package 이동
- 실제 Repository Root와 Java/docs/ops/AI 문서 구조 안내

이번 작업은 모든 기능을 같은 package 모양으로 통일하지 않는다. 파일 위치만으로 해결할 수 있는 누락만
수정하고 책임·의존성 재설계는 후속 Issue로 분리한다.

## 유지할 기존 동작

- 모든 HTTP/WebSocket API path, request, response, status
- DB schema와 JPA Entity mapping
- 비즈니스 정책과 상태 전이
- Transaction boundary, Propagation과 Lock 순서
- 인증·인가와 Security filter 동작
- Event/Kafka/Outbox/Redis/외부 연동 계약
- 기존 cross-domain 호출과 Port/Adapter 계약

## 기준 코드

| 구분 | SHA | 설명 |
|---|---|---|
| Before | `ee81a966a503b58534c0cd3b64a993f42294ac7a` | Issue #37이 merge된 최신 `develop` |
| After | `3ca05d7b8493851d1751ccd14da711ebfe9fa162` | 두 package 이동과 구조 문서 작성 구현 commit |

## 환경·실행 조건

- Windows PowerShell
- Java 17, Gradle Wrapper 9.5.1
- Docker Desktop 29.6.1
- 기존 `docker-compose.sonar.yml`의 SonarQube와 project `bobfull-backend`
- 현재 `SONAR_TOKEN`, `SONAR_HOST_URL`을 사용했으며 값은 출력하거나 기록하지 않았다.
- Before와 After는 같은 worktree, Gradle plugin, Sonar project와 미해결 Issue 조건으로 측정했다.

## 측정 방법

### Package와 파일 수

```powershell
Get-ChildItem src/main/java/com/bobfull -Recurse -Filter *.java

Get-ChildItem src/main/java/com/bobfull -Recurse -Filter *.java |
  Group-Object {
    (Select-String -Path $_.FullName -Pattern '^package\s+([^;]+);').Matches[0].Groups[1].Value
  }
```

package/path mismatch는 각 Java 파일의 `package`를 경로로 변환한 예상 절대경로와 실제 절대경로를
비교했다. old FQCN은 아래 두 문자열을 `src`, `docs`, `README.md`에서 `rg`로 검색했다.

```text
com.bobfull.reservation.application.service.ReservationCompletionTestHook
com.bobfull.chat.application.service.ModerationAnalysisException
```

### 의존성과 동작 Guardrail

- 각 production Java의 source top-level package와 `import com.bobfull.<target>`을 비교했다.
- 다른 top-level 기능을 향하는 import를 Repository, Entity, Port, 기타로 분류했다.
- base Diff에서 Controller, domain Entity, Security 파일과 `@Transactional`, `Propagation`, `@Lock`
  변경 여부를 검색했다.
- 이동 파일은 첫 package declaration을 제외한 본문을 Before와 After에서 직접 비교했다.

### SonarQube

```powershell
.\gradlew.bat clean classes testClasses sonar `
  "-Dsonar.host.url=$env:SONAR_HOST_URL" `
  "-Dsonar.token=$env:SONAR_TOKEN" `
  "-Dsonar.scm.revision=<검증 SHA>" `
  --no-daemon
```

각 분석 뒤 `/api/issues/search?componentKeys=bobfull-backend&resolved=false&ps=500&p=1` 결과에서
key, rule, severity, type, component, line, message와 status를 비교했다.

## Before 결과

### 전체 규모

| 항목 | Before |
|---|---:|
| Production Java 파일 | 401 |
| Java 파일이 있는 package | 111 |
| package/path mismatch | 0 |
| Cross-domain import | 244 |
| Cross-domain Repository import | 49 |
| Cross-domain Entity import | 139 |
| Cross-domain Port import | 9 |
| Cross-domain 기타 import | 47 |

| Package | 파일 수 |
|---|---:|
| `admin` | 55 |
| `auth` | 19 |
| `chat` | 74 |
| `common` | 18 |
| `member` | 9 |
| `notification` | 10 |
| `payment` | 56 |
| `reservation` | 74 |
| `restaurant` | 60 |
| `restaurantinsight` | 25 |
| `com.bobfull` bootstrap | 1 |

### 확인된 위치 누락

| 현재 위치 | 클래스 | 실제 책임 | 판단 |
|---|---|---|---|
| `reservation.application.service` | `ReservationCompletionTestHook` | application이 호출하고 infrastructure가 구현하는 확장 계약 | `application.port`가 적합 |
| `chat.application.service` | `ModerationAnalysisException` | moderation application 실패를 app/Kafka 경계에 전달하는 예외 | `application.exception`이 적합 |

## 변경 내용

| Before | After | 변경 성격 |
|---|---|---|
| `reservation.application.service.ReservationCompletionTestHook` | `reservation.application.port.ReservationCompletionTestHook` | 기존 Port 계약 위치 정리 |
| `chat.application.service.ModerationAnalysisException` | `chat.application.exception.ModerationAnalysisException` | application 예외 위치 정리 |

- 두 클래스 본문과 접근성은 package declaration을 제외하고 동일하다.
- 호출부·구현체·테스트의 import만 새 FQCN으로 변경했다.
- [project-structure.md](../../../../040-architecture/project-structure.md)에 실제 Root, Java, docs, ops와 AI
  작업 문서 구조를 작성했다.
- [README](../../../../../README.md)에는 주요 Root 구조 요약과 상세 구조 문서 링크만 추가했다.

## After 전체 package tree

괄호 안 숫자는 해당 package의 production Java 파일 수다.

```text
com.bobfull (1)
├─ admin (55)
│  ├─ application.model (7)
│  ├─ application.service (9)
│  ├─ infrastructure.query (12)
│  ├─ presentation.controller (9)
│  └─ presentation.dto (18)
├─ auth (19)
│  ├─ application.model (1)
│  ├─ application.service (1)
│  ├─ infrastructure.jwt (2)
│  ├─ infrastructure.redis (2)
│  ├─ infrastructure.security (4)
│  ├─ presentation.controller (1)
│  └─ presentation.dto (8)
├─ chat (74)
│  ├─ application.dto (2)
│  ├─ application.event (1)
│  ├─ application.exception (1)
│  ├─ application.port (3)
│  ├─ application.service (11)
│  ├─ domain.entity (11)
│  ├─ domain.exception (1)
│  ├─ infrastructure.adapter (2)
│  ├─ infrastructure.ai (5)
│  ├─ infrastructure.kafka (6)
│  ├─ infrastructure.outbox (5)
│  ├─ infrastructure.redis (5)
│  ├─ infrastructure.repository (4)
│  ├─ infrastructure.websocket (5)
│  ├─ presentation.controller (4)
│  ├─ presentation.dto (7)
│  └─ presentation.exception (1)
├─ common (18)
│  ├─ config (2)
│  ├─ entity (1)
│  ├─ exception (4)
│  ├─ monitoring (2)
│  ├─ outbox.entity (3)
│  ├─ outbox.repository (1)
│  ├─ outbox.service (1)
│  ├─ privacy (1)
│  ├─ response (2)
│  └─ transaction (1)
├─ member (9)
│  ├─ application.service (1)
│  ├─ domain.entity (2)
│  ├─ domain.exception (1)
│  ├─ infrastructure.repository (1)
│  ├─ presentation.controller (1)
│  └─ presentation.dto (3)
├─ notification (10)
│  ├─ infrastructure.outbox (9)
│  └─ infrastructure.smtp (1)
├─ payment (56)
│  ├─ application.dto (2)
│  ├─ application.port (8)
│  ├─ application.service (11)
│  ├─ domain.entity (5)
│  ├─ domain.exception (2)
│  ├─ infrastructure.adapter (9)
│  ├─ infrastructure.config (3)
│  ├─ infrastructure.repository (2)
│  ├─ infrastructure.scheduler (2)
│  ├─ presentation.controller (5)
│  └─ presentation.dto (7)
├─ reservation (74)
│  ├─ application.dto (5)
│  ├─ application.port (5)
│  ├─ application.service (14)
│  ├─ domain (1)
│  ├─ domain.entity (6)
│  ├─ domain.exception (1)
│  ├─ domain.policy (1)
│  ├─ infrastructure.adapter (5)
│  ├─ infrastructure.repository (11)
│  ├─ infrastructure.scheduler (2)
│  ├─ presentation.controller (6)
│  └─ presentation.dto (17)
├─ restaurant (60)
│  ├─ image
│  │  ├─ application.port (1)
│  │  ├─ application.service (1)
│  │  ├─ domain.exception (1)
│  │  ├─ domain.policy (1)
│  │  ├─ infrastructure.adapter (1)
│  │  ├─ infrastructure.config (2)
│  │  ├─ infrastructure.storage (1)
│  │  ├─ presentation.controller (1)
│  │  └─ presentation.dto (2)
│  ├─ restaurant
│  │  ├─ application.service (1)
│  │  ├─ domain.entity (2)
│  │  ├─ domain.exception (1)
│  │  ├─ infrastructure.cache (3)
│  │  ├─ infrastructure.repository (3)
│  │  ├─ presentation.controller (2)
│  │  └─ presentation.dto (8)
│  ├─ sharedtable
│  │  ├─ application.port (2)
│  │  ├─ application.service (2)
│  │  ├─ domain.entity (2)
│  │  ├─ domain.exception (1)
│  │  ├─ infrastructure.adapter (1)
│  │  ├─ infrastructure.repository (1)
│  │  ├─ presentation.controller (1)
│  │  └─ presentation.dto (5)
│  └─ timeslot
│     ├─ application.port (1)
│     ├─ application.service (2)
│     ├─ domain.entity (1)
│     ├─ domain.exception (1)
│     ├─ infrastructure.repository (1)
│     ├─ presentation.controller (1)
│     └─ presentation.dto (7)
└─ restaurantinsight (25)
   ├─ application.dto (1)
   ├─ application.port (1)
   ├─ application.service (2)
   ├─ domain.entity (7)
   ├─ domain.policy (3)
   ├─ infrastructure.ai (3)
   ├─ infrastructure.kafka (3)
   ├─ infrastructure.repository (2)
   ├─ presentation.controller (1)
   └─ presentation.dto (2)
```

## After 결과

| 항목 | Before | After | 결과 |
|---|---:|---:|---|
| Production Java 파일 | 401 | 401 | 동일 |
| Java 파일이 있는 package | 111 | 112 | `chat.application.exception` 신설 |
| `reservation.application.service` | 15 | 14 | Hook 이동 |
| `reservation.application.port` | 4 | 5 | Hook 이동 |
| `chat.application.service` | 12 | 11 | Exception 이동 |
| `chat.application.exception` | 0 | 1 | Exception 이동 |
| package/path mismatch | 0 | 0 | PASS |
| old Hook FQCN | 존재 | 0 | PASS |
| old Exception FQCN | 존재 | 0 | PASS |
| Cross-domain import | 244 | 244 | Guardrail 동일 |
| Repository / Entity / Port / 기타 | 49 / 139 / 9 / 47 | 49 / 139 / 9 / 47 | Guardrail 동일 |

### 동작 보존 정적 Guardrail

| 항목 | 결과 | 근거 |
|---|---|---|
| 이동 클래스 본문 | 동일 | package 첫 줄을 제외한 Before/After text 비교 |
| Controller/API mapping | 변경 0 | Controller Diff 0 |
| DB/JPA mapping | 변경 0 | domain Entity Diff 0 |
| Transaction/Lock | 변경 0 | annotation Diff line 0 |
| Security | 변경 0 | security package Diff 0 |
| Event/Kafka/Outbox 계약 | 변경 0 | Kafka 변경은 Exception import 1줄뿐이며 event/outbox 파일 Diff 없음 |
| Port/Adapter 계약 | 변경 0 | 기존 Hook interface와 구현 본문 유지, 새 추상화 없음 |

## 유지한 예외

- `reservation.domain.CancellationScope`: Entity가 아닌 독립 도메인 Enum이므로 `domain` 직속 유지
- Admin: 독자 Entity가 없어 `domain` 미생성
- Notification: Email Outbox/SMTP 전달 구현만 있어 infrastructure-only 유지
- `MemberModerationReviewStatus`: HTTP query/response와 application/query 조건을 함께 사용해 단순 이동하지 않음
- Chat moderation filter/gate/context/validator: package-private 협력과 AI 책임 경계가 있어 현재 application 유지
- `ChatMessageAsyncModerationDispatcher`: 위치만 옮기면 application과 infrastructure 의존 방향이 악화돼 유지
- `RestaurantFeedbackInsightWriter`: `REQUIRES_NEW` 저장 경계를 제공하는 application collaborator로 유지
- Adapter의 `adapter`, `ai`, `smtp` 하위 package 차이: 구현 기술과 소유 기능을 드러내는 의도적 차이

## #15로 넘긴 항목

- application `dto`와 `model` 구분 및 이름 기준
- Entity/Enum/Value Object package와 타입 이름 기준
- Service/Calculator/Processor/Policy 접미사 기준
- Annotation과 코드 작성 방식의 전역 일관성
- 현재 실제 계층 구조와 충돌하는 `docs/050-engineering/code-convention.md` 갱신

## #20으로 넘긴 설계 부채

- cross-domain Repository/Entity 직접 참조와 협력 계약
- `MemberRepository -> AdminMemberRepository` Repository fragment 역방향 의존
- Reservation/Notification enqueue와 Email delivery model 결합
- Reservation/Payment/TimeSlot 협력 방향
- RestaurantInsight의 Chat/Reservation/Restaurant Repository 직접 참조
- `InvalidChatMessageEventException`의 cross-domain 공유와 Event/Kafka 소유권
- application/infrastructure의 presentation DTO 하향 의존
- `GlobalExceptionHandler`의 PortOne 전용 분기
- `OutboxEventType`의 Chat/Email 도메인 결합
- Chat AI 전처리/Rule/Provider/Adapter와 async dispatcher 책임 경계
- Restaurant cache와 Chat Redis payload의 application/presentation 의존 방향

## build/test/Sonar 회귀 검증

| 검증 | 명령 | 결과 |
|---|---|---|
| 관련 테스트 | `gradlew :test --tests <5 classes>` | 5 classes, 41 tests, failures/errors 0, skipped 1 |
| Compile | `gradlew compileJava` | PASS |
| Test compile | `gradlew compileTestJava` | PASS |
| 전체 build | `gradlew clean build --no-daemon` | PASS, 3m 56s, 14 tasks |
| Backend 전체 테스트 | clean build XML 집계 | 214 suites, 945 tests, failures/errors 0, skipped 64 |
| Lambda 테스트 | clean build XML 집계 | 3 suites, 11 tests, failures/errors/skipped 0 |
| Markdown 링크 | `ops/tools/check-markdown-links.ps1` | 125 files, 190 relative links, broken 0 |

관련 테스트의 1건 SKIP은 외부 production AI Provider 조건이 없는 기존 테스트다. 첫 실행에서 root `test`는
완료됐지만 전역 `--tests` 필터가 Lambda module에도 전달되어 같은 이름의 Lambda 테스트가 없다는 이유로
Gradle 전체 명령이 실패했다. 검증 대상을 root `:test`로 한정해 다시 실행했고 PASS를 확인했다.

### SonarQube Before / After

| 유형 | Before | After | 변화 |
|---|---:|---:|---:|
| 전체 | 326 | 326 | 0 |
| Bug | 14 | 14 | 0 |
| Vulnerability | 1 | 1 | 0 |
| Code Smell | 311 | 311 | 0 |

- Before/After Gradle Sonar task PASS
- Issue key 추가 0, 제거 0
- 이동한 두 파일의 Issue 0
- 기존 Issue 4건은 테스트 파일 import 추가로 component 내 line만 1 증가했고 key, rule, severity, type,
  message와 status는 유지됐다.
- package 이동으로 인한 재키잉과 실제 신규 회귀 0

## 결과 해석

두 클래스가 현재 책임을 드러내는 package로 이동했으며 production 파일 수와 외부 계약은 유지됐다.
새 package를 일률적으로 추가하지 않았고 기능 특성에 따른 예외를 문서화했다. 전체 build와 동일 Sonar
비교에서 package/import 변경으로 인한 동작·정적 분석 회귀는 확인되지 않았다.

## 검증 한계

- package 책임은 정적 코드·사용처·기존 계약을 기준으로 검증했으며 실제 AWS 배포는 수행하지 않았다.
- API, Kafka, SMTP, Redis와 PortOne 외부 환경을 실제 호출하지 않았다. 이 Issue는 해당 코드와 설정을
  변경하지 않았고 관련·전체 테스트 및 Diff Guardrail로 비변경을 확인했다.
- SonarQube Compute Engine 상세 조회 API는 현재 token 권한으로 403이었지만 Gradle 분석은 성공했고
  project Issue API 326건 전체를 Before/After로 비교할 수 있었다.
- 기존 SonarQube Issue 326건은 이번 package 정합성 작업에서 수정하지 않았다.
- 설계 부채의 존재를 기록했지만 개선 효과나 의존성 감소를 주장하지 않는다.

## 관련

- Issue: [#38](https://github.com/gpekd5/bobfull-backend/issues/38)
- 선행: [#37](https://github.com/gpekd5/bobfull-backend/issues/37)
- 네이밍·Convention 후속: [#15](https://github.com/gpekd5/bobfull-backend/issues/15)
- 책임·의존성 후속: [#20](https://github.com/gpekd5/bobfull-backend/issues/20)
- PR: Draft PR 생성 후 연결
