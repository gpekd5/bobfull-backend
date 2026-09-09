# Issue #235 — 인기 회차 조회 Hot-path 병목 개선 및 K6 재측정

## 기준

- 기준 Branch: `perf/235-restaurant-view-hotpath`(코드, PR #242) → `docs/235-hotpath-after-remeasure`(After 재측정, 이 PR)
- 선행 Evidence: `docs/110-records/evidence/v3/142-reservation-peak/README.md`(#142, Before 원본)
- Before 코드 SHA: PR #242 머지 전 develop 기준(HikariCP `maximum-pool-size` env var 외 회차 조회 구조는 #61 이후 그대로)
- After 코드 SHA: PR #242 머지 커밋(TimeSlotService 회차별 반복 쿼리 4종 배치 전환)
- 측정일: 2026-08-13

## 요약

#142는 "인기 회차 예약 오픈" 상황에서 조회 폭주(A)가 CPU·DB Connection Pool을 동시에 포화시킨다는
걸 확인했지만, 그 조회가 식당 상세(detail)와 회차 조회(dining-sessions) 중 어느 쪽 때문인지는
분리하지 못했다. 이 Issue는 그 Hot-path를 분리 측정하고, 확인된 병목을 최소 변경으로 개선한
뒤 동일 조건으로 재측정했다.

**결론**: dining-sessions API가 회차마다 반복하던 쿼리 4종(활성 예약·참여자 합계·CLOSED 여부·
READY Payment 선점 합계)을 배치 쿼리로 전환한 것만으로 일상적 부하(Load, 20 iter/s)에서는
p95 92% 개선이 관측됐고, Load 구간에서는 CPU/Pool 포화가 관측되지 않았다. **다만 #142와 동일한
Stress(최대 320 iter/s) 조건으로 재측정한 결과, 포화가 완전히 사라진 건 아니다** — 병목이
시작되는 지점이 약 40 iter/s에서 약 320 iter/s로 8배 밀렸을 뿐, 최고 부하 단계에서는 여전히
CPU·HikariCP Pool이 포화된다(4절 참고, PR #249 재검토 반영). Pool 크기 조정(3단계)은 이번
측정 범위(최대 320 iter/s)에서는 필요하지 않다고 판단하지만, 그 이상의 부하가 실제로 발생할
가능성이 있다면 재검토가 필요하다.

## 핵심 원칙 준수 확인 (Issue #235)

- #142 Evidence를 Before 기준선으로 사용했다.
- Hikari Pool 크기를 먼저 늘리지 않고, 요청당 DB 비용(쿼리 구조)부터 확인했다.
- 정확한 병목 API(1단계)를 확인하기 전에는 Query/Index/Cache를 미리 정답으로 확정하지 않았다.
- 한 번에 여러 기술을 넣지 않고, 쿼리 구조 개선 하나만 적용한 뒤 같은 조건으로 재측정했다.
- Redis ZSet 대기열은 이 Issue에서 선도입하지 않았다.

## 1. 병목 Hot-path 분리

`peak-restaurant-view.js`(#142)는 한 iteration에서 식당 상세와 회차 조회를 함께 호출해 어느 쪽이
병목인지 분리하지 못했다. `restaurant-view-hotpath.js`(`TARGET=detail|sessions`)로 AWS에서
각각 독립 측정했다(Load, 20 iter/s·5분, 인스턴스 t3.small·풀=10, #142와 동일 조건).

| API | p95 | 오류율 | CPU·DB Pool 영향 |
|---|---:|---:|---|
| 식당 상세(`GET /api/restaurants/{id}`) | 16.5ms | 0% | 거의 없음(단일 쿼리 확인, 코드 리뷰로 검증) |
| 회차 조회(`GET .../dining-sessions`) | 1.15s~4.03s(측정 시점별 변동, 아래 "트러블슈팅" 참고) | 0% | 단독 20 req/s에도 HikariCP active 10/10(=풀 100%) 포화 |

Raw: `raw/detail-AWS-load.{log,json}`, `raw/sessions-AWS-load{,-postcleanup}.{log,json}`

**판정**: dining-sessions가 주 병목이다(Issue #235 "1. 병목 Hot-path 분리" 완료). 식당 상세는
이미 단일 쿼리(코드 리뷰로 확인, `RestaurantService.getRestaurantDetail` → `Restaurant` 엔티티에
`@OneToMany`/컬렉션 필드 없음)라 이번 개선 대상에서 제외했다.

## 2. 회차 조회 반복 Query 개선

`TimeSlotService.getAvailableDiningSessions`가 회차(TimeSlot)마다 아래 4개 쿼리를 반복 실행하고
있었다(회차 N건 기준 `3 + N*4` 쿼리, #61 이후 구조):

1. 활성 예약(RECRUITING/CONFIRMED/CANCELLING) 조회
2. 그 예약의 참여자 partySize 합계
3. CLOSED 예약 존재 여부(`availableCapacity` 0 처리용)
4. 만료되지 않은 READY Payment 선점 partySize 합계

회차 ID는 TimeSlot 목록 조회 시점에 이미 다 알고 있으므로, 4개 모두 `List<Long> timeSlotIds`
기준 배치 쿼리(GROUP BY 또는 IN)로 바꿔 회차 건수와 무관하게 한 번씩만 실행하도록 했다:

- `ReservationRepository.findAllByTimeSlotIdInAndReservationStatusIn`(활성/CLOSED 겸용)
- `ReservationParticipantRepository.sumPartySizeByReservationIdsAndStatuses`
- `PaymentRepository.sumPartySizeByTimeSlotIdsAndStatusAndExpiresAtAfter` +
  `PaymentHoldReader.sumActiveReadyPartySizeByTimeSlotIds`

`availableCapacity` 계산식(`ReservationCapacityPolicy.availableCapacity`, CLOSED면 0)은 그대로
유지했다 — 입력값을 가져오는 방식만 바꿨다.

**쿼리 수 변화**: `AvailableDiningSessionQueryCountInvestigationTest`(전용 MySQL 컨테이너,
TimeSlot 20건)로 실측 — **83개 → 7개**(고정값, 회차 건수와 무관). `PerformanceQueryCountIntegrationTest`
(TimeSlot 2건)에서도 동일하게 7개로 수렴함을 확인했다.

상세 코드 변경은 PR #242 참고.

## 3. DB Pool 설정 판단

아래 4절의 After 재측정에서 Load(20 iter/s) 구간은 HikariCP `active`/`pending` 포화가 관측되지
않았지만, **Stress 최고 단계(320 iter/s)에서는 `active`가 다시 10(=풀 100%)까지 차고 `pending`도
최대 190건까지 올라간다** — 쿼리 구조 개선으로 병목 임계점이 크게 밀렸을 뿐, 풀 크기 자체의
물리적 상한(10)은 여전히 존재한다. 다만 그 임계점이 #142 원본(약 40 iter/s)보다 8배 높은 약
320 iter/s까지 올라갔고, 이는 이번 측정에서 시도한 가장 높은 목표 부하와 같아 **이번 측정
범위 안에서는** 풀 크기 조정이 필요하지 않다고 판단한다(기본값 10 유지). 320 iter/s를 넘는
부하가 실제로 발생할 가능성이 있다면 그때 재검토가 필요하다.

## 4. 동일 조건 K6 After 측정

### 측정 방법론 — 워밍업 트러블슈팅

3단계 진행 중, 인스턴스를 재시작한 직후 바로 측정하면 JVM JIT·커넥션 풀 콜드 스타트가 섞여
실제보다 나쁜 값이 나온다는 걸 직접 확인했다(재시작 직후 smoke 테스트 p95 1.11s → 90초
워밍업 트래픽을 흘려보낸 뒤 재측정하니 p95 290ms로 급격히 개선). 그래서 **Before/After 둘 다
30초 워밍업 트래픽(결과 폐기)을 먼저 흘려보낸 뒤** 실제 측정을 시작했다 — 이 원칙을 지키지
않은 이전 raw 파일(`sessions-AWS-load.log`, `sessions-AWS-load-postcleanup.log`)은 참고용으로만
남겨두고, 아래 비교표는 워밍업을 거친 `-before-warm`/`-after-batch` 실행 기준으로 작성한다.

### Before/After 비교 (AWS `bobfull-k6-test-app`, t3.small·풀=10, `STAGE=load` 20 iter/s·5분, 워밍업 후 측정)

| 지표 | Before(배치 전, PR #242 이전 코드) | After(배치 후, PR #242 코드) | 변화 |
|---|---:|---:|---|
| p95 응답시간 | 802.66ms | **60.27ms** | 개선 92.5% |
| p99 응답시간 | 1.706s | **265.54ms** | 개선 84.4% |
| 평균 응답시간 | 299.22ms | **35.41ms** | 개선 88.2% |
| HTTP RPS(`http_reqs`) | 19.7 req/s | 20.0 req/s | 동일(목표 부하 그대로 소화) |
| 오류율(`http_req_failed`) | 0% | 0% | 동일 |
| dropped_iterations | 25건 | 3건 | 감소 |
| CPU(최대/평균) | 91.7% / 70.0% | **21.2% / 11.6%** | 여유 확보 |
| HikariCP active(20초 간격 scrape 최대) | 10(=풀 100%) | **0** | 이 scrape 구간에서는 포화 관측 안 됨 |
| HikariCP pending(20초 간격 scrape 최대) | 1 | **0** | 이 scrape 구간에서는 포화 관측 안 됨 |

Raw: `raw/sessions-AWS-load-before-warm.{log,json}`, `raw/sessions-AWS-load-after-batch.{log,json}`,
`raw/prometheus/sessions-{before,after}-{cpu_process,hikari_active,hikari_pending}.json`

**해석**: 회차별 반복 쿼리를 배치로 묶은 것만으로 지연·CPU·DB Pool 세 지표 모두에서 뚜렷한
개선이 나타났다. HikariCP `active`가 이 Load 구간의 20초 간격 scrape 시점마다 0으로 관측된
건 — 20 req/s를 처리하면서 커넥션을 전혀 안 썼다는 뜻이 아니라, 쿼리 수가 줄어 커넥션을
붙잡는 시간(체류 시간) 자체가 짧아져서 매 scrape 순간에 마침 비어 있는 것으로 샘플링됐을
가능성이 크다는 뜻이다(PR #249 재검토 반영, hyeonseung-dev MINOR). 정확한 표현은 "완전
해소"가 아니라 "이 부하 수준·scrape 구간에서는 포화가 관측되지 않음"이다 — 이 Load 수준의
결론이 모든 부하 수준에 그대로 적용되지 않는다는 건 아래 "Stress 재측정"에서 확인했다.

### Stress 재측정 (#142와 동일 스크립트·조건, PR #249 재검토 반영)

Issue #235 4단계는 "`peak-restaurant-view.js`를 그대로 재사용해 #142와 동일 K6 부하 단계에서
After를 측정"하도록 명시한다. 위 Load 비교만으로는 이 계약을 충족하지 못한다는 지적(hyeonseung-dev
독립 리뷰, MAJOR)을 받아, `peak-restaurant-view.js`(#142 원본, 식당 상세+회차 조회 결합)를
그대로 `STAGE=stress`로 재실행했다(같은 인스턴스, 배포된 배치 코드, 30초 워밍업 후 측정).

| 지표 | Before(#142 원본 Stress) | After(배치 후 Stress) | 변화 |
|---|---:|---:|---|
| p95 응답시간 | 13.14s | **1.34s** | 개선 89.8% |
| p99 응답시간 | 19.62s | **2.18s** | 개선 88.9% |
| HTTP RPS(`http_reqs`) | 51.4 req/s | **195.3 req/s** | 3.8배 증가 |
| Iteration/s | 25.7 iter/s | **97.6 iter/s** | 3.8배 증가 |
| 오류율(`http_req_failed`) | 0.02%(9/40,701) | **0%(0/152,631)** | 개선 |
| dropped_iterations | 61,851건(78.2/s) | **5,886건(7.5/s)** | 개선 90.5% |
| CPU(최고 단계 기준) | 88~98% | **96~98%(최고 단계에서만)** | 포화 시작 지점이 크게 밀림(아래 참고) |
| HikariCP active(최고 단계 기준) | 10(=풀 100%) | **10(=풀 100%, 최고 단계에서만)** | 동일(단, 발생 시점이 밀림) |
| HikariCP pending(최고 단계 기준) | ~190건 | **~190건(최고 단계에서만)** | 동일(단, 발생 시점이 밀림) |

Raw: `raw/peak-restaurant-view-AWS-stress-after-batch.{log,json}`,
`raw/prometheus/stress-after-batch-{cpu_process,hikari_active,hikari_pending,threads}.json`

**시간대별 포화 시점 비교(경과 시간 기준)**:

| 단계(목표 iter/s) | #142 원본(Before) 상태 | 이번(After) 상태 |
|---|---|---|
| 20 (0~1분) | 미포화 | 미포화(CPU 9~11%) |
| 40 (1~4분) | **포화 시작**(t+150s, CPU 93%, active 10, pending 45) | 미포화(CPU 10~16%) |
| 80 (4~7분) | 포화 지속 | 미포화(CPU 21~41%) |
| 160 (7~10분) | 포화 지속(CPU 94~96%) | 미포화~포화 시작 전환(CPU 52~66%) |
| 320 (10~13분) | 포화 지속(CPU 97%) | **포화 시작**(CPU 97~98%, active 10, pending ~190, 마지막 약 2~3분 구간) |

**정확한 해석(PR #249 재검토 반영)**: 쿼리 배치화로 병목이 시작되는 지점이 약 40 iter/s에서
약 320 iter/s로 **8배 밀렸다** — 일상적인 부하 범위에서는 사실상 병목이 사라진 것과 다름없는
큰 개선이다. 하지만 **가장 높은 목표 부하(320 iter/s)에서는 여전히 CPU·HikariCP Pool이
포화된다** — "Pool 포화를 완전히 해소했다"는 이전 서술은 과했다. 정확히는 "쿼리 구조 개선만
으로 병목 임계점이 크게 상승했지만, 물리적 상한(2 vCPU·풀 10) 자체가 없어진 건 아니다"이다.

## 5. 정합성 회귀 확인

- `TimeSlotServiceTest` 신규 유닛 테스트: 활성 예약+참여자 3명+READY 선점 1명인 회차의
  `availableCapacity`가 배치 조립 전후로 동일한 공식(`ReservationCapacityPolicy.availableCapacity`)
  으로 계산됨을 확인. CLOSED 회차는 참여자·READY 선점과 무관하게 `availableCapacity=0`을
  유지함을 확인.
- 전체 유닛 테스트 814/818 통과(실패 4건은 무관한 `ManualSmtpSendVerification`, 실제 Gmail
  SMTP 자격증명 필요 — 이 변경과 무관).
- AWS 재측정에서도 `checks_succeeded` 100%, `http_req_failed` 0%로 응답 자체는 매번 정상이었다.

## 6. 검증 한계

- Stress 최고 단계(320 iter/s)를 넘는 부하는 시도하지 않았다 — 그 이상에서 새 포화점이 더
  올라가는지, 아니면 320 iter/s 근처가 사실상의 상한인지는 확인하지 못했다.
- HikariCP `active`/`pending`은 20초 간격 scrape 값이라 순간적인 짧은 포화는 놓칠 수 있다 —
  Load 구간의 "포화 관측 안 됨"이 "커넥션을 전혀 안 썼다"는 뜻은 아니다(4절 참고).
- 워밍업 시간(30초)은 경험적으로 정한 값이라, 더 짧은/긴 워밍업이 결과에 영향을 주는지는
  별도로 확인하지 않았다.
- t3.small의 CPU 크레딧(버스터블) 소진 여부는 이번에도 확인하지 않았다 — 다만 After 측정에서
  CPU 사용률 자체가 낮아(평균 11.6%) 크레딧 소진 위험은 낮다고 판단한다.
- RDS 쪽 성능(쿼리 실행 시간 자체)은 App 쪽 Prometheus 지표로 간접 추정했을 뿐, RDS
  Performance Insights 등으로 직접 확인하지는 않았다.

## 최종 판단

**조회 개선만으로 병목 임계점이 약 40 iter/s에서 약 320 iter/s로 8배 상승 → 현재 구조(배치
쿼리) 유지, 이번 측정 범위(최대 320 iter/s) 안에서는 Pool 크기 조정 불필요, Redis ZSet
대기열 미도입. 다만 320 iter/s를 넘는 부하가 실제로 우려된다면 Pool/인스턴스 확장 재검토가
필요하다(PR #249 재검토 반영 — 이전 "완전 해소" 결론을 정정).**

- `#191`(Auto Scaling 판단) 연계는 이번 측정 범위(최대 320 iter/s) 안에서는 필요하지 않다고
  판단한다 — 다만 이는 "모든 부하에서 필요 없다"는 뜻이 아니라 "이번에 실측한 범위 안에서는"
  이라는 조건부 결론이다.
- Redis ZSet 대기열은 Before에서도(#142 시나리오 B, 최대 500명 동시 CREATE 경쟁) 이미 병목
  신호가 없었고, 이번 조회 경로 개선까지 더해져 도입 조건(순간 유입량이 안전 처리량을
  지속적으로 초과)이 이번 측정 범위 안에서는 충족되지 않는다고 판단한다.

## 후속 과제

- 320 iter/s를 넘는 부하(더 큰 Stress 목표치)에서 새 포화점이 어디인지, 그 시점에도 Pool
  조정으로 대응 가능한지는 실제 트래픽 규모 전망이 나오면 후속으로 확인한다.
- `docs/040-architecture/adr/README.md`에 이번 결정(회차 조회 배치화, 320 iter/s까지는 Pool 미조정)을
  ADR-0001 보강 근거로 추가할지는 Human 판단 필요.
- **시나리오 D(통합 피크, A+B 동시 실행)는 이번 범위에서 진행하지 않기로 결정**(대화창 논의,
  Human 결정). A(최대 320 iter/s까지 개별 확인)·B(#60으로 최종 확정, 500명까지 락 경합 없음)
  둘 다 개별 검증이 이미 충분히 넓은 범위에서 끝났고, D가 새로 확인할 수 있는 건 사실상
  "같은 HikariCP 풀을 A·B가 동시에 쓸 때의 상호작용 효과" 하나뿐인데 그 가능성 자체가
  낮다고 판단했다(A의 병목도 풀이 아니라 CPU였음, B는 풀을 거의 안 씀). 실제 트래픽에서
  조회 폭주와 예약 경쟁이 동시에 몰리는 정황이 관측되면 그때 D를 진행한다.
