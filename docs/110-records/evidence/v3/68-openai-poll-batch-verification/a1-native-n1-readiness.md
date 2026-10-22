# Issue #68 — A1 Native N1 연결 및 무과금 안전성

## 범위와 판정

- 2026-10-22 Human 승인: 테스트 전용 Live 연결과 Fake HTTP/Kafka/MySQL 회귀만 수행. **유료 실행 및 API Key 읽기·사용은 미승인/미실행**.
- branch `feature/68-openai-poll-batch-verification`, HEAD `8ffb5aabc1df96e0a20d08b0f29eab89ac069807` + 기존 미커밋 작업 + 이 단계 변경. Commit/Push/PR/Issue 변경 없음.
- Primary: N1 8건 밖의 호출, 불명 Usage 이후 전송, 예산·횟수 초과를 전송 전에 차단.
- Secondary: 정상 및 HOLD의 SDK/HTTP Usage, DB afterCommit, Kafka Offset/Lag와 Metric 보존.
- Guardrail: 기존 A1 문자열/해시, GT/Gate, Production, Dashboard, 기존 Evidence/raw/journal 불변. 과거 Gate V1/V2 HOLD를 PASS로 바꾸지 않음.
- 모델 품질 NOT_EVALUATED. Fake GT echo는 AI 품질 결과가 아니며 N1 성공도 후보 채택이 아니다.

## 고정 승인 대상

- 후보 `issue68-risk-boundary-a1-test-only`.
- Prompt SHA-256 `c4a3f361f7325ac811f9e2f0ce35b3cd1934b536910ac89fbd903ca35e1c7960`.
- [승인 제안 Manifest](a1-native-n1-approval-proposal.json), Manifest SHA-256 `3d959747d3334bdf3d8fbc3ea911cdc8f6e5a09305bd30ce94a0cef915bd26cc`. JSON 파일 자체의 SHA가 아니라 `Issue68A1N1Plan.manifestSha256()`의 객체 직렬화 기준이다. Prompt/GT/Schema 외에 Harness와 Production 소스 해시도 연결했다.
- 고정 순서: PROFANITY-14 → 15 → 16 → 01 → 17 → 13 → SAFE-17 → SAFE-01 (모두 `HOLDOUT-` 접두어).
- 기존 Policy v2 GT 그대로. 앞의 네 건은 FLAGGED/PROFANITY(HIGH/HIGH/HIGH/MEDIUM), 뒤의 네 건은 SAFE/[]/LOW.
- Native 정상 논리 요청 최대8, 실제 HTTP 전체32/논리당4. Batch/N2/Control 재측정/자동 반복/자동 재개 금지.
- 기존20건에 정상 경고 대조군이 부족하다. 신규 합성20 및 #58의40건은 Human GT 동결 전 제외한다. 과거 사용 데이터이며 신규 블라인드 평가가 아니다.

## 테스트 전용 변경

- `Issue68A1N1Plan`: 순서·논리/HTTP 상한 및 GT 기반 V1/V2 동시 기록. Candidate Native의 FP를 기준선 오류로 면제하지 않는다.
- `Issue68A1N1Session`: 고정 root, 별도 승인 변수와 Manifest 해시, 단일 기존 장부 lock, 일회성 claim, append-only checkpoint. 승인 파일 변조 및 재시작은 fail-closed.
- `Issue68Budget`: 기존 hash-chain 장부를 그대로 replay하고 `A1_N1_BEGIN/END`만 새 사건으로 지원. 과거 Pilot BEGIN/HOLD/claim은 종료하거나 삭제하지 않는다. 실제 장부에는 이번 단계에서 append하지 않았다.
- `Issue68HttpBoundary`: 명시 N1 경로에만 A1 허용. 기존 기본/Control 경로는 그대로다. 목적지/본문/Schema/Model/cap/입력 검증, 매 exchange 횟수 검사와 영속 예약을 실제 전송 전에 수행.
- `Issue68OpenAiContext`: 실제 Production Adapter/ChatClient를 사용하고 ChatModel 인터페이스 위임에서 System만 교체. Production Prompt, Model 옵션 및 출력 계약은 변경하지 않는다.
- `Issue68A1N1Execution/LiveTest`: 동일 Native Kafka/MySQL 실행기를 Fake와 향후 별도 승인된 Live에 사용. 유료 승인 변수가 없으면 LiveTest는 SKIP. 실제 인증정보를 사용하는 경로는 이번에 실행하지 않는다.
- Native Commit 호출의 예외/Callback 실패를 테스트 전용 observer가 관측해 HTTP를 차단한다. 테스트 fault는 예외만 주입하며 실행 observer가 HOLD를 설정한다. ACK/Retry/DLT 설정이나 Consumer thread 소유권은 변경하지 않는다.
- Native N1에도 기존 60초 HTTP timeout과 65초 poll lease 여유 검사를 연결한다. 소비 설정은3 partition 중1개 사용/concurrency1/maxPoll10/RECORD/autoCommitFalse, 이전 DB/Offset 완료 후 다음 메시지 발행이다.
- DB `prompt_version`과 기존 업무 Metric은 Production 버전을 유지한다. 실제 wire A1 버전/해시는 별도 Manifest·요청 해시로 식별한다. 이 실험 결과를 Production Prompt 결과로 합산하지 않는다.

## 예산과 재시작 계약

| 기존 장부 항목 | 보존 값 |
|---|---:|
| 정산 | $0.00044625 |
| UNKNOWN | $0.01927680 |
| 부담 합계 | $0.01972305 |
| gross 예약 이력 | $0.07718400 |

- 원본 journal SHA-256 `80607e4746092d22067d61b818e69bd61f493e197f1a6d4d4cc58789c4fa6547` 고정. 최종 테스트는 공개된 과거 금액·이벤트 순서로 byte 동일한 TempDir fixture를 재현하며 private journal 파일 없이 실행할 수 있다. 실제 장부는 수정하지 않는다.
- 신규 진단 전체 부담 최대 $0.10, 기존 부담 포함 전역 $1. 정상 Usage만 정산하며 cache 할인은 보수적 차단 계산에 사용하지 않는다.
- Native HTTP마다 Input128K+Output128의 $0.0192768을 선예약한다. gross는 역사적 합계이지 실제 정산 비용이나 현재 부담이 아니다.
- UNKNOWN/연결 실패/손상/non-2xx 응답은 예약 유지 후 같은 논리의 SDK/하위 Retry도 차단한다. 오류 JSON에 Usage가 있어도 기존 판독 계약상 신뢰하지 않는다.
- Crash 직전 pending도 이월한다. N1 시작 claim과 예산 baseline을 초기화하지 않으며 자동 재개하지 않는다. 향후 N2는 이번 범위 밖이고 누적 $0.10 baseline을 새로 부여해서는 안 된다.
- N1 로컬 Tokenizer 견적 Input10,603/Output cap1,024, cache 미반영 약 $0.00220485. 실제 Usage·청구액·완료 보장 상한이 아니다. UNKNOWN, 예산, HTTP 상한 또는 품질 Gate에 따라 조기 중단될 수 있다.

## 무과금 검증

최종 N1 전용 실행: **43 tests /1 skipped /failures0/errors0** (42 PASS, 유료 N1 1건은 미승인 SKIP). Mock Provider 객체 대신 loopback Fake HTTP와 실제 SDK/ChatClient/Production Adapter, Testcontainers Kafka7.7.1/MySQL8.4/JPA를 사용했다.

| Fake 시나리오 | HTTP | SDK Usage 관측 | DB Commit 관측 | 최종 Lag | Unsafe Commit | DLT |
|---|---:|---:|---:|---:|---:|---:|
| 정상 | 8 | 8 | 8 | 0 | 0 | 0 |
| Critical HIGH→MEDIUM | 1 | 1 | 1 | 0 | 0 | 0 |
| FP (5번째 사례) | 5 | 5 | 5 | 0 | 0 | 0 |
| FN / Category 오류 (각각) | 1 | 1 | 1 | 0 | 0 | 0 |
| Validation / Parse 실패 (각각) | 1 | 1 | 0 | 1 | 0 | 0 |
| Usage 불명 | 1 | 0 | 0 | 1 | 0 | 0 |
| DB 저장 실패 | 1 | 1 | 0 | 1 | 0 | 0 |
| Kafka Commit 실패 | 1 | 1 | 1 | 1 | 0 | 0 |

- 200/의미상 오류는 DB에 원래 응답을 저장한 후 품질 HOLD를 남긴다. 정상 저장이 끝난 현재 record의 Offset 진행을 되돌리지 않는다. 다음 HTTP는 막으며 실제 오류를 GT에 맞춰 보정하지 않는다.
- UNKNOWN/429/503/손상 JSON/응답 유실은 첫 HTTP 뒤 같은 논리의 추가 SDK/하위 재시도를 차단했다. 별도 하위 exchange fixture는 네 번 각각 예약·정산하고 다섯 번째는 HTTP 전에 차단했다. SDK가 정상200을 반복한다는 의미가 아니다.
- HTTP 전체32 예약 이후33번째 거절, 단계·전역 잔액 부족 시 HTTP0, 중복 실행/Crash pending/재시작 차단, 손상·빈 장부/심볼릭 링크/승인 파일 변조 차단을 검증했다.
- gzip 응답과 SDK/HTTP Usage 대조, 실제 wire의 A1/System-only 변경·cap128·입력 순서, HTTPS 목적지/Schema/옵션 변조 거절, Gate V1/V2 분리 기록을 검증했다. HTTPS 검사 객체는 socket을 열 수 없다.
- 정상과 HOLD 모두 Context 종료 전 SDK/HTTP, DB afterCommit, Offset/Lag, Meter snapshot을 append-only checkpoint에 보존했다. Usage가 불명인 경우 SDK 관측이 없다는 사실도 그대로 보존한다.
- 초기 final `OpenAiChatModel` CGLIB 상속 실패는 ChatModel 인터페이스 위임으로 수정했다. non-2xx에 Usage가 있으면 재시도4회를 기대했던 잘못된 테스트는 기존 보수적 판독 계약(UNKNOWN/첫 HTTP 뒤 차단)에 맞게 고쳤다. Prompt·GT·Gate를 바꾸지 않았다.
- 선행 관련 회귀348건/6 skipped/실패0/오류0을 확인했다. 이후 fixture 독립성 및 Commit observer 보완의 최신 검증은 위 N1 전용 실행과 아래 최종 전체 회귀로 구분한다.
- 최신 코드의 최종 `test build --rerun-tasks --no-daemon --console=plain`: **BUILD SUCCESSFUL (6m53s), 12 tasks 모두 executed**. root1,440 tests/92 skipped, Lambda11 tests/0 skipped. **합계1,451 tests/92 skipped/failures0/errors0**. 기본 `kafka-evidence` 제외와 opt-in SKIP는 기존 Gradle 계약을 유지한다.
- `git diff --check`, 신규/untracked 포함12파일 whitespace, 승인 JSON syntax 및 생성 Manifest와의 일치 검증 PASS.
- 시작 시1,257파일 해시와 대조하여 기존 파일 변경은 테스트 Harness4파일에 한정됨을 확인했다. 기존 파일 누락0, Production/GT/Gate/기존 Evidence·raw/journal/Dashboard 변경0. 신규6 Java + 이 Evidence/승인 JSON2파일. 기존 tracked/untracked 작업을 보존했다.
- 신규 유료 호출0/실제 장부 예약·정산0. 위 기존 UNKNOWN·정산·gross·journal 해시 및 A1 동결 파일 SHA-256 `4b1aaaf88f2107824b5339190ae8beb43e5ea3ff17d031bc5f109d27eadf59cd` 유지.

## 재현

```sh
env -u OPENAI_API_KEY \
  -u ISSUE68_A1_N1_LIVE_APPROVAL -u ISSUE68_A1_N1_MANIFEST_SHA256 \
  -u ISSUE68_LIVE_APPROVAL \
  -u ISSUE68_O0_LIVE_APPROVAL -u ISSUE68_O0_MANIFEST_SHA256 \
  -u ISSUE68_PILOT_LIVE_APPROVAL -u ISSUE68_PILOT_MANIFEST_SHA256 \
  -u ISSUE68_LOCAL_MONITORING -u ISSUE68_DASHBOARD_CAPTURE \
  ./gradlew :test --tests '*Issue68*Test' \
  --tests '*ModerationPromptTest' --tests '*ModerationHeldoutDatasetTest' \
  --tests '*ChatModerationServiceTest' --tests '*ChatModerationBatch*Test' \
  --no-daemon --console=plain
```

전체 회귀는 같은 환경변수 제거 조건에서 `./gradlew test build --rerun-tasks --no-daemon --console=plain`. 기존 raw를 지우지 않으므로 clean은 실행하지 않는다.

## 남은 위험과 다음 승인

- HTTPS URL/요청 검사는 TLS socket/Key 없이 수행했다. 실제 계정 모델 접근, 인증, OpenAI Usage/품질은 이번에 검증하지 않는다.
- 로컬 Fake의 고정 Usage900/20은 회계 fixture다. Prompt 비용이나 성능 개선 실측으로 해석하지 않는다.
- Context 종료 전 DB/Offset/Metric snapshot을 checkpoint로 보존한다. Testcontainers 종료 뒤의 영속 DB 복구나 운영 재균형을 입증하는 테스트가 아니다.
- 문맥 의존 PROFANITY-16 HIGH→MEDIUM은 GT/Risk Exact/V1 오류를 남기고, 기존 승인된 V2의 검토 신호 유지 조건을 별도로 기록한다. Critical14/15 하향은 차단한다.
- 과거 Control HOLD는 유지한다. N1은 새로운 후보 진단일 뿐 Control 실패를 우회하여 Pilot을 재개하는 절차가 아니다.
- 별도 Human 유료 승인이 있어야 N1을 실행할 수 있다. 성공하더라도 N2/Batch/Control/반복 및 기존 Pilot을 자동 실행하지 않는다.
