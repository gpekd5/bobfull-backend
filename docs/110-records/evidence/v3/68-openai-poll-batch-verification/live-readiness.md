# Issue #68 — Live Readiness 보완 및 최소 Smoke

## 범위와 승인

- 기존 Phase 1 README/verification-results.json은 당시 소스·실행 결과 그대로 보존한다. 이후 테스트 지원 코드 변경은 이 문서와 별도 원본으로 구분한다.
- Human 승인: #68 전체 계획 예산 $0.50, 이번 Smoke의 **총 선예약 합계 최대 $0.10**. 정산으로 잔액이 늘어도 이번 실행의 총 선예약 상한은 해제하지 않는다.
- Native 논리 요청 최대1회, Kafka Poll-Batch 논리 요청 최대1회. SDK/하위 HTTP retry도 같은 총 예약 한도에 포함한다. Kafka 재전달에 의한 추가 Provider 분석은 테스트 경계에서 거절한다.
- 880건 Matrix, O0/O1, Production 활성화, AWS, Commit/Push/PR/Close는 승인 범위가 아니다.

## 불변 조건

- Production/설정/Prompt/Policy/Rule/Model/DB Schema 변경 없음. Batch 기본 OFF 유지.
- 과거 사용한 Policy v2 Human GT80/진단20 재사용. 신규40건 제외.
- GT SHA256: `2892ac234eea3ae79da9a1d13fd10237393393fddb87cb2b24180817a9a7823b`.
- Smoke 입력: 기존 합성 `HOLDOUT-SAFE-01`, `HOLDOUT-PROFANITY-16`. Native는 첫 입력만, Batch는 같은 첫 입력과 HIGH 중점 입력을 포함한2건이다.
- 따라서 완전히 같은 두 입력에 대한 Native/Batch 품질 비교가 아니다. 공통 입력1건만 paired 비교 가능하며, 전체 품질/안정성 판정 표본이 아니다.

## 전송·예산·중단 경계

- Fake 생성자는 여전히 loopback HTTP만 허용한다. 일회성 Live session만 정확한 `https://api.openai.com/v1`을 허용하며 POST 경로/443/TLS 검증/redirect 금지/NO_PROXY를 유지한다.
- 실제 Adapter/SDK가 생성한 전체 요청의 SHA256을 무과금 리허설로 동결한다. Model/System Prompt/원본 Few-shot/Schema/출력cap/옵션/입력/중립ID 순서가 달라지면 전송하지 않는다.
- Native 요청 SHA256: `88f2c1c0cfb1b850da5f1c6671501fd99e319a1f51e4cf8e1dcf0e2dd9b56324`.
- Batch 요청 SHA256: `e4737a4c08f33c81e245e9d16922b872208682f85ab0bcfd90fbb7c599955340`.
- Credential은 승인된 테스트 자식 프로세스 환경변수에서만 읽는다. Fake Context는 실제 Key를 읽지 않는다. Provider 오류 본문은 SDK 전달 전에 제거하며 Live 로그를 비활성화한다. 예외 message/cause는 결과에 저장하지 않는다.
- 고정 실행 경로 `.issue68-live/budget.jsonl` 및 `approval.claim`. 개인 실행 상태이며 Commit 대상이 아니다. 장부0600/디렉터리0700, FileLock, 일회성 claim, hash-chain 검증으로 동시 실행·손상·자동 재개를 거절한다.
- 미정산 RESERVE는 재시작 복원 시 전액 유지한다. 이미 실행한 journal/claim을 새 예산으로 재사용하지 않는다. 원본 파일 삭제/경로 변경으로 재승인을 우회하지 않는다.
- 매 network exchange 전 context128,000 input + output cap의 최대액을 journal에 fsync한 뒤 전송한다. Usage 유효 시 미할인액 정산, 불명/실패는 전액 유지한다.
- 이것은 고정 공개 단가/모델 한도 아래 이 실험의 보수적 장부 상한이다. 계정 전체 청구/세금/다른 프로세스/가격 변경까지 보장하는 기능이 아니다.
- 요청·DB afterCommit·실행 종료/실패 checkpoint와 요청별 Usage/예약을 보존한다. JVM 강제 종료/OS 장애에서 모든 마지막 checkpoint가 남는다고 보장하지 않는다. HTTP 미확정 예약은 장부에 남는다.
- 기존 Fake 대기값은 유지하며 Live만280초 완료 대기를 사용한다. timeout 시 추가 전송 차단/cancel 후 Container를 종료하고 부분 DB/Offset/Lag 상태를 수집한다. 실제 긴 Retry-After와 재균형은 별도 미검증이다.
- FN/GT HIGH 하향/정책 위반/UNRESOLVED/Native FP/Batch FP/기술 오류는 제한 실행 단위 종료 후 Hold한다. 예산/전송/checkpoint 오류는 다음 HTTP 이전에 차단한다. 자동 재개하지 않는다.

## 예상량 — 실제 Usage 아님

- 논리 요청: Native1 + Batch1. 출력cap128/256.
- 무과금 tokenizer 추정 input1024/1219, outputcap 합계384: 미할인 계획비용 $0.00056685. Provider accounting과 동일하지 않다.
- 재시도 없는 최대 선예약 합계 $0.0386304. 총 예약 $0.10은 SDK Retry 중에도 넘을 수 없다. 8회 HTTP를 무조건 실행할 수 있다는 의미가 아니다.
- 공식 모델 단가: input $0.15 / cached input $0.075 / output $0.60 per1M. 보호 계산에는 cache 할인을 적용하지 않는다.
- 공개 문서의 가용성은 계정 접근 증거가 아니다. Live에서 읽기 전용 모델 조회1회가 성공한 후에만 Chat Completion을 시작한다. 접근 실패 시 모델 대체 없이 중단한다.
- 출처: https://developers.openai.com/api/docs/models/gpt-4o-mini (2026-10-21 확인).

## 검증 원본 및 해석

- 무과금 리허설: `build/issue68/fake-live-smoke-rehearsal.json`. 실제 Consumer/Adapter/SDK/Kafka7.7.1/MySQL8.4를 사용하되 Provider는 Fake이다.
- Live 결과는 별도 `live-smoke-results.json`에 기록한다. 이 파일이 없다면 Live 완료 결과로 간주하지 않는다.
- 검증 명령/최종 테스트 집계/Live 실제 관측은 후속 실행 결과를 확인한 뒤 이 문서에 추가한다.
- Batch Token은 요청 단위로만 기록하고 메시지별 DB Token에 복제하지 않는다. 기존 운영 E2E Timer를 수신→DB Commit으로 바꾸지 않는다.
- 기존 실험 결과, 운영 Token 절감, 신규 블라인드 품질을 이번 Smoke 결과로 대신하지 않는다.

## 실제 실행 결과 — Native 후 HOLD, Batch 미실행

2026-10-21 실행. 기준 HEAD `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`, 미커밋 소스 snapshot이며 각 실행 원본의 sourceHashes를 사용한다.

### 무과금 검증

- `env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew :test --tests '*Issue68*Test' --console=plain --no-daemon`: PASS. 이후 요청 전체 해시 동결/오류 redaction 검증을 추가하고 전체 실행에서 확인했다.
- `env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew test build --rerun-tasks --console=plain --no-daemon`: **SUCCESS, 4m22s, 12 tasks 실제 실행**.
- root1,224 / Lambda11 = **1,235 tests / skipped87 / failures0 / errors0**. Live1건은 환경 Gate로 skip했다. API Key 없이 실행했다.
- #68 무과금40건 PASS: Budget7/Contract5/HTTP6/Readiness11/Kafka-MySQL11. 기존28건에 새12건 추가.
- `live-readiness-results.json`에 전체 집계와 Fake 최소 리허설 원본/소스 해시를 보존했다. Fake GT echo를 실제 모델 품질로 해석하지 않는다.

### Live 실행과 중단

- `.env` 전체를 source하지 않고 OPENAI_API_KEY 한 항목만 테스트 자식 환경에 일시 전달했다. Key는 명령 인수/소스/결과에 기록하지 않았다. 실행 후 Key 문자열 노출 검사도 false였다.
- 모델 읽기 조회 HTTP200, 모델 `gpt-4o-mini` 접근 확인. 이 GET1회는 Chat Completion/논리 Provider 호출 수에 합산하지 않는다.
- **Native 논리1 / 생성 HTTP attempt1(200) / SDK Retry0 / Batch0**.
- 실제 응답 모델 `gpt-4o-mini-2024-07-18`.
- HOLDOUT-SAFE-01: GT와 실제 결과 모두 SAFE / [] / LOW. Result/Category/Risk 1/1. FP0/FN0이나 FLAGGED/HIGH 표본이 없어 Recall/HIGH 방어를 검증한 것이 아니다.
- SDK가 저장한 DB Token: input824 / total837. output13은 total-input으로 계산한 값이다. Cached Input은 이번 보존 자료에서 **확인 불가**이며0으로 확정하지 않는다.
- HTTP network 경계의 Usage는 null이었다. **USAGE_UNKNOWN Gate로 Native 실행 종료 후 중단했다. Batch/추가 Live/Matrix를 실행하지 않았다.**
- SDK/DB Token 기준 미할인 참고 추정 비용 `$0.0001314` (131,400 nanoUSD). 실제 청구액이나 장부 정산액이 아니다. Cache 할인액은 확인할 수 없다.
- 예약 장부는 Usage를 미확정으로 처리해 **$0.0192768 전액 유지**. 총 선예약액도 $0.0192768로 Smoke $0.10/전체 $0.50 안이다. 남은 금액이 있어도 추가 호출 승인으로 해석하지 않는다.
- Native DB1건 완료/afterCommit 관측/partition0 source offset0 → committed offset1/Lag0/DLT0/수신1회·재전달0회/조기commit0/저장실패0.
- Local 측정: network exchange2.472322s, Port logical2.770783s, Kafka 수신→DB Commit2.845531s, 발행 ACK→DB Commit2.946028s. N=1이며 p50/p95가 같아도 percentile 성능 검증이 아니다.
- Live 테스트는 **FAIL/HOLD**, Gradle `:test`는 exit1이다. 실제 서비스 처리는 성공했지만 Smoke 완료 조건은 충족하지 못했다. 이전 무과금 전체 Build PASS와 구분한다.
- 원본: `live-smoke-results.json`, 예약/Offset 확인: `live-smoke-stop-evidence.json`. 개인 원본 journal/claim/checkpoint는 `.issue68-live/`에 보존하고 Commit하지 않는다. 자동 재개 금지.

### Usage Gap 분석 — 확인된 사실과 가설

- 확인: 실제 SDK/DB에는 Token이 있으나 network interceptor에서 읽은 Usage는 null이다. 보호 장치는 이를0비용으로 만들지 않고 예약 유지/Hold했다.
- 확인: 사용 중인 OkHttp4.12.0 BridgeInterceptor는 network interceptor가 반환한 이후 transparent gzip을 해제한다. 현재 Usage 판독은 그 이전 network interceptor의 `peekBody`에서 수행한다.
- 무과금 loopback gzip probe: `NETWORK_ENCODING=gzip`, `NETWORK_BODY_IS_JSON=false`, `APPLICATION_BODY_IS_JSON=true`로 차이를 재현했다. 최초 probe는 Kotlin classpath 누락으로 실패했으며 classpath만 보완한 뒤 PASS했다.
- **가장 유력한 원인은 압축 응답 판독 위치다.** 그러나 Live 응답의 Content-Encoding을 기록하지 않아 이 요청의 원인을 확정하지 않는다. 추가 OpenAI 호출은 하지 않았다.
- 후속 후보: exchange별 선예약은 network 경계에 유지하고, 압축 해제 후 Usage를 해당 attempt에 안전하게 연결/정산하는 무과금 회귀 검증. 오류 응답/응답 유실/하위 follow-up/중복 정산에 대한 보수적 계약을 유지해야 한다.
- 이번 실행 후 이 Gap을 임의 수정해 Live를 재실행하지 않았다. 기존 장부/미확정 예약을 유지하며 보완 범위와 재실행 여부를 Human에게 확인한다.

## 다음 단계 판정

- Live Native의 endpoint/모델/Structured Output/Validation/MySQL Commit/Kafka Offset 정상 흐름은 관측했다.
- **실제 Batch Schema/Mapping/Usage/품질은 NOT_RUN**이다. Native/Batch 비용 및 품질 비교를 완료했다고 표현하지 않는다.
- 880건 Matrix 실행 Readiness는 **HOLD**. Usage 정산 Gap 및 부분 재개 계약을 보완하고 별도 승인 전 O0/O1을 실행하지 않는다.
- 이번 최소 표본과 기존 독립 실험을 운영 품질/비용 절감/활성화 승인으로 일반화하지 않는다.
