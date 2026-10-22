# Issue #68 — Usage 판독 및 Attempt별 정산 무과금 보완

## 범위와 과거 기록

- 이번 단계는 테스트 전용 Harness 수정과 무과금 회귀다. 실제 OpenAI/모델 조회/API Key 읽기/AWS/Production 변경은 수행하지 않는다.
- Phase 1 README/verification-results.json 및 기존 Live 문서·원본은 수정하지 않는다. 과거 Live는 Native1 이후 USAGE_UNKNOWN으로 HOLD한 사실 그대로다.
- 기존 `.issue68-live/budget.jsonl`과 claim/checkpoint를 열어 정산하거나 재작성하지 않는다. 과거 미확정 예약 **$0.0192768**을 유지한다.
- 기준 HEAD `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`, 브랜치 `feature/68-openai-poll-batch-verification`. 미커밋 snapshot이며 실행 소스 해시는 별도 원본에 보존한다.
- GT/Native·Batch 계약/Prompt/Policy/Rule/Model/기본 Batch OFF는 변경하지 않는다.

## 확인된 원인과 미확정 사항

1. 기존 코드는 network interceptor의 `response.peekBody(...).string()`을 곧바로 JSON으로 해석했다.
2. 실제 dependency인 OkHttp4.12.0의 BridgeInterceptor는 network interceptor가 반환한 다음 transparent gzip을 해제한다. 따라서 같은 응답을 SDK는 파싱해도 network 계측은 압축 bytes를 읽을 수 있다.
3. 수정 전 실제 SDK/Fake gzip 자동 테스트에서 SDK Token900/20/920은 정상이고 별도 Usage는 null인 실패를 재현했다. 이는 **gzip fixture의 확정 재현**이다.
4. 과거 Live 응답의 Content-Encoding은 기록하지 않았다. gzip이 그 Live 요청의 확정 원인이라고 소급 주장하지 않는다.
5. `peekBody`는 원본 source의 읽기 위치를 소비하지 않는 복사본이다. SDK가 읽는 원본 body와 HTTP header는 정상 응답에서 그대로 유지한다.

Dependency 근거: `okhttp-4.12.0-sources.jar`의 `BridgeInterceptor.kt`, `Response.kt::peekBody`. 공개 Usage 계약은 [OpenAI Chat Completions 문서](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)와 대조했다.

## 후보 비교 및 선정

| 후보 | 장점 | 위험/판단 |
|---|---|---|
| Application interceptor/SDK 최종 응답에서만 정산 | 자동 압축 해제 후 JSON 접근 가능 | 하위 HTTP follow-up의 중간 응답·예약과 최종 응답의 연결을 별도로 관리해야 함. SDK 최종 Usage만으로 전체 attempt 비용을 알 수 없음 |
| Accept-Encoding을 강제로 identity로 변경 | 단순함 | 요청의 전송 특성을 바꾸며 압축 판독 결함을 회피할 뿐 해결하지 않음. 미선정 |
| network 응답의 bounded 복사본을 encoding별 해석 | 기존 Attempt별 예약 소유권 유지, 중간 응답 분리, 원본 body 보존 | wire/decoded 크기와 gzip 무결성 검사 필요. **선정** |

## 수정 전후

| 단계 | Before | After |
|---|---|---|
| 전송 직전 | exchange별 예약·fsync | 동일 |
| Usage 판독 | wire peek를 바로 문자열 JSON 파싱 | identity/gzip 판별 → bounded 복사본 → gzip일 때 복사본만 해제 → 엄격한 Usage 검증 |
| Body | SDK가 후속 처리 | 동일 원본 body를 SDK가 처리. 재요청하거나 body를 교체하지 않음 |
| 정산 | 판독과 정산을 같은 catch 범위에서 처리 | 판독은 Usage 또는 null 반환, 해당 Ticket을 정확히 한 번 정산. 장부 오류는 별도 전송 차단 |
| 실패 | 불명 예약 유지 | 동일. 오류 status/누락/손상/상한초과/미지원 encoding은 전액 유지 |
| 기록 | encoding 정보 없음 | 새 Attempt에 안전한 encoding 분류와 SETTLED/UNKNOWN_KEEP/SETTLEMENT_FAILED 기록 |

## 구현 경계

- `Issue68ResponseUsage`: 원본 wire와 압축 해제 bytes를 각각1MiB 제한. gzip EOF/CRC, 필수 정수/상한/합계/cache 범위를 검증하고 중복 JSON key/후행 JSON을 거절한다.
- 지원 encoding은 identity/gzip이다. br/deflate/다중 encoding은 임의 해석하지 않고 UNKNOWN_KEEP한다. Cached Input이 없으면 null이며0 hit으로 확정하지 않는다. 입력/출력이 유효하면 cache 할인을 적용하지 않는 정산은 가능하다.
- `Issue68HttpBoundary`: 선예약 위치와 Retry/목적지/요청 해시 제한은 유지. Body 판독 실패와 journal 정산 실패를 분리해 이중 정산을 방지한다.
- 오류 redaction으로 body를 교체하는 경우에만 Content-Encoding/Length를 제거한다. 바뀐 비압축 body에 기존 gzip header가 남아 SDK가 잘못 재해제하지 않게 하며 status/Retry 헤더는 유지한다.
- `Issue68FakeOpenAiEndpoint`: gzip, 외부 JSON 손상, 응답 전 연결 종료, 정산 I/O 실패 fixture만 추가했다. 기본 응답은 기존 비압축이다.
- `Issue68OpenAiKafkaIntegrationTest`: 압축 응답의 Native1/Batch2와 실제 MySQL Commit/Offset 검증 추가. 테스트 raw는 고유 파일명+CREATE_NEW로 기록하여 과거 build raw도 덮어쓰지 않는다.
- 실제 Live journal 복원 테스트는 원본을 수정하지 않는다. 별도 fixture에서 동일 $0.0192768 UNKNOWN_KEEP 상태를 만들고, 재시작 후 새 정상 응답으로 과거 예약을 해제할 수 없음을 검증한다.

## 검증 결과

실제 명령/집계/원본은 [usage-accounting-results.json](usage-accounting-results.json)에 보존했다. Fake Usage는 고정 fixture 값이며 실제 OpenAI Token/청구/운영 품질을 의미하지 않는다.

### 실행 및 결과

- 수정 전 gzip 재현: `env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew :test --tests '*Issue68UsageAccountingTest' --console=plain --no-daemon` → **예상한 RED, 1 test/1 failure**, SDK900/20/920 정상이나 별도 Usage null.
- 새 HTTP/Usage 테스트 및 기존 HTTP6건 → **22 PASS**.
- 관련 회귀(실제 명령은 JSON): **170 tests / skipped1 / failures0 / errors0**, SUCCESS1m32s. Live1건은 환경 Gate로 skip했다.
- `env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew test build --rerun-tasks --console=plain --no-daemon` → **SUCCESS4m6s, 12 tasks 실제 실행**.
- root1,241 + Lambda11 = **1,252 tests / skipped87 / failures0 / errors0**. 두 모듈 모두 실제 재실행했다.
- #68 무과금57건 PASS / Live1 skipped. 이번 추가17건은 Reader8/정산8/Kafka gzip1이다.
- 새 테스트의 IOException 선언 누락으로 한 번 compile 실패했고, 축소 Context에 없는 Token Counter를 참조해 한 번 실패했다. 테스트 예외 선언 및 SDK 응답 직접 관측으로 수정했다. Production Metric을 바꾸거나 실패를 정상으로 간주하지 않았다.

### 검증 항목

| 테스트 | 확인한 계약 | 결과 |
|---|---|---|
| Issue68ResponseUsageTest 8건 | gzip/identity 동일 Usage, 원본 body 보존, CRC/잘림/크기 제한, 필수 정수/합계/cache/중복key/후행JSON 검증, unsupported encoding 보수 처리, redaction header 정합성 | PASS |
| Issue68UsageAccountingTest 8건 | 실제 SDK Native/Batch와 Usage 일치, SDK429 및 하위503 Attempt별 예약/정산, Usage 누락·외부JSON 손상·요청 후 연결 종료 시 전액 유지, 정산 I/O 실패 차단, 과거 UNKNOWN_KEEP fixture 복원 | PASS |
| Kafka gzip 통합1건 | 실제 Consumer/Adapter/SDK/MySQL: Native1 + Batch2, 개별 Commit/Offset/Lag 및 Token 비복제 | PASS |
| 기존 Budget/Live 안전장치/GT/Rule/Retry/DLT 및 관련 회귀 | 이중 정산·예산 초기화 거절, 공유 Retry/recovery-only, 품질 Hold/동결 계약 보존 | PASS |

### 실제 무과금 Kafka 관측

- gzip Native 요청1: Usage input900/output20/total920/cache0, SDK와 일치.
- gzip Batch 요청1/항목2: Usage input900/output40/total940/cache0. SDK ChatResponse 메타데이터와 별도 HTTP 계측이 일치하며 요청당 한 번 정산한다.
- 두 요청 모두 SETTLED. fixture 장부 정산306,000 nanoUSD / 총 선예약38,630,400 nanoUSD. **실제 OpenAI 비용이 아니다.**
- Native DB1/Batch DB2 완료, 양쪽 Lag0/DLT0/저장·트랜잭션·조기commit 위반0, 양쪽 실제 offset commit 관측. Batch 메시지별 DB Token은 NULL이다.
- 압축429→200과 하위503→200은 각각 논리1/HTTP2. 첫 실패는 UNKNOWN_KEEP, 성공 응답은 새 Ticket을 SETTLED한다. 실패의 예약을 성공 Usage로 덮어쓰지 않는다.
- 요청 후 연결 종료/손상 응답의 Usage를0으로 만들지 않는다. 실제 HTTP exchange 전 연결 실패와 전송 후 응답 유실은 같은 관측이라고 주장하지 않는다.

### 보존 확인

- 기존 Evidence6개 + Live journal/claim/checkpoint8개, 총14개 파일의 SHA256이 작업 시작과 동일하다.
- 기존 build raw도 변경하지 않고 새 고유 파일명으로 원본을 추가했다.
- 기존 Live status=NATIVE_GATE_HOLD, 미확정 예약19,276,800 nanoUSD는 그대로다. 과거 Token824/Total837을 새 코드로 재정산하지 않았다.
- 실제 OpenAI/API Key 읽기0, Live 재실행0. 이전 Live FAIL/HOLD는 이번 무과금 PASS로 소급 변경하지 않았다.

## 다음 Live 전 조건

- 이번 무과금 성공을 근거로 기존 HOLD나 UNKNOWN_KEEP를 해제하지 않는다. 실제 Live는 재실행하지 않는다.
- 다음 유료 실행은 Human의 별도 범위/호출 수 승인과 기존 누적 예약을 이월하는 재개 계약이 필요하다. 기존 journal을 삭제하거나 새 경로로 예산을 초기화하지 않는다.
- 기존 일회성 claim은 자동 재개를 거절한다. 다음 승인에 맞춘 진입점 보완 여부를 별도로 결정해야 한다.
- 과거 Live의 실제 encoding 원인, 실제 Batch HTTPS Schema/Mapping/Usage, 모델 품질·FN/FP/HIGH, 880건 Matrix는 이번 작업으로 검증 완료되지 않는다.
