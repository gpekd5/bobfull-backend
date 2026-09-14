# Issue #24 RestaurantInsight 패키지 및 API 소유권 정리 Evidence

## 1. 검증 대상

- Issue: `#24 [project][refactor] RestaurantInsight 패키지 및 API 소유권 정리`
- Before: `develop` `2e9b6c3197efeaeae185274d5f2632b6efccd44e`
- After source commit: `4b16f29` (`refactor/24-restaurant-insight-packages`)
- 검증일: 2026-09-14 (Asia/Seoul)
- 목적: RestaurantInsight의 위치와 API 소유권만 정리하고 기존 동작과 계약이 보존됐는지 확인한다.

## 2. 측정 방법

추정값을 사용하지 않고 다음 기준으로 측정했다.

- Java 파일 수: `Get-ChildItem <경로> -Recurse -Filter '*.java'`
- 패키지별 파일 수: 각 최상위 하위 디렉터리에서 위 명령을 반복 집계
- 기존 FQCN: `rg`로 `com.bobfull.restaurantinsight.(adapter|config|dto|entity|port|repository|service)`와 기존 Kafka FQCN 검색
- package/path 일치: 모든 main/test Java 파일의 첫 `package` 선언을 경로로 변환해 실제 절대 경로와 비교
- endpoint 소유권: `feedback-insights`를 두 Controller에서 각각 `Select-String`으로 집계
- annotation/기술 계약: `rg` 및 `git show <Before>:<path>`로 Before를 읽고 현재 파일과 문자열 비교
- 테스트 수: Gradle XML의 `tests`, `failures`, `errors`, `skipped` 속성 합계
- SonarQube: 동일 서버, project key `bobfull-backend`, 동일 Gradle 명령과 API 검색 조건을 사용

Sonar 범위는 RestaurantInsight 전체 경로, 이동 전 top-level RestaurantInsight Kafka 3개 파일, Restaurant Owner Controller와 해당 WebMvc 테스트로 고정했다.

## 3. 패키지 Before / After

### Production Java

| 측정 항목 | Before | After |
|---|---:|---:|
| `restaurantinsight` 내부 Java 파일 | 21 | 25 |
| `adapter` | 2 | 0 |
| `config` | 1 | 0 |
| `dto` | 3 | 0 |
| `entity` | 7 | 0 |
| `port` | 1 | 0 |
| `repository` | 2 | 0 |
| `service` | 5 | 0 |
| `presentation` | 0 | 3 |
| `application` | 0 | 4 |
| `domain` | 0 | 10 |
| `infrastructure` | 0 | 8 |
| top-level Kafka의 RestaurantInsight 전용 파일 | 3 | 0 |
| 기존 패키지 잔존 Java 파일 | 21 | 0 |

After 25개는 Controller 1, response DTO 2, application DTO 1, Port 1, Service 2, Entity/Enum 7, Policy 3, Repository 2, AI 3, Kafka 3이다. 파일 수 증가는 기존 Restaurant Controller에 있던 endpoint를 전용 Controller 1개로 분리한 결과다.

### Test Java

| 측정 항목 | Before | After |
|---|---:|---:|
| `restaurantinsight` 내부 테스트 파일 | 5 | 11 |
| top-level Kafka의 RestaurantInsight 전용 테스트 파일 | 5 | 0 |
| Owner Restaurant Controller 내 Insight endpoint 테스트 | 3 | 0 |
| RestaurantInsight 전용 Controller 테스트 | 0 | 3 |

기존 endpoint 테스트 3건은 assertion과 보안 조건을 유지한 채 새 Controller 테스트로 이동했다. Owner Restaurant Controller 자체 테스트 파일은 계속 남아 나머지 Restaurant API를 검증한다.

## 4. 구조 정합성

| 검사 | 결과 |
|---|---:|
| main/test 전체 package/path 불일치 | 0 |
| 기존 RestaurantInsight FQCN 잔존 | 0 |
| top-level RestaurantInsight Kafka production 잔존 | 0 |
| top-level RestaurantInsight Kafka test 잔존 | 0 |
| 기존 Restaurant Controller의 Insight endpoint | 0 |
| RestaurantInsight Controller의 Insight endpoint | 1 |

## 5. 기존 동작 Guardrail

| 항목 | Before | After | 결과 |
|---|---|---|---|
| HTTP mapping | `GET /api/owner/restaurants/{restaurantId}/feedback-insights` | 동일 | PASS |
| 권한 | `/api/owner/**`는 `ROLE_OWNER` | `SecurityConfig` 변경 0, WebMvc 401/403/200 검증 | PASS |
| response | 익명 집계 `RestaurantFeedbackInsightListResponse` | 동일 DTO와 assertion | PASS |
| JPA | `restaurant_feedback_analysis`, `restaurant_feedback_item` Entity mapping | package 선언 외 mapping 변경 0 | PASS |
| 집계/AI/실패 처리 | 기존 Service와 Provider 구현 | package/import 및 FQCN 정리 외 로직 변경 0 | PASS |
| Transaction | `@Transactional`, `readOnly=true`, `REQUIRES_NEW` | 세 위치와 propagation 동일 | PASS |
| Kafka Listener | topic, group, factory, concurrency 기존 값 | annotation 문자열 동일 | PASS |
| Retry/DLT | attempts `3`, backoff `1000`, DLT `bobfull.restaurant-insight.dlt.v1` | 동일 | PASS |
| Consumer 오류 처리 | `CustomException`, `InvalidChatMessageEventException`, DLT recoverer | 동일 | PASS |
| AI Provider 활성화 | `bobfull.ai.restaurant-insight.enabled=true` 조건 | 동일 | PASS |

`RestaurantInsightAspectCanonicalizer`는 `domain.policy`로 이동하면서 application package에서 호출할 수 있도록 class와 static method의 가시성만 `public`으로 변경했다. switch 분기와 반환값은 변경하지 않았다.

RestaurantInsight Kafka가 Chat 소유 `InvalidChatMessageEventException`을 직접 참조하는 기존 관계는 계약 변경 없이 보존했다. 이 의존 방향의 재검토는 2차 책임/의존성 리팩터링 대상이다.

## 6. 테스트와 Build

| 명령 | 결과 | 측정값 |
|---|---|---|
| `.\gradlew.bat compileJava compileTestJava` | PASS | 3초 |
| `.\gradlew.bat :test --tests "com.bobfull.restaurantinsight.*" --tests "com.bobfull.restaurant.restaurant.presentation.controller.OwnerRestaurantControllerWebTest" --rerun-tasks` | PASS | 11개 실행 클래스, 56 tests, failure/error/skip 0 |
| `.\gradlew.bat clean build` | PASS | 3분 30초, root 944 + Lambda 11 = 955 tests, failure/error 0, skip 64 |

처음에는 필터를 공통 `test` task에 적용해 루트 대상 테스트가 통과한 뒤 테스트가 없는 Lambda 하위 모듈에서 `No tests found`로 명령이 종료됐다. 제품 실패가 아니라 multi-project task 선택 문제였으며, 루트 `:test`로 한정해 같은 대상을 다시 실행하고 PASS를 확인했다.

## 7. SonarQube Before / After

동일 명령을 사용했다.

```powershell
.\gradlew.bat clean classes testClasses sonar `
  "-Dsonar.host.url=$env:SONAR_HOST_URL" `
  "-Dsonar.token=$env:SONAR_TOKEN" `
  "-Dsonar.scm.revision=<검증 SHA>"
```

토큰 값은 출력하거나 기록하지 않았다.

| 항목 | Before | After |
|---|---:|---:|
| CE task | `9d8a9bc4-23b5-4a4c-b370-3827c5b97820` | `dde58b54-2f11-4cd7-b567-d0cc609500a0` |
| analysis | `92baadc9-f5f8-4923-8505-4b323e793172` | `cd51994d-bf7a-4a8f-b764-f8928154e551` |
| CE status | SUCCESS | SUCCESS |
| Quality Gate | ERROR | ERROR |
| 프로젝트 전체 open | 326 | 326 |
| 프로젝트 Bug / Code Smell / Vulnerability | 14 / 311 / 1 | 14 / 311 / 1 |
| #24 범위 open | 42 | 42 |
| #24 범위 Bug / Code Smell / Vulnerability | 3 / 39 / 0 | 3 / 39 / 0 |
| 규칙별 분포 | 19개 규칙, 동일 분포 | 동일 | 
| 실제 신규 회귀 | - | 0 |

규칙별 분포는 `S1128=2`, `S1659=6`, `S2259=1`, `S2589=2`, `S2925=1`, `S3776=1`, `S5738=1`, `S5778=1`, `S5843=3`, `S5853=1`, `S5854=7`, `S5866=2`, `S6035=1`, `S6213=4`, `S6353=1`, `S6395=2`, `S8786=3`, `S8924=2`, `S9358=1`로 Before/After가 같다.

### 경로 이동에 따른 재등록

42건 중 40건은 기존 issue key가 새 경로로 그대로 이전됐다. 다음 2건만 새 key로 재등록됐지만 메시지와 대상 코드가 Before와 동일하다.

| 규칙 | Before | After | 판정 |
|---|---|---|---|
| `S1128` | `02a58028-863b-43e1-9bb5-4c06ebd63216`, 기존 Service의 미사용 `RestaurantFeedbackPrompt` import | `1ef4e33a-1947-4804-a4eb-6b92ab10a8a9`, 이동된 동일 Service/import | 기존 issue 재키잉 |
| `S3776` | `c3f4f875-de12-4c09-9707-7c992d87e025`, complexity 18/15 | `7c3e09eb-0293-4cc2-99b2-1cb08c1a81bf`, 동일 method와 수치 | 기존 issue 재키잉 |

Quality Gate `ERROR`는 기존 프로젝트 issue 기준이며 #24 패키지 이동으로 수량이나 유형이 증가한 결과가 아니다. 이번 범위 밖 기존 Sonar issue는 수정하지 않았다.

## 8. 검증 한계

- 실제 AWS 배포와 외부 운영 환경 검증은 수행하지 않았다.
- Testcontainers Kafka 통합 테스트는 실행했지만 `RESTAURANT_INSIGHT_LOCAL_BROKER_TEST=true`가 필요한 별도 실제 로컬 broker backfill Evidence 테스트는 실행하지 않았다.
- 실제 외부 LLM 호출은 수행하지 않았으며 기존 mock/conditional-off 테스트로 Provider 계약과 실패 경로를 검증했다.
- SonarQube는 기존 Baseline과 같은 로컬 서버/project에서 수행했으며 Quality Gate의 기존 전체 issue를 해결하는 작업은 아니다.
- 이 Evidence는 위치/소유권 변경의 회귀 확인 자료이며 성능, 비용, 운영 개선 효과를 주장하지 않는다.
