# Issue #68 — Usage 수정 후 명시적 Live Smoke 재개

## 결론

2026-10-21 18:14 KST, 승인된 **Native1 + 실제 Kafka Poll-Batch1(2항목)**만 실행했다. 두 요청 모두 HTTP200, SDK/HTTP Usage 일치 및 정산/DB Commit/Offset 검증은 통과했다. 그러나 Batch의 `HOLDOUT-PROFANITY-16`이 GT HIGH 대신 MEDIUM으로 저장되어 **BATCH_GATE_HOLD**로 중단했다. Live JUnit은 이 품질 Hold를 숨기지 않고 **1 test / 1 failure**로 기록했다. 추가 Live, O0/O1, 880건 Matrix는 실행하지 않았다.

기술 계약 성공과 품질 통과는 다르다. 이 표본으로 전체 품질 유지·Native 대비 비용 절감·운영 적용을 확정하지 않는다. PROFANITY-16의 동시점 Native 응답은 이번 승인 범위에 없어 측정하지 않았다. 따라서 해당 HIGH 하향을 이번 Native 대비 paired regression이라고 주장하지 않는다.

## 승인 및 재개 계약

- 기준 HEAD: `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`, 브랜치 `feature/68-openai-poll-batch-verification`. 미커밋 테스트 소스 snapshot의 SHA256은 신규 raw와 [무과금 검증 기록](live-resume-usage-v1-verification.json)에 보존했다. HEAD만으로 미커밋 구현이 재현된다고 표현하지 않는다.
- 과거 승인: `issue68-smoke-native1-batch1-2026-10-21`. 신규 승인/실행 ID: `issue68-resume-usage-v1-native1-batch1-2026-10-21`.
- 기존 `.issue68-live/approval.claim`, 기존 checkpoint, [과거 Live 결과](live-smoke-results.json)는 그대로다. 새 실행은 `.issue68-live/<신규 승인 ID>/approval-manifest.json`과 별도 checkpoint를 사용한다. private 실행 상태는 Commit 대상이 아니다.
- 동일 고정 `budget.jsonl`을 OS FileLock으로 독점하고 복원한다. 과거 두 줄/303 bytes의 SHA256을 검사하고 기존 UNKNOWN_KEEP **$0.0192768**을 이월한다. 승인별 실행 디렉터리/manifest는 신규 생성만 허용하고 실패·종료 후 삭제하거나 자동 재개하지 않는다. claim/manifest 및 디렉터리는 fsync한다.
- 신규 Native 최대1, Batch 최대1. 실제 Batch는 지정된 합성 Case2건이어야 한다. SDK Retry 기본3 및 Kafka 정책은 변경하지 않고, network exchange별 선예약과 **신규 생성 HTTP 최대4회** 차단을 추가했다. 모델 조회 GET 논리1회는 생성 요청/Token 비용과 별도로 기록한다. 이 Java HttpClient 조회의 하위 HTTP Attempt는 별도 계측하지 않았다.
- Smoke 누적 **gross 선예약 상한 $0.10**. #68 계획 예산 $0.50과 구분한다. 정상 두 요청의 과거 포함 gross는 $0.0579072, 신규 HTTP4회의 보수적 최악 gross는 $0.0966912다. 정산으로 가용액이 회복되어도 gross 제한은 초기화하지 않는다.
- 정확한 HTTPS 목적지, redirect 금지, Model/Prompt/Schema/cap/옵션/합성 입력/요청 body SHA 검증을 유지했다. 입력은 기존 Policy v2 Human GT의 과거 사용 표본이며 신규 블라인드 데이터가 아니다. 실제 사용자 데이터는 사용하지 않았다.
- API Key는 `.env`의 `OPENAI_API_KEY` 한 항목만 추출해 승인된 Gradle 자식 프로세스 환경변수에 전달했다. `.env` source/다른 항목 주입/인수 전달은 하지 않았다. child 출력은 key 값으로 redaction하고 private/Evidence/JUnit에서 값 미포함을 확인했다.

## 이번 테스트 전용 변경

| 파일 | 변경 |
|---|---|
| Issue68LiveSession | 기존 일회성 open 보존, 별도 resume 승인·원본 장부 fingerprint·기존 claim 검사·manifest·분리된 checkpoint 경로 |
| Issue68HttpBoundary | Live 신규 exchange 최대4회, SDK Usage observation과 동일 논리 요청 번호 연결 |
| Issue68OpenAiContext | 새 재개 승인 ID에만 기존 환경변수 credential 경로 허용. Fake는 여전히 fixture credential |
| Issue68SdkUsage (신규) | 실제 ChatModelObservationContext에서 SDK model/Usage만 수집, HTTP Usage와 input/output/total/cached 일치 검사 |
| Issue68LiveResumeTest (신규) | 재개/잠금/예산/장부 보존·이중 정산 거절·HTTP 시도 제한·SDK Usage 대조8건 |
| Issue68LiveSmokeTest | 새 승인으로만 활성화, Native Gate 후 Batch, SDK/HTTP 대조, 별도 신규 결과 파일 |
| Issue68OpenAiKafkaIntegrationTest | 기존 gzip Kafka/MySQL 리허설에 새 SDK 대조기를 연결 |

Production 코드/설정, Prompt/Policy/Rule/Model/GT, Retry/DLT는 변경하지 않았다. Batch 기본 OFF는 그대로다.

## 무과금 사전 검증

모든 명령에서 `OPENAI_API_KEY`, `ISSUE68_LIVE_APPROVAL`을 제거했다. 명령/집계/source hash 원본은 [사전 검증 JSON](live-resume-usage-v1-verification.json)에 있다.

```sh
env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew :test --tests '*Issue68LiveResumeTest' --tests '*Issue68LiveReadinessTest' --tests '*Issue68UsageAccountingTest' --console=plain --no-daemon
env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew :test --tests '*Issue68*' --tests '*ChatModeration*' --tests '*Moderation*' --tests '*AiModerationPortSelectionTest' --console=plain --no-daemon
env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew test build --rerun-tasks --console=plain --no-daemon
```

- focused: **27 PASS**, 11s.
- 관련 회귀: **249 tests / skipped10 / failures0 / errors0**, 2m1s. #68 무과금65 PASS, Live1 skip.
- 전체 root1,249 + Lambda11 = **1,260 tests / skipped87 / failures0 / errors0**. build **SUCCESS4m28s**, 12 tasks 모두 실제 실행.
- Fake SDK500 지속 실패로 신규 HTTP4회 후 다섯 번째 서버 도달0을 검증했다. 과거1회와 합친 gross $0.096384를 유지했다. 이는 무과금 장애 검증이며 이번 Live에서 Retry를 발생시킨 결과가 아니다.
- 실제 Consumer/Adapter/SDK/JPA, Kafka7.7.1/MySQL8.4의 gzip Native1/Batch2에서 SDK/HTTP Usage 및 개별 DB Commit/Offset을 대조했다.

## Live 실행 및 실제 Usage

실행은 승인 환경변수가 있는 단 한 번의 자식 프로세스다. **그대로 재실행하지 않는다.** 동일 승인은 이미 소비됐고, 다음 실행은 Human 재승인이 필요하다.

```sh
./gradlew :test --tests '*Issue68LiveSmokeTest' --console=plain --no-daemon
```

원본: [Live 결과](live-resume-usage-v1-results.json), [종료·장부·Offset 감사](live-resume-usage-v1-audit.json).

| 항목 | Native | Kafka Poll-Batch |
|---|---:|---:|
| 논리 생성 요청 / HTTP Attempt | 1 / 1 | 1 / 1 |
| 실제 요청 항목 수 | 1 | 2 |
| HTTP status / 응답 encoding | 200 / gzip | 200 / gzip |
| Input / Cached Input | 824 / 0 | 986 / 0 |
| Output / Total | 13 / 837 | 53 / 1,039 |
| SDK/HTTP Usage 일치 | true | true |
| 미할인 Usage 추정 비용 | $0.0001314 | $0.0001797 |
| 해당 요청 선예약 | $0.0192768 | $0.0193536 |
| 정산 | Ticket2 SETTLE 1회 | Ticket3 SETTLE 1회 |
| network interceptor 계측 시간 | 2.321661250s | 1.783474292s |
| Port 논리 처리 시간 | 2.703481833s | 1.811018000s |
| Kafka 수신→개별 DB Commit | 2.827807167s | 1.830086208 / 1.843901500s |

실제 모델은 양쪽 `gpt-4o-mini-2024-07-18`이며 요청 모델은 승인된 `gpt-4o-mini`다. 모델 접근 조회 GET 논리1회는 HTTP200으로 반환됐다. 하위 Attempt 수는 미계측이며, 생성 HTTP2회와 혼동하지 않는다. 생성 경로의 SDK Retry0, Kafka 재전달0이다. network interceptor 시간은 Usage 판독·장부 정산을 포함하며 TCP/TLS 연결 전체 또는 순수 Provider 서버 처리시간과 같다고 보지 않는다.

비용은 [공개 모델 단가](https://developers.openai.com/api/docs/models/gpt-4o-mini)(1M당 input $0.15 / cached input $0.075 / output $0.60)와 실제 응답 Usage 기준 **추정치**이며 청구서가 아니다. 두 요청 cached0으로 할인/미할인 추정치가 동일하다. 계정 전체·다른 프로세스·가격 변경에 대한 청구 상한을 보장하지 않는다.

## 품질 Gate

| 모드 / Case | GT | 실제 | 결과 |
|---|---|---|---|
| Native / HOLDOUT-SAFE-01 | SAFE / [] / LOW | 동일 | PASS |
| Batch / HOLDOUT-SAFE-01 | SAFE / [] / LOW | 동일 | PASS |
| Batch / HOLDOUT-PROFANITY-16 | FLAGGED / [PROFANITY] / HIGH | FLAGGED / [PROFANITY] / MEDIUM | **GT_HIGH_DOWNGRADE → HOLD** |

- Native Result/Category/Risk 1/1. GT FLAGGED가 없는 Native 표본의 F1을 일반 품질 지표로 해석하지 않는다.
- Batch Result/Category 2/2, Risk1/2, TP1/TN1/FP0/FN0, F1=1.0이나 **HIGH 하향1건으로 실패**다. 구조적 정책 조합 위반·매핑/Validation·Provider 오류·UNRESOLVED는0이다.
- 실제 Native는 SAFE01만 호출했다. PROFANITY16의 동시점 Native 비교 없이 Batch만의 원인·회귀라고 단정하지 않는다. 잘못된 Risk는 정상 형식으로 저장되므로 Retry/DLT로 고쳐졌다고 표현하지 않는다.
- Native와 Batch는 항목 수와 구성도 다르다. 단순 Token/latency 비율을 동일 조건 개선율이나 운영 효과로 계산하지 않는다.

## Kafka / DB / Offset

| 항목 | Native | Batch |
|---|---|---|
| 실제 poll 구성 | SAFE01 | SAFE01 + PROFANITY16 |
| Provider ID | 단건 | m001/m002 중립 ID |
| 결과 DB Commit | 1 | 2 (개별 transaction) |
| partition0 원본 offset | 0 | 0,1 |
| 최종 committed offset | 1 | 2 |
| Lag | 최대1 → 최종0 | 최대2 → 최종0 |
| DLT / 저장 실패 / 조기commit / transaction 위반 | 모두0 | 모두0 |
| 메시지별 DB Token | input824 / total837 | 두 행 모두 NULL; 요청 Usage를 복제하지 않음 |

flush, transaction afterCommit, 메서드 반환, Kafka committed offset을 별도로 관측했다. 이번은 성공 기술 경계의 소표본이고 재균형·강제 종료·실제 Provider 장애에서의 운영 검증을 대체하지 않는다. Testcontainers 종료 후 DB는 폐기되며 before-stop 개별 Commit 및 Offset/Lag snapshot만 보존한다. 기존 로컬 DB와 AWS는 사용하지 않았다.

## 예산 및 기존 기록 보존

- 과거 UNKNOWN_KEEP: **$0.0192768** 그대로. 과거 Native input824/total837을 새 코드로 소급 정산하지 않았다.
- 신규 정산: **$0.0003111** = input1,810/output66/total1,876의 미할인 추정치. 신규 UNKNOWN_KEEP0.
- 장부의 정산+미확정 부담액: **$0.0195879**.
- 누적 gross 선예약: **$0.0579072 / $0.10**. gross의 남은 금액은 추가 실행 승인이나 자동 재개 허가가 아니다.
- 장부는6줄이며 과거2줄/303 bytes를 그대로 유지하고 RESERVE2/SETTLE2/RESERVE3/SETTLE3만 추가했다. 각 성공 Ticket 정산은 한 번이다. hash-chain 검증 통과.
- 원래 Evidence8개와 claim/checkpoint7개, 총15개는 SHA256 동일. journal은 append-only이며 과거 prefix SHA256 `a8a259adbc6d559a585bcb821586b266ff18456c42f5aaf526a4618e86ef7cc5`가 동일하다. 이전 HOLD를 새로운 성공으로 바꾸지 않았다.
- 이번 두 gzip 응답의 Usage는 수정된 reader가 정상 판독했다. 과거 응답 encoding은 여전히 미확인으로 gzip이 과거 오류의 확정 원인이라는 주장은 하지 않는다.

## 최종 상태와 다음 단계

무과금 전체 build는 PASS지만, 별도 Live JUnit은 **품질 Gate FAIL/HOLD**, 22s다. 테스트가 모두 성공했다고 보고하지 않는다. 예산·전송·Usage/정산 및 DB/Offset 기술 오류는 이번 실행에서 관측되지 않았다.

신규 JSON syntax, untracked 소스/문서의 whitespace 별도 검사와 `git diff --check`는 PASS다. tracked/staged Diff는0이며 기존 미커밋 작업은 보존했다. GitHub 읽기 전용 재확인에서 #68은 OPEN / `status:human-answer-required`, #55는 OPEN이다. Issue/PR은 변경하지 않았다.

다음 유료 단계는 **HOLD**다. HIGH 하향 사례를 Human이 검토하고, 다음 단계/새 실행 승인·소비된 claim 및 누적 예산 이월 계약을 확인하기 전에는 재개하지 않는다. GT·Prompt를 결과에 맞추어 변경하지 않는다. 기존 기본 Batch OFF 및 #68/#55 OPEN 유지, Commit/Push/PR/Merge/AWS/880건 Matrix0이다.
