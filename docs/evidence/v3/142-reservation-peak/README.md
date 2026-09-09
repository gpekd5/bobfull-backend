# #142 인기 회차 예약 부하 측정 — 시나리오 A·B

#207(AWS 실행 환경 준비, `bobfull-k6-test-app` EC2)이 완료돼 실제 AWS 대상으로 시나리오 A는
Load/Stress까지 실행해 첫 병목 전환점을 확인했다(아래 "결과 표 비교" 참고). 로컬 Prometheus를
이 인스턴스의 `/actuator/prometheus`에 직접 연결해 CPU/DB Pool 지표까지 같은 시간축으로
확인했다(Issue #142 "K6 summary만으로 병목을 단정하지 않는다" 원칙 준수). Prometheus
`query_range` 원본 응답은 `raw/prometheus/`에 저장했다. Grafana 시각화는 admin 비밀번호·
Slack 연동이 필요해 이번 범위에서는 붙이지 않고 Prometheus HTTP API로 직접 쿼리했다.

시나리오 B는 착수 당시 팀 합의(Human 결정, Issue #142 댓글)에 따라 "현재 비관적 락 전략 기준의
Before Baseline"으로 측정하고, #60(예약 좌석 락 전략 재설계)이 새 전략을 확정하면 동일 조건으로
After를 재측정할 계획이었다. **#60이 [PR #234](https://github.com/bobfull-project/bobfull-backend/pull/234)로
2026-08-12 병합되면서 Decision Matrix가 예약 좌석 경쟁에 대해 "비관적 락 유지(현재 구조가 이미
충분함을 실측으로 확인, 새 전략 미도입)"로 결론났다** — 즉 재측정할 After(새 전략) 자체가
생기지 않는다. 이에 따라 시나리오 B의 아래 결과는 Before Baseline이 아니라 **최종 결과**로
재분류한다. A는 원래부터 #60과 무관해 최종 결과로 취급한다.

(참고: #234의 재검토 조건은 "인기 회차 부하(#142)에서 lock wait가 실제 병목으로 확인되면
재검토"였는데, 아래 "B의 DB Pool·Lock 지표"가 보여주듯 500명 동시 CREATE 경쟁까지도 락 대기·
DB Pool 병목 신호가 관측되지 않아 이 재검토 조건도 트리거되지 않았다 — 두 Issue의 실측이
서로를 뒷받침한다.)

## 측정·재현 환경 (Issue #142 "측정 환경 계약")

- 기준 Branch·Commit: `feature/142-peak-load-test`, 이 문서 갱신 시점 최신 커밋(PR #220 Conversation 참고)
- 애플리케이션 인스턴스: `bobfull-k6-test-app` EC2 1대, t3.small(2 vCPU, 버스터블) — 인스턴스 CPU 크레딧 소진 여부는 미확인(위 "이 결론의 한계" 참고)
- MySQL: 버전·인스턴스 세부사항 미확인(Test RDS, `bobfull_test` 스키마 사용 — `docs/operations/infra/k6-aws-test-environment.md` 참고). 테스트 데이터 규모는 시나리오별 `setup()`이 그때그때 만드는 합성 데이터뿐(수십~수백 건 수준, 대량 사전 시딩 없음)
- DB Connection Pool 크기: HikariCP 기본값 `maximum-pool-size=10`(코드에 명시적 설정이 없었음, 이번 PR에서 `DB_POOL_MAX_SIZE` env var로 오버라이드 가능하게 함 — 이번 측정 시점에는 여전히 10)
- K6 VU·arrival-rate·duration: 시나리오 A는 `constant-arrival-rate`(Load, 20 iter/s·5분)/`ramping-arrival-rate`(Stress, 20→320 iter/s 계단식·13분, `preAllocatedVUs=50~300`). 시나리오 B는 `per-vu-iterations`(VU=`CONCURRENT_USERS`, 1인당 1회)
- 워밍업: 별도 워밍업 단계 없음(Load/Stress 자체의 첫 구간이 사실상 워밍업 역할). 본 측정은 각 설정별 1~2회(재현성 확인용 반복 포함)
- 맛집·회차·좌석 수·동시 사용자 수: 시나리오 A는 식당 1개·회차 1일치(96슬롯), 시나리오 B는 식당 1개·좌석 4석 테이블 1개·회차 1개(경쟁 대상), 동시 사용자 수는 표 참고(A는 iter/s, B는 `CONCURRENT_USERS`)
- 캐시: 이번 두 시나리오 모두 앱의 기존 Redis 캐시(#62, 검색 결과 캐시) 대상 API를 직접 사용하지 않음 — A는 식당 상세/회차 조회(캐시 미적용 경로), B는 예약 준비(캐시 없음)
- Outbox·알림 Adapter: 이번 시나리오는 결제 완료·알림 발생 경로를 타지 않아 해당 없음
- 외부 결제: 호출하지 않음(CREATE는 READY Payment만 생성, 완료 처리 없음 — 위 "범위 한계" 참고)
- 로컬 환경(별도 비교용): `./gradlew bootRun`(`spring.profiles.active=local`), `docker-compose`(MySQL 8.4, Redis 7) — 운영/AWS 환경과 CPU·네트워크 특성이 달라 병목 전환점 근거로 쓰지 않는다.
- Fixture: 시나리오별 `setup()`에서 API로 직접 생성(운영 데이터 없음, 합성 데이터만 사용).

## 시나리오 A — 예약 페이지 조회 폭주 (`peak-restaurant-view.js`)

같은 식당·같은 날짜를 반복 조회해 한 key에 요청이 몰리는 패턴을 모사한다(#63의 `restaurant-search.js`는
무작위 키워드로 여러 식당에 걸친 균등 부하를 모사하는 것과 대조적).

| 실행 | 결과 | Raw |
|---|---|---|
| 로컬 Smoke(`STAGE=smoke`) | PASS — checks 84/84(100%), http_req_failed 0% | `raw/A-peak-restaurant-view-smoke.log`, `.json` |
| AWS Smoke(`BASE_URL=http://15.164.48.39:8080`) | PASS — checks 84/84(100%), http_req_failed 0%, p95≈434ms(로컬 대비 네트워크 왕복 포함) | `raw/A-peak-restaurant-view-AWS-smoke.log`, `.json` |
| AWS Load(`STAGE=load`, 20 iter/s·5분, p99 포함 재실행) | 안정 처리 구간(실패율 0.00%)이지만 여유는 크지 않음 | `raw/A-peak-restaurant-view-AWS-load-2.log`, `.json`(최신, p99 포함) |
| AWS Stress(`STAGE=stress`, 20→320 iter/s 계단식·13분, p99+Prometheus 포함 재실행) | **첫 병목 전환점 확인 + 근본 원인 확인** — 아래 "결과 표 비교" 참고 | `raw/A-peak-restaurant-view-AWS-stress-3.log`, `.json`(최신, p99+Prometheus 포함) |

이전 실행(`A-peak-restaurant-view-AWS-load.*`, `-stress.*`, `-stress-2.*`)은 p99가 없는 k6
기본 `summaryTrendStats`로 실행한 초기 기록이라 raw로만 남기고, 아래 비교표는 p99를 포함한
최신 실행(`-load-2`, `-stress-3`) 기준으로 작성한다. `http_reqs`(실제 HTTP 요청 수·속도)와
`iterations`(k6 반복 수·속도)를 구분해서 표기한다 — 이 시나리오는 반복(iteration) 1회당
GET 요청 2건(식당 상세 + 회차 조회)을 보내므로 두 값이 다르다.

### 결과 표 비교 — AWS Load vs Stress (`bobfull-k6-test-app`, t3.small)

| 단계 | 목표 부하(iteration 기준) | HTTP RPS(`http_reqs`) | Iteration/s(`iterations`) | p50 | p90 | p95 | p99 | max | 오류율(`http_req_failed`) | dropped_iterations | CPU/DB Pool | 정합성 | 병목 후보 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| Load | constant-arrival-rate 20 iter/s, 5분 | 39.8 req/s | 19.9 iter/s | 177ms | 261ms | 408ms | 962ms | 2.46s | 0.00%(0/11,973) | 16(0.05/s) | 미관측(이 실행은 Prometheus 연결 전) | checks 100% | 아직 없음 — 안정 구간이나 p99가 이미 1초에 근접 |
| Stress | ramping-arrival-rate 20→40→80→160→320 iter/s, 13분 | 51.4 req/s(목표 640 req/s 대비 크게 못 미침) | 25.7 iter/s | 2.30s | 10.15s | 13.14s | **19.62s** | 31.98s | 0.02%(9/40,701) | 61,851(78.2/s) | **CPU 88~98%, HikariCP active 10/10(고정), pending ~190건** | checks 99.98% | **CPU + DB Pool 동시 포화(아래 근본 원인 확인 참고)** |

**해석**: 오류율은 낮게 유지됐지만(0.00~0.02%), p95가 408ms → 13.14초로 32배, p99는 962ms →
19.62초로 20배 급증했고 `dropped_iterations`가 6만 건대까지 치솟았다. 서버가 **에러를 내며
실패하는 게 아니라, 요청이 쌓여 점점 느려지는 방식으로 포화**됐다 — 전형적인 자원 고갈
(saturation) 신호다. 이전 실행(p99 미포함, `raw/A-peak-restaurant-view-AWS-load.json`/
`-stress.json`/`-stress-2.json`)의 p50/p90/p95도 이번 재실행과 오차 범위 안에서 일치해
재현 가능함을 재확인했다(예: Stress p95 13.90s/12.98s(이전) vs 13.14s(이번)).

### 근본 원인 확인 — 로컬 Prometheus를 AWS 인스턴스에 연결해 실측

`monitoring/docker-compose.yml`의 Prometheus를 실행자 로컬에서 띄우고
`BOBFULL_BACKEND_METRICS_TARGET=15.164.48.39:8080`으로 지정해, AWS Test App EC2의
`/actuator/prometheus`를 직접 스크래핑했다. 아래 표는 최신 Stress(p99 포함, `-stress-3`)
실행 중 수집한 값이다. Prometheus `query_range` 원본 JSON은
`raw/prometheus/A-Stress-{cpu_process,cpu_system,hikari_active,hikari_pending,hikari_timeout,threads,lock_count,lock_sum}.json`,
Load 구간은 `raw/prometheus/A-Load-*.json`에 저장했다.

Stress 시작 시각(`05:49:44Z`) 기준 경과 시간별 관측값(`query_range`, step=15~30s):

| 경과 | `process_cpu_usage` | `hikaricp_connections_active` | `hikaricp_connections_pending` | `jvm_threads_live_threads` |
|---:|---:|---:|---:|---:|
| t+0s(20 iter/s 시작) | 52% | 0 | 0 | 66 |
| t+90s | 81% | 4 | 0 | 78 |
| t+150s(40 iter/s 단계 중) | **93%** | **10(=max)** | **45** | 88 |
| t+210s | 94% | 10 | 192 | 232 |
| t+420s(160 iter/s 단계) | 96% | 10 | 191 | 232 |
| t+780s(320 iter/s 단계) | 97% | 10 | 190 | 232 |

이전 실행(`-stress-2`, raw로 별도 보존)에서도 같은 시점 기준 CPU 88~98%, `active=10`(고정),
`pending` 190건 전후로 거의 동일한 값이 나와 재현 가능함을 확인했다.

**결론**: CPU와 DB Connection Pool(HikariCP `maximum-pool-size=10`)이 목표 부하가 40 iter/s
단계에 도달한 시점(경과 약 2분 30초, 아직 Stress 최고 단계 320 iter/s에 훨씬 못 미친 지점)부터
**거의 동시에 포화**되고, 이후 160·320 iter/s 단계까지도 그 상태가 그대로 유지된다(더 나빠지지도
않음 — 이미 최대치라 더 나빠질 여지가 없다). Tomcat 스레드도 232에서 고정돼 요청이 스레드
단계에서부터 쌓이고 있음을 보여준다. 즉 **"어느 시점 이후 갑자기 무너진 병목"이 아니라
"t3.small(2 vCPU) 인스턴스 자체의 CPU·커넥션 풀 용량이 같은 식당·날짜 반복 조회 40 iter/s(약 80 req/s)
수준에서 이미 한계"**라는 게 이번 기록의 근거 있는 결론이다. `hikaricp_connections_pending`이 약 190건으로
안정된 것도 "더 큰 장애로 확산"되지 않고 일정한 대기 상태로 버티는 것으로 해석된다(요청이
실패하지 않고 느려지기만 하는 이유).

이 결론의 한계: (1) 커넥션 풀 크기(10)가 애플리케이션 기본값인지 이 테스트 인스턴스만의 설정인지
확인하지 않았다 — 운영 환경 설정과 다를 수 있다. (2) CPU 포화가 "쿼리 자체가 무겁다"(#61
영역)와 "단순히 요청량 대비 vCPU가 부족하다"(인스턴스 크기 문제) 중 어느 쪽이 더 큰 비중인지는
쿼리별 CPU 프로파일링 없이는 구분하지 못한다. (3) t3.small은 버스터블 인스턴스라 CPU 크레딧
소진 여부도 확인하지 않았다.

## 시나리오 B — 예약 버튼 동시 클릭 (`peak-reservation-create-race.js`)

**범위 한계(Human 결정, 대화창 논의)**: 이 시나리오는 **CREATE 경쟁만** 다룬다. Issue #142가
원래 언급하는 "이미 만들어진 예약에 JOIN으로 몰려 좌석초과가 막히는지"는 이번 범위에 없다 —
`ReservationPreparationService` 클래스 Javadoc에 "결제 성공 전에는 Reservation·
ReservationParticipant를 생성하지 않는다"고 명시돼 있어, JOIN 대상이 되려면 최초 참여자의
결제가 실제로 완료돼야 한다. 이 저장소엔 PortOne을 대신할 Fake 결제 확인 어댑터가 없고, 실제
결제 완료는 PortOne 결제창을 통한 카드 결제라 k6로 자동화할 수 없다(Issue #142 "제외 범위"의
"PortOne 실서비스 반복 결제 요청"과도 충돌). JOIN 기반 좌석초과 테스트는 Fake 결제 확인
어댑터가 별도 Issue로 추가되면 이어서 다룬다.

CREATE 경쟁은 결제 완료 없이도 완전히 검증 가능하고, "인기 회차가 열리는 순간 몰리는" 상황을
오히려 더 정확히 모사한다.

### 결과 — 로컬 (Issue #142 "초기 후보 단계": 2 → 5 → 10 → 20 → 50)

| 동시 사용자 수 | 성공(200) | 충돌(409 ACTIVE_RESERVATION_ALREADY_EXISTS) | 예상 밖 응답 | 실패 기준 | teardown 독립 검증 | Raw |
|---:|---:|---:|---:|---|---|---|
| 2 | 1 | 1 | 0 | PASS | PASS(배타 선점 유지 확인) | `raw/B-create-race-2users.*` |
| 5 | 1 | 4 | 0 | PASS | PASS | `raw/B-create-race-5users.*` |
| 10 | 1 | 9 | 0 | PASS | PASS | `raw/B-create-race-10users.*` |
| 20 | 1 | 19 | 0 | PASS | PASS | `raw/B-create-race-20users.*` |
| 50 | 1 | 49 | 0 | PASS | PASS | `raw/B-create-race-50users.*` |

### 결과 — AWS(`bobfull-k6-test-app`, 동일 동시성 단계, 지표 분리 재실행)

**(PR #220 재검토 반영, hyeonseung-dev MAJOR 1) 아래 p95/p99는 CREATE 경쟁 요청만 기록하는
`peak_create_race_duration` Trend 기준이다.** 이전 표(`-AWS-v2.*`)의 p95/p99는 `setup()`의
회원가입·로그인 요청(500명 실행 기준 1,002회)까지 합산된 전역 `http_req_duration`이었다 —
리뷰가 지적한 대로 이 값만으로는 "500명 동시 경쟁"의 실제 지연을 대표한다고 보기 어려웠다.
`peak-reservation-create-race.js`에 CREATE 요청 하나만 기록하는 별도 Trend를 추가해
재실행했다(`-AWS-v3.*`).

| 동시 사용자 수 | 성공(200) | 충돌(409) | 예상 밖 응답 | p95(`peak_create_race_duration`, 경쟁 요청만) | p99 | 실패 기준 | teardown 독립 검증 | Raw |
|---:|---:|---:|---:|---:|---:|---|---|---|
| 2 | 1 | 1 | 0 | 24.74ms | 24.98ms | PASS | PASS | `raw/B-create-race-2users-AWS-v3.*` |
| 5 | 1 | 4 | 0 | 37.81ms | 38.30ms | PASS | PASS | `raw/B-create-race-5users-AWS-v3.*` |
| 10 | 1 | 9 | 0 | 47.20ms | 48.52ms | PASS | PASS | `raw/B-create-race-10users-AWS-v3.*`(1차 시도는 아래 "네트워크 타임아웃" 참고, 표 값은 재시도 결과) |
| 20 | 1 | 19 | 0 | 75.66ms | 78.62ms | PASS | PASS | `raw/B-create-race-20users-AWS-v3.*` |
| 50 | 1 | 49 | 0 | 200.57ms | 205.68ms | PASS | PASS | `raw/B-create-race-50users-AWS-v3.*` |
| 100 | 1 | 99 | 0 | 342.16ms | 353.09ms | PASS | — | `raw/B-create-race-100users-AWS-v3.*` |
| 200 | 1 | 199 | 0 | 655.13ms | 684.47ms | PASS | — | `raw/B-create-race-200users-AWS-v3.*` |
| 500 | 1 | 499 | 0 | 1.79s | 1.84s | PASS | — | `raw/B-create-race-500users-AWS-v3.*`(`setupTimeout=180s` 필요, 아래 참고) |

이전 표(`-AWS-v2.*`, p95 102.8ms~1.35s)는 raw로 보존하고, 위 표를 최종 기준으로 삼는다. 흥미롭게도
경쟁 요청만 분리해도 지연은 여전히 동시 사용자 수에 비례해 늘어난다(2명 25ms → 500명 1.79초) —
즉 이전 결과가 setup 트래픽과 섞여서 "우연히 비슷하게" 나온 게 아니라, **경쟁 자체가 동시성이
커질수록 느려지는 게 실제로 관측된다**는 뜻이다. 다만 이는 서버의 락/DB Pool 지표(아래 "B의
DB Pool·Lock 지표" 참고 — HikariCP active 최대 1, pending 항상 0, 락 호출당 평균 시간도
40→28ms로 오히려 낮아짐)와는 맞지 않는다 — 서버 내부는 여유가 있는데 클라이언트가 관측하는
요청 지연은 동시성에 비례해 늘어난다는 뜻이며, 500개 HTTP 연결을 동시에 열고 응답을 기다리는
**Tomcat 커넥션·스레드 수준의 대기이거나 k6 클라이언트 자체가 500개 연결을 동시에 처리하며
받는 부하**일 가능성이 높다(둘 다 정확히 구분하려면 서버 쪽 Tomcal thread pool 지표와
k6 클라이언트 리소스 사용률을 함께 봐야 하는데, 이번 범위에서는 하지 못했다 — 후속 과제).

**`vus`(활성 VU 게이지) vs `vus_max`(설정된 최대 VU) 구분(PR #220 재검토 반영)**: 리뷰가 인용한
"vus.max=219"는 실행 중 **순간적으로 동시에 활성 상태였던 VU 수의 피크**(`vus` 게이지, 이번
500명 v3 재실행에서는 461)를 가리킨 것으로 보인다 — `per-vu-iterations` executor는 VU를 순간
전부가 아니라 점진적으로 기동하므로 이 게이지는 설정값보다 낮게 나오는 게 정상이다. 500명
모두가 실제로 CREATE를 1회씩 시도했는지는 이 게이지가 아니라 `iterations` 카운터(500)와
`peak_create_race_success+conflict+unexpected` 세 Counter의 합(1+499+0=500)으로 확인해야
하고, 모든 실행에서 이 합은 정확히 `CONCURRENT_USERS`와 일치했다(`vus_max`도 항상
`CONCURRENT_USERS`와 정확히 일치 — 위 표의 각 raw 파일에서 확인 가능).

**오류율 지표 사용 시 주의(PR #220 재검토 반영, hyeonseung-dev MINOR)**: k6 기본 `http_req_failed`는
2xx가 아닌 응답을 전부 "실패"로 집계하므로, 이 시나리오에서는 **의도된 경쟁 패자의 409
응답까지 실패로 잡힌다** — 예를 들어 500명 실행에서 `http_req_failed`는 약 33%이지만 이건
실제 서버 오류가 아니라 "500명 중 499명이 예상대로 409를 받았다"는 정상 결과다. 실제 오류
여부를 판단할 때는 `http_req_failed`가 아니라 `peak_create_race_unexpected` Counter(200도
409도 아닌 응답만 집계, 모든 실행에서 0)를 봐야 한다. `peak_create_race_success`(항상 1)·
`peak_create_race_conflict`(항상 `CONCURRENT_USERS-1`)·`peak_create_race_unexpected`(항상 0)
세 Counter의 합이 실제 완료된 CREATE 시도 수와 같아야 하고, `options.thresholds`가 이 세
불변식을 자동으로 강제한다(트러블슈팅 섹션 참고).

### B의 DB Pool·Lock 지표 (Prometheus, 100/200명 및 500명 재실행 중 수집)

CREATE는 `TimeSlotRepository.findWithLockByIdAndDeletedAtIsNull`로 비관적 락을 건다(ADR
0001). 이 메서드의 Spring Data 호출 지표(`spring_data_repository_invocations_seconds_*`)를
호출 전후 델타로 계산해 "락 대기를 포함한 평균 처리 시간"의 대체 지표로 썼다(순수 InnoDB
`SHOW ENGINE INNODB STATUS` 레벨의 row lock wait/deadlock 카운터는 MySQL exporter가 없어
관측하지 못했다 — 아래 한계 참고).

| 동시 사용자 수 | `findWithLockBy...` 호출 수 | 호출당 평균 시간 | HikariCP active(최대) | HikariCP pending(최대) | HikariCP timeout(구간 내 신규) | 서버 미처리 예외(구간 내 신규) | CPU(최대) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100~200(연속 실행) | 101·201건 | 40.0ms·35.1ms | 1 | 0 | 0건(9→9) | 0건(10→10) | 44% |
| 500 | 501건 | 27.8ms | 1 | 0 | 0건(18→18) | 0건(19→19) | 44% |

`HikariCP timeout`은 `hikaricp_connections_timeout_total`(커넥션 획득 자체를 실패한 요청
수)의 구간 시작~종료 값이고, `서버 미처리 예외`는 `bobfull_business_events_total{event="UNHANDLED_EXCEPTION"}`
(deadlock 등 커스텀 처리되지 않은 예외가 발생하면 증가하는 앱 자체 카운터 — MySQL
`SHOW ENGINE INNODB STATUS` 레벨 deadlock 카운터는 아니지만, 발생 시 애플리케이션단에서
잡히지 않은 예외로 이어질 가능성이 높아 대체 신호로 썼다)이다. 둘 다 각 구간에서 **0건**
증가해, 커넥션 타임아웃이나 처리되지 않은 서버 오류(deadlock 포함) 없이 CREATE 경쟁이
처리됐음을 확인했다.

**해석**: 100→500명으로 동시성이 5배 늘어도 락 호출당 평균 시간이 오히려 낮거나 비슷하게
유지되고(40→35→28ms), `HikariCP active`는 최대 1(=풀의 10%), `pending`은 항상 0이었다 —
**락 경합이나 DB Pool 대기로 인한 병목 신호가 이 범위(최대 500명)에서는 관측되지 않았다.**
시나리오 A(지속적 조회 부하)와 뚜렷이 다른 점이다 — B는 총 쿼리 수 자체가 적은 짧은 burst라
동일 인스턴스에서도 CPU/Pool을 거의 쓰지 않는다.

**한계**: (1) InnoDB 레벨의 실제 row lock wait/deadlock 카운터는 MySQL exporter가 없어
관측하지 못했다 — 위 지표는 Spring Data 호출 시간(락 획득+쿼리 실행 포함)의 근사치다. (2)
500명을 넘는 동시성에서 락 경합이 나타나는지는 확인하지 못했다(아래 "환경 한계" 참고).

**환경 한계(서버가 아니라 하네스)**: `CONCURRENT_USERS=1000`은 `setup()`이 180초를 넘겨
실패했다(`setup() execution timed out after 180 seconds`). `setup()`이 회원가입·로그인을
순차로 하기 때문(1,000명이면 2,002회 순차 HTTP round-trip)이지, 서버가 느려진 게 아니다 —
그 180초 동안 서버는 초당 약 10~16 요청을 오류 없이 처리하고 있었다(`raw/B-create-race-1000users-AWS.log`
참고, `http_req_failed=0%`). 500명까지는 `setupTimeout`을 늘리는 것만으로 정확히 1명 성공을
재확인했다. 더 큰 동시성을 보려면 `setup()`을 병렬화하거나(예: 여러 회원을 동시에 가입) 미리
만들어둔 회원 풀을 재사용하는 방식으로 하네스를 고쳐야 한다(이번 범위 밖, 후속 개선 후보).

로컬·AWS 모든 동시성 단계에서 정확히 1명만 성공하고 나머지는 전부
`409 ACTIVE_RESERVATION_ALREADY_EXISTS`였다. `checks_succeeded`는 매 실행 100%였고
`peak_create_race_unexpected` 카운터는 항상 0이었다. `teardown()`에서 새 회원으로 같은
회차에 CREATE를 한 번 더 시도해도 409가 나, 경쟁 종료 후에도 배타 선점이 깨지지 않음을
독립적으로 재확인했다. 동시성이 올라갈수록 요청 지연이 늘어나는 현상은 위 "결과 — AWS"
표(`peak_create_race_duration`, 경쟁 요청만 분리한 지표)에 이미 반영돼 있다.

**이 결과는 최종 결과다.** 착수 당시에는 "현재 비관적 락 전략 기준의 Before Baseline"으로
두고 #60(예약 좌석 락 전략 재설계)이 끝나면 After를 재측정할 계획이었으나, #60이
[PR #234](https://github.com/bobfull-project/bobfull-backend/pull/234)로 2026-08-12
병합되면서 Decision Matrix가 "비관적 락 유지(새 전략 미도입)"로 결론나 재측정할 After 자체가
생기지 않는다(위 "요약" 참고). #234의 재검토 조건("인기 회차 부하(#142)에서 lock wait가 실제
병목으로 확인되면 재검토")도 위 "B의 DB Pool·Lock 지표" 결과(500명까지 병목 신호 없음)에서
트리거되지 않았다.

### 트러블슈팅 — teardown 검증 로직 최초 버그

처음 작성한 `teardown()`은 GET 회차 조회 응답의 `reservationId` 필드가 채워졌는지로 성공을
판정했는데, CREATE prepare 성공(200)은 결제 완료 전이라 `Reservation`이 아직 생성되지 않은
상태다(위 "범위 한계" 참고) — 그래서 실제로는 정상 동작(1명 성공)인데도 매번 "예약이 생성되지
않았다"는 오탐 에러가 찍혔다. 새 회원으로 CREATE를 한 번 더 시도해 409를 받는지로 검증하는
방식으로 고쳤다(`peak-reservation-create-race.js` 참고).

### 트러블슈팅 — 불변식 위반이 k6 실행 실패로 이어지지 않던 문제 (PR #220 리뷰, hyeonseung-dev)

처음 구현은 `peak_create_race_success`/`conflict`/`unexpected` Counter만 기록하고
`options.thresholds`로 묶지 않았다. 그래서 예를 들어 경쟁 버그로 2명이 200을 받아도 Counter
값만 달라질 뿐 k6는 종료 코드 0으로 끝날 수 있었다 — "정확히 1명만 성공"이라는 이 시나리오의
핵심 불변식을 자동으로 실패 처리하는 기준이 없었다. `teardown()`의 배타 선점 재검증도 `console.error`
로그만 남겨 사람이 로그를 읽어야만 위반을 발견할 수 있었다.

`options.thresholds`에 `peak_create_race_success: ['count==1']`,
`peak_create_race_conflict: ['count==CONCURRENT_USERS-1']`, `peak_create_race_unexpected: ['count==0']`,
`checks: ['rate==1.0']`를 추가하고, `teardown()`도 `check()`로 바꿔 같은 실패 기준에 걸리게 했다.
실제로 실패 기준이 동작하는지 별도 실험(threshold를 `count==0`으로 일부러 깨뜨림)으로 확인했다 —
정상 조건에서는 종료 코드 0, 불변식을 강제로 깨면 종료 코드 99(k6 표준 threshold 실패 코드)로
끝난다. 2/5/10/20/50 전 단계를 재실행해 모든 threshold가 PASS임을 다시 확인했다(위 결과 표).

### 트러블슈팅 — 실패 기준이 실제로 잡아낸 네트워크 타임아웃 (지표 분리 재실행 중 발생)

지표 분리 재실행(위 "결과 — AWS" 참고) 중 `CONCURRENT_USERS=10` 1차 시도에서 실패 기준이
실제로 실패했다(`checks 90.90%`, `peak_create_race_unexpected=1`, 종료 코드 99). 로그에서 원인을
확인하니 서버 응답이 아니라 `Post "http://.../api/reservations/prepare": dial: i/o timeout` —
10개 동시 연결 중 1개가 클라이언트→서버 연결 수립 단계에서 타임아웃난 것으로, 애플리케이션
로직이나 락 처리와는 무관한 **일시적 네트워크 오류**다(같은 요청이 서버에 도달해 처리됐다는
증거가 없다 — dial 단계 실패이므로 서버 로그 쪽에는 아예 안 남을 수 있다). Counter만 있었다면
이 1건이 조용히 `peak_create_race_unexpected`에 1로 기록되고 넘어갔을 것을 실패 기준이 종료 코드
99로 확실히 실패시켰다 — 이 트러블슈팅 항목 자체가 실패 기준(바로 위 항목)이 설계대로
동작한다는 방증이다. 즉시 재시도했을 때는 10명 전원 정상(성공 1·충돌 9·예상 밖 0)으로 끝났고, 위 결과 표의 10명
행은 이 재시도 결과다. 1차(실패) 시도의 콘솔 로그는 `raw/B-create-race-10users-AWS-v3.log`에
그대로 남겼고, 재시도(성공) 로그는 `raw/B-create-race-10users-AWS-v3-retry.log`다 — summary
JSON(`raw/B-create-race-10users-AWS-v3.json`)은 재시도 시점에 같은 경로로 다시 내보내져
재시도(성공) 결과로 덮어써졌다.

## A 개선 조치 — HikariCP 커넥션 풀 크기 설정화

`hikaricp_connections_max=10`이 실측(위 "근본 원인 확인")으로 확인됐는데,
`application-prod.yml`/`application-local.yml.example`에는 `spring.datasource.hikari.*`
설정 자체가 없었다 — 즉 10은 애플리케이션이 의도적으로 튜닝한 값이 아니라 **HikariCP
자체의 기본값**이었다.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: ${DB_POOL_MAX_SIZE:10}
```

기존 `REDIS_TIMEOUT` 등과 같은 패턴으로 env var 오버라이드를 추가했다. **기본값은 그대로
10으로 유지**해 이 변경만으로는 운영 동작이 바뀌지 않는다 — #142에서 발견한 값을 근거로
운영 풀 크기를 임의로 올리는 결정은 하지 않았다(쿼리·DB 자원 계획은 #61/#62·Human 판단
영역).

### After 재측정 — `DB_POOL_MAX_SIZE=30` 재배포 후 Stress 재실행

PR #220 머지 후 인프라 담당자(김홍기)가 `bobfull-k6-test-app`을 `DB_POOL_MAX_SIZE=30`으로
재배포했다(프로세스 재시작 `2026-08-12 08:35:58Z` 확인, `hikaricp_connections_max=30.0`
실측으로 재배포 반영 확인). 같은 조건(`STAGE=stress`)으로 Stress를 재실행하고 로컬 Prometheus를
다시 연결해 같은 지표를 수집했다.

| 지표 | Before(풀=10, `-stress-3`) | After(풀=30, `-stress-4-after-pool30`) | 변화 |
|---|---:|---:|---|
| p95 응답시간 | 13.14s | **19.21s** | 46% 악화 |
| p99 응답시간 | 19.62s | **26.86s** | 37% 악화 |
| HTTP RPS(`http_reqs`) | 51.4 req/s | **37.0 req/s** | 처리량 28% 감소 |
| Iteration/s(`iterations`) | 25.7 iter/s | **18.5 iter/s** | 28% 감소 |
| 오류율(`http_req_failed`) | 0.02%(9/40,701) | 0.27%(79/29,345) | 소폭 악화(절대치는 여전히 낮음) |
| dropped_iterations | 61,851건(78.2/s) | **67,529건(85.2/s)** | 9% 악화 |
| CPU(최대) | 88~98% | 97~98% | 동일하게 포화 |
| HikariCP active(최대) | 10(=풀 100%) | **30(=풀 100%)** | 풀을 3배로 늘려도 여전히 100% 포화 |
| HikariCP pending(최대) | ~192건 | ~171건 | 거의 동일 |
| Tomcat 스레드(고정) | 232 | 235 | 거의 동일 |

Raw: `raw/A-peak-restaurant-view-AWS-stress-4-after-pool30.log`, `.json`,
`raw/prometheus/A-Stress-after-pool30-{cpu_process,hikari_active,hikari_pending,threads}.json`

**결론: 풀 크기 확대는 개선이 아니라 악화로 나타났다.** `active`가 여전히 풀 사이즈만큼
꽉 차 있고(10/10 → 30/30, 둘 다 100%) CPU도 동일하게 97~98%로 포화된 채라는 점이 이 결과를
설명한다 — DB Connection Pool 크기는 애초에 진짜 제약이 아니었고, **t3.small(2 vCPU)
인스턴스 자체의 CPU 용량이 진짜 상한선**이었다(위 "근본 원인 확인"의 가설과 일치). 오히려
커넥션·스레드가 늘어난 만큼 2개뿐인 vCPU에서 컨텍스트 스위칭 오버헤드가 커져 처리량이
줄고 지연이 늘어난 것으로 해석된다.

### After 재측정 2 — 인스턴스를 t3.2xlarge(8 vCPU)로 임시 확장 후 Stress 재실행

위 결론에 따라 인스턴스 vCPU 확장이 실제로 도움이 되는지 직접 확인했다. 인프라 담당자가
`bobfull-k6-test-app`을 t3.2xlarge(8 vCPU)로 임시 확장했다(`system_cpu_count=8.0` 실측 확인,
IP가 `3.34.1.161`로 변경돼 새 IP도 함께 확인). `DB_POOL_MAX_SIZE`는 이 시점에도 아직 30으로
남아있었다 — 풀 크기 자체의 영향은 이미 위에서 독립적으로 확인했으므로(같은 CPU=2 조건에서
풀 10→30이 도움이 안 됨), 롤백을 기다리지 않고 이 상태로 먼저 재측정해 CPU 변수만 새로 얹은
효과를 봤다.

| 지표 | ①CPU=2·풀=10 | ②CPU=2·풀=30 | ③**CPU=8·풀=30** |
|---|---:|---:|---:|
| p95 응답시간 | 13.14s | 19.21s | **4.44s** |
| p99 응답시간 | 19.62s | 26.86s | **5.63s** |
| HTTP RPS(`http_reqs`) | 51.4 req/s | 37.0 req/s | **73.5 req/s** |
| 오류율(`http_req_failed`) | 0.02%(9/40,701) | 0.27%(79/29,345) | **0.002%(1/57,881)** |
| dropped_iterations | 61,851건(78.2/s) | 67,529건(85.2/s) | **53,261건(67.6/s)** |
| CPU(최대) | 88~98% | 97~98% | **49%(더 이상 포화 아님)** |
| HikariCP active(최대) | 10(=풀 100%) | 30(=풀 100%) | **30(=풀 100%, 여전히 포화)** |
| HikariCP pending(최대) | ~192건 | ~171건 | ~174건 |

Raw: `raw/A-peak-restaurant-view-AWS-stress-5-after-8vcpu.log`, `.json`,
`raw/prometheus/A-Stress-after-8vcpu-{cpu_process,hikari_active,hikari_pending,threads}.json`

**결론: CPU가 진짜 원인이었다는 게 확증됐다.** CPU를 2→8 vCPU로 늘리자 `process_cpu_usage`가
97~98%에서 49%로 내려가 더 이상 포화 상태가 아니고, p95/p99가 13.14s/19.62s(①) →
4.44s/5.63s(③)로 크게 개선됐으며 오류율도 거의 0으로 떨어졌다. **다만 완전히 해결되지는
않았다** — `HikariCP active`가 여전히 풀 크기(30)만큼 100% 포화돼 있고 `dropped_iterations`도
5만 건대로 목표 부하(320 iter/s)를 여전히 못 따라간다. CPU 여유가 생기자 **DB Connection
Pool 크기(현재 30)가 다음 병목으로 드러난 것**으로 해석된다 — CPU가 부족했던 ①·②에서는 풀을
늘려도 의미가 없었지만, CPU가 충분한 지금은 풀을 더 늘리는 실험이 다시 의미를 가질 수 있다
(이번 PR 범위에서는 실행하지 않음, 후속 후보).

**권장 후속 조치**: (1) 인스턴스 vCPU 확장이 실제로 효과가 있다는 근거를 확보했으니, 운영
환경 인스턴스 크기 조정 여부를 Human/인프라 담당자가 판단(비용 대비 효과 검토). (2) CPU가
충분한 상태를 유지한다는 전제로 `DB_POOL_MAX_SIZE`를 재조정하는 실험이 후속으로 유효함(단,
이번 PR에서 `DB_POOL_MAX_SIZE=30` 자체는 롤백하기로 이미 결정됨 — 아래 "남은 작업" 참고,
인스턴스 크기도 비용 문제로 이 실험 이후 원래 크기로 되돌릴 예정).

## 남은 작업

- (완료) 시나리오 A AWS Load/Stress 실행, 첫 병목 전환점(HTTP 레벨) 확인
- (완료) Prometheus 연결로 근본 원인 확인(CPU+DB Pool 동시 포화)
- (완료) HikariCP 풀 크기 env var 설정화(기본값 10 유지, 실제 조정은 후속)
- (완료) 시나리오 B 동시성 100/200/500까지 확장 확인, 1000은 하네스 setup 한계로 기록
- (완료, PR #220 재검토 반영) raw Evidence JSON에 남아있던 실제 JWT 토큰 제거 + `handleSummary()`로
  재발 방지(BLOCKER), B의 p95/p99를 경쟁 요청만의 별도 Trend로 분리 재측정(MAJOR 1), 잔여
  RPS/iter-s 표기 정리(MAJOR 2), 오류율 지표 해석 주의사항 문서화(MINOR)
- (완료) `DB_POOL_MAX_SIZE=30`으로 재배포 후 Stress 재실행 — **풀 확대는 개선이 아니라 악화로
  확인됨**(위 "After 재측정" 참고). CPU(2 vCPU)가 진짜 상한선이라는 근본 원인 확인의 가설을
  재확인했다.
- (완료) 인스턴스를 t3.2xlarge(8 vCPU)로 임시 확장 후 재측정 — **CPU가 진짜 원인이었음을
  확증**(97~98% → 49%, p95 13.14s→4.44s). 다만 CPU 여유가 생기자 HikariCP 풀(30)이 새 병목으로
  드러남(위 "After 재측정 2" 참고).
- **Human 결정**: 이 실험 이후 인스턴스는 비용 문제로 원래 크기(t3.small)로, `DB_POOL_MAX_SIZE`도
  기본값(10)으로 **둘 다 되돌리기로 확정**(대화창 결정) — 풀을 낮추는 건 지금 병목(CPU=8·풀=30
  조합)에는 역효과이므로 하지 않고, "CPU가 원인이다/ 인스턴스 확장이 효과 있다"는 결론만
  Evidence로 남긴다.
- 운영 환경 인스턴스 크기 조정, 풀 크기 재튜닝(CPU 확장이 실제로 결정된 뒤), RDS
  `max_connections` 여유 확인은 모두 이번 범위 밖 후속 과제로 남긴다.
- (완료) #60(예약 좌석 락 전략 재설계)이 [PR #234](https://github.com/bobfull-project/bobfull-backend/pull/234)로
  병합돼 "비관적 락 유지"로 결론남 — 시나리오 B의 재측정(After)이 더 이상 필요 없어 결과를
  최종으로 재분류함
- A/B 결과가 정리됐으므로 시나리오 C(Redis ZSet 대기열) 도입 여부 판단으로 이어감(팀 합의) —
  단, A는 재배포 후 재측정이 아직 남아있어 그 결과까지 보고 판단하는 게 안전함
- JOIN 기반 좌석초과 테스트는 Fake 결제 확인 어댑터 도입 여부를 별도 결정한 뒤 진행
