# #63 공통 K6 Harness

Issue #63(공통 K6 Harness·주요 API Load/Stress·성능 지도) 구현. 실제 AWS 실행 환경은
Issue #207에서 별도 Test App EC2 + Test RDS 형태로 준비했다.

AWS 테스트 환경의 구성·접근 방법은 [`docs/090-testing/performance/k6-aws-test-environment.md`](../docs/090-testing/performance/k6-aws-test-environment.md)를 참고한다.

## 구조

```text
k6/
├─ common/
│  ├─ config.js     BASE_URL/STAGE/RUN_ID 등 환경변수, 공통 tag
│  ├─ auth.js       로그인·회원가입 helper, Authorization 헤더
│  ├─ helpers.js    공통 header·tag를 붙인 HTTP 요청 래퍼
│  ├─ checks.js     공통 check(status, ApiResponse.success)
│  └─ fixture.js    Restaurant/SharedTable/DiningSession Fixture 생성
├─ scenarios/
│  ├─ restaurant-search.js               P0-A. GET /api/restaurants
│  ├─ dining-session-availability.js     P0-B. GET /api/restaurants/{id}/dining-sessions
│  ├─ reservation-prepare.js             P0-C. POST /api/reservations/prepare (CREATE)
│  ├─ peak-restaurant-view.js            #142 시나리오 A. 같은 식당·날짜로 조회가 몰리는 상황
│  └─ peak-reservation-create-race.js    #142 시나리오 B. 같은 회차 동시 CREATE 경쟁
├─ data/
│  └─ SEED_CONTRACT.md   Fixture seed·cleanup 계약(Issue #63 "테스트 데이터 계약")
└─ README.md
```

## 사전 준비

- [k6](https://k6.io) 설치(`brew install k6` 등).
- 대상 서버가 떠 있어야 한다. 로컬 검증은 `./gradlew bootRun`으로 앱을 띄운 뒤 진행한다(기본 포트 8080).
- AWS 테스트 환경은 실행자의 공인 IPv4가 Test App EC2 Security Group 8080에 `/32`로 허용돼 있어야 한다.

## 실행 방법

```bash
# 로컬 Smoke — 스크립트가 실제 API를 정상 호출하는지만 확인(성능 결론에 쓰지 않음)
k6 run -e STAGE=smoke k6/scenarios/restaurant-search.js
k6 run -e STAGE=smoke k6/scenarios/dining-session-availability.js
k6 run -e STAGE=smoke k6/scenarios/reservation-prepare.js

# AWS Test App EC2
k6 run -e STAGE=smoke  -e BASE_URL=http://<test-ec2-public-ip>:8080 k6/scenarios/restaurant-search.js
k6 run -e STAGE=load   -e BASE_URL=http://<test-ec2-public-ip>:8080 k6/scenarios/restaurant-search.js
k6 run -e STAGE=stress -e BASE_URL=http://<test-ec2-public-ip>:8080 k6/scenarios/restaurant-search.js
```

공통 환경변수:

| 변수 | 의미 | 기본값 |
|---|---|---|
| `BASE_URL` | 대상 서버 | `http://localhost:8080` |
| `STAGE` | `smoke`\|`load`\|`stress` | `smoke` |
| `RUN_ID` | 실행 식별자(Grafana/Evidence와 시간축 연결용) | `local-<timestamp>` |
| `LOAD_RATE` / `LOAD_VUS` / `LOAD_DURATION` | Load 단계 세부값 | 시나리오별 기본값 참고 |
| `STRESS_START_RATE` / `STRESS_START_VUS` | Stress 시작값 | 시나리오별 기본값 참고 |

`reservation-prepare.js` 전용:

| 변수 | 의미 | 기본값 |
|---|---|---|
| `SESSION_POOL_SIZE` | Fixture로 만들 미사용 회차 수(총 반복 수 이상 필요) | smoke=10, load=8000, stress=50000 |
| `MEMBER_POOL_SIZE` | Fixture로 만들 회원 계정 수 | 20 |
| `BASE_DATE` | 회차를 만들 기준 날짜(YYYY-MM-DD) | 실행일+1일 |
| `THINK_TIME_SECONDS` | 반복마다 넣는 sleep(회차 풀 소비 속도를 낮춰 duration을 버티게 함) | smoke=0, load/stress=1 |
| `FIXTURE_INTERVAL_MINUTES` / `FIXTURE_MAX_DAYS` / `FIXTURE_MAX_TABLES` | 회차 풀을 만들 때 쓸 간격·최대 날짜 수·최대 테이블 수(이 세 값의 곱이 확보 가능한 최대 풀 크기) | 15 / 60 / 30 |

`SESSION_POOL_SIZE`가 `FIXTURE_MAX_TABLES × FIXTURE_MAX_DAYS × 하루 슬롯 수`를 넘으면 Fixture 생성 자체를 시작하지 않고 즉시 실패한다. 하루 슬롯 수는 `24:00`을 endTime으로 쓸 수 없어(서버가 `currentStart < endTime`으로 슬롯을 만듦) `1440/interval`이 아니라 `1439`을 interval로 나눈 몫이다(15분 간격이면 96이 아니라 95). 기본값(15분·60일·30테이블) 기준 최대 약 171,000건까지 확보 가능하다.

## 부하 모델 (Issue #63 Q3 Human 결정)

절대 VU/RPS 숫자를 미리 성공 기준으로 고정하지 않는다.

- **Smoke**: 기능 검증용. 매우 작은 VU/rate, 짧은 duration.
- **Load**: 조회형(`restaurant-search`, `dining-session-availability`)은 `constant-arrival-rate`로 일정 요청 속도를 유지하며 안정 구간(5분)을 확인한다. 상태 변경형(`reservation-prepare`)은 `constant-vus`로 동일하게 확인한다.
- **Stress**: 조회형은 `ramping-arrival-rate`, 상태 변경형은 `ramping-vus`로 3~5분 간격 계단식(staircase)으로 올리다 첫 병목 전환점(p95/p99 급증, 오류율 증가, Hikari pending, CPU 포화, lock wait, timeout/deadlock 중 하나)이 보이면 그 시점에 중단한다.
- 각 시나리오의 기본 rate/VU 값은 시작값일 뿐이다. 실제 값은 최초 Smoke/Load 실행 결과를 보고 `-e`로 조정한 뒤 Evidence에 실제 실행 값을 기록한다(Issue #63 "성공 기준 원칙" — 실제 트래픽·SLA 없이 임의 숫자를 성공 사실로 만들지 않는다).

## 관측 시간축 연결 (AWS 실행 시)

매 실행마다 다음을 기록해 K6 결과와 Prometheus/Grafana를 같은 시간축으로 묶는다(Issue #63 "관측 시간축 연결").

- K6 시작/종료 시각, timezone
- `RUN_ID`, Commit SHA, scenario 이름, 환경
- 이 값들로 Grafana에서 같은 시간 범위의 HTTP/JVM/DB/Redis/Outbox 지표를 조회

## 결과 저장

```text
docs/110-records/evidence/v3/63-api-k6/README.md    #63 결과표·병목 전환점·비교 조건
docs/110-records/evidence/v3/63-api-k6/raw/         #63 k6 JSON/요약, Prometheus 쿼리 결과
docs/110-records/evidence/v3/142-reservation-peak/README.md   #142 결과표·병목 전환점·비교 조건
docs/110-records/evidence/v3/142-reservation-peak/raw/        #142 k6 JSON/요약, Prometheus 쿼리 결과
```

Fixture 시딩과 실제 Smoke/Load/Stress 실행·Grafana Evidence 작성은 각 성능 테스트 작업에서 이어서 진행한다.

## #142 인기 회차 예약 부하 (시나리오 A·B)

Issue #142는 #63의 Harness를 그대로 재사용한다. 이번 구현 범위도 #63과 동일하게 **코드 +
로컬 검증**까지다.

```bash
k6 run -e STAGE=smoke k6/scenarios/peak-restaurant-view.js
k6 run -e CONCURRENT_USERS=10 k6/scenarios/peak-reservation-create-race.js
```

`peak-reservation-create-race.js` 전용:

| 변수 | 의미 | 기본값 |
|---|---|---|
| `CONCURRENT_USERS` | 같은 회차에 동시에 CREATE를 시도할 회원 수(Issue #142 "초기 후보 단계": 2→5→10→20→50) | 10 |
| `BASE_DATE` | 경쟁 대상 회차를 만들 기준 날짜(YYYY-MM-DD) | 실행일+1일 |

**범위 한계**: 이 시나리오는 CREATE 경쟁만 다룬다. JOIN 기반 좌석초과 테스트는 결제 완료가
전제(`ReservationPreparationService` Javadoc)라 Fake 결제 확인 어댑터 없이는 k6로 자동화할 수
없다. 상세 근거와 로컬 검증 결과는 `docs/110-records/evidence/v3/142-reservation-peak/README.md` 참고.
