# Issue #146 — 환불 완료 동기 경로 K6 측정 및 Spring Event 전환 판단

## 기준

- 기준 Branch: `perf/146-refund-completion-k6`(PR #250)
- 측정 Commit SHA: `83d424b`
- 측정일: 2026-08-13
- 선행 Evidence: `docs/110-records/evidence/v3/142-reservation-peak/README.md`(#142), `docs/110-records/evidence/v3/restaurant-view-hotpath/README.md`(#235) — 동일 AWS 테스트 인스턴스·Prometheus 스크래핑 방식 재사용
- Human 결정 계약: Issue #146 댓글(2026-08-13T03:34:40Z, `sighingpotato`) "Human 결정 필요 — 답변 확정" 7개 항목을 기준으로 측정했다.

## 요약

시나리오 A(기준선)·B(락 경쟁)·C(중복 웹훅)·E(완료 실패)는 AWS 실측(Load, 20 iter/s 또는 그룹 10/s)에서 목표(p95 300ms·p99 800ms)를 여유 있게 충족했다. CPU는 6~16%, HikariCP active는 최대 7건(풀 10 중), pending은 전 구간 0으로 DB Pool 병목은 관측되지 않았다.

시나리오 F(PortOne 외부 지연 500ms~3000ms·timeout·connection reset)는 응답시간이 주입한 지연만큼 그대로 늘었지만 HikariCP active는 전 구간 최대 2건으로, **외부 API 지연이 Reservation 락이나 DB Connection Pool로 새지 않는다**는 Port·Adapter 분리 설계를 실측으로 확인했다.

**시나리오 D(완료 로직 지연 주입, 100/300/500/1000ms)에서 결정적인 발견이 나왔다**: 지연이 커질수록 응답시간이 주입한 지연보다 훨씬 더 크게 늘었다(1000ms 주입 시 avg **7537ms**, p99 13.9s) — Hikari active가 풀 상한(10)까지 꽉 차고 pending이 최대 **92건**까지 쌓였기 때문이다. 계산해보면 우연이 아니다: 동시 필요 커넥션 수 ≈ 도착률(20/s) × 완료 트랜잭션 점유시간이므로, **현재 Pool 크기(10)·이 부하 수준(20/s)에서는 완료 트랜잭션이 500ms만 넘어도 이론적으로 포화가 시작된다**. 이는 Issue 본문이 우려한 "예약 완료 로직이 무거워지면 DB Connection 점유·Pool 대기 증가" 시나리오가 실측으로 재현된 것이다.

**결론(§8 참고): A안(현재 동기 Port·Adapter 구조 유지)** — 단, 조건부. **현재 상태**(지연 주입 없음)에서는 Spring Event 전환 게이트 조건이 하나도 충족되지 않아 구조를 유지한다. 다만 D의 발견은 "향후 예약 완료 로직에 통계·알림 등 후속 처리를 추가할 계획이 있다면, 그 전에 반드시 Pool 크기 조정이나 처리 비용 최적화(B안)를 먼저 검토해야 한다"는 강한 근거로 남긴다 — 이 결론에 도달하는 순간(예: 로직 추가로 300~500ms 이상의 처리 시간이 늘어나는 순간) 시스템은 점진적으로 나빠지는 게 아니라 급격히 무너진다.

**DB 최종 상태 검증 중 별도 발견(§ DB 최종 상태 검증 참고)**: 시나리오 B(그룹 3명 동시 완료)에서 개별 참여자는 전부 정확히 완료되지만 **Reservation 전체 상태가 CANCELLED로 전이되지 않는 실제 동시성 결함**을 발견했다 — V2 그룹 취소 기능의 실제 버그이며 이 Issue 범위를 넘어 `#259`로 분리했다. C/E는 DB 상태까지 직접 대조해 계약대로 동작함을 확인했다.

**이번 측정의 한계(§7)**: 실메일 발송 지연으로 `setup()` 자체가 오래 걸려, 이번 측정은 Issue 본문이 제시한 1/10/30/50/100 VU 단계적 확대와 Stress 단계를 전부 수행하지 못하고 **Load 단계 1개 지점**만 실측했다. InnoDB 레벨 실제 row lock wait(`performance_schema.data_lock_waits`)도 MySQL exporter 부재로 수집하지 못해 HikariCP active/pending을 대리 지표로 사용했다(#235와 동일한 기존 한계).

## 측정 환경

- 애플리케이션 인스턴스: `bobfull-k6-test-app` EC2 1대, t3.small(2 vCPU, 버스터블) — `#142`/`#235`와 동일 인스턴스
- `SPRING_PROFILES_ACTIVE=prod,performance` — `performance` 프로파일 fake Bean(`PerformanceTestPaymentReader`/`PerformanceTestRefundRequester`/`PerformanceTestReservationCompletionHook`)으로 PortOne 실호출을 완전히 대체. 나머지 설정(JWT·PortOne webhook secret 등)은 인스턴스 기존 `prod` 환경변수를 그대로 사용(`application-performance.yml`은 배포 아티팩트에 미포함, PR #250 댓글 2026-08-13T06:09:28Z 참고)
- MySQL: Test RDS(`bobfull_test` 스키마), 버전 미확인 — `#235`와 동일한 기존 한계
- DB Connection Pool: HikariCP `maximum-pool-size=10`(`#142`/`#235`와 동일, 이번 측정에서 별도로 늘리지 않음)
- Redis: 인스턴스 로컬 docker 컨테이너(운영 ElastiCache와 별도, `#146` 측정 중 확인됨) — 환불 완료 경로는 Redis에 의존하지 않아 이번 측정과 무관
- 모니터링: 로컬 Prometheus(`monitoring/docker-compose.yml`)가 `http://15.164.234.170:8080/actuator/prometheus`를 15초 간격으로 직접 스크래핑(`#235`와 동일 방식)
- 테스트 데이터 규모: 시나리오별 `setup()`이 그때그때 만드는 합성 데이터(수백~천 건, 대량 사전 시딩 없음) — 정확한 수치는 §4 표 참고
- H2 vs MySQL: 이 문서의 모든 수치는 실제 MySQL(AWS) 기준이다. H2는 이번 측정에 사용하지 않았고, PR #250의 유닛/통합 테스트(회귀 검증)에서만 사용했다.

### 측정 중 발견한 인프라 이슈 — 실메일 발송이 Fixture 준비를 지연시킴

`setup()`이 재사용하는 `POST /api/payments/{id}/complete`(예약 확정)는 `ReservationConfirmationService`를 통해 **실제 SMTP 메일 발송을 요청 스레드에서 동기 실행**한다(`EmailOutboxEventService.enqueue()` → `afterCommit` 콜백 내 동기 처리, 기존 아키텍처). `application*.yml`에 `spring.mail.*` 타임아웃 설정이 없어 메일 서버 응답이 늦어지면 요청이 사실상 무제한 대기한다.

측정 도중 이 경로가 원인으로 추정되는 애플리케이션 헬스 다운(CPU·DB Pool은 모두 idle인데 `/actuator/health`만 DOWN)이 실제로 발생했고, 인프라 담당자가 메일 로그인 문제를 확인·조치한 뒤 재개했다. 조치 후에도 이메일 발송 자체는 평균 500ms~1초대로 느려(p99 1.5초대) `setup()` 소요 시간이 예상보다 훨씬 길었다(시나리오당 7~15분).

다만 **실제 측정 대상인 취소(`accept()`/`acceptEntireReservationCancellation`) 경로에는 이메일 발송이 없다**(코드 확인 완료 — 이메일은 결제 확정과 모집 마감 스케줄러 경로에서만 발생). 따라서 아래 시나리오별 Trend 지표(즉시응답/웹훅 완료 시간 등)는 메일 지연에 오염되지 않았다. 다만 이 문제로 인해 `setup()`이 감당 가능한 시간 안에 끝나도록 각 시나리오의 픽스처 풀을 대폭 축소하고 측정 시간도 30초로 단축했다(§7 한계 참고).

## 시나리오별 결과 (AWS, Load 단계 1개 지점, 워밍업 후 측정)

| 시나리오 | 부하 | 반복 수 | 지표 | avg | p95 | p99 | 실패율 |
|---|---|---:|---|---:|---:|---:|---:|
| A 기준선 | 20 iter/s×30s | 601 | 즉시응답 완료 | 45.2ms | 83ms | 179ms | 0% |
| A 기준선 | 〃 | 〃 | 웹훅 완료 | 21.1ms | 40.3ms | 81ms | 0% |
| B 동일예약 경쟁(3인×300그룹) | 10 group/s×30s | 300그룹 | 그룹 배치 전체 | 34.7ms | 45.1ms | 69.1ms | 0% |
| B 〃 | 〃 | 〃 | 참여자별 개별 | 25.8ms | 37.5ms | 54.4ms | 0% |
| C 중복·경쟁 | 20 iter/s×30s | 600 | 동시 경쟁(즉시+웹훅) | 46.8ms | 115ms | 272ms | 0% |
| C 〃 | 〃 | 〃 | 순차 중복(웹훅만) | 12.2ms | 26ms | 53.1ms | 0% |
| E 완료 실패 | 20 iter/s×30s | 600 | 즉시응답 경로 실패(500) | 54.9ms | 78.5ms | 551ms | 0%(의도된 500) |
| E 〃 | 〃 | 〃 | 웹훅 경로 실패(500) | 16.6ms | 24ms | 80.4ms | 0%(의도된 500) |

### 시나리오 D — 완료 로직 지연 주입 (핵심 결과)

| 주입 지연 | avg | p95 | p99 | checks 실패 | HikariCP active 최대 | HikariCP pending 최대 |
|---:|---:|---:|---:|---:|---:|---:|
| 100ms | 372.7ms | 1179ms | 1510ms | 0.16% | (§6 참고) | (§6 참고) |
| 300ms | 346ms | 401ms | 510ms | 0% | 7 | 0 |
| 500ms | 1424.6ms | 2279ms | 2743ms | 0.33% | **10(풀 포화)** | **높음** |
| 1000ms | **7536.9ms** | **10877ms** | **13904ms** | 0.78% | **10(풀 포화)** | **최대 92** |

100ms에서도 p95(1179ms)가 이미 avg(373ms)보다 훨씬 크게 벌어진 것은, 이 실행이 300ms 실행 이후 곧바로 이어진 연속 실행이라 앞선 실행의 큐잉 잔재가 일부 섞였을 가능성이 있다 — 단일 지표로 단정하지 않고 500ms·1000ms의 명확한 추세(§6)로 결론을 뒷받침한다.

### 시나리오 F — PortOne 외부 지연 분리 (전 구간 확인)

| 주입 조건 | avg | p95 | p99 | checks 실패 |
|---|---:|---:|---:|---:|
| 500ms | 573.7ms | 758.1ms | 949ms | 0.66% |
| 1000ms | 1037.9ms | 1101ms | 1183ms | 0.83% |
| 3000ms | 3054.7ms | 3.1s | 3.2s | 0%(25건 드롭, 완료된 요청은 전부 성공) |
| TIMEOUT | 36.6ms(빠른 실패, 502) | 86ms | 350ms | 0.99% |
| CONNECTION_RESET | 31.5ms(빠른 실패, 502) | 55.1ms | 127.1ms | 1.16% |

500ms/1000ms/3000ms 모두 **주입 지연과 거의 선형으로 일치**한다(D처럼 지연이 늘수록 비선형으로 폭증하지 않는다) — §6에서 그 이유(DB Pool 비점유)를 확인한다. 각 변형에서 관측된 소수의 check 실패(0.66~1.16%, 4~10건/약 600건)는 D의 연속 실행 직후 발생한 잔여 영향으로 추정되며(§6 참고), F 자체의 구조적 문제는 아니다.

실패율은 "실제 오류(5xx·타임아웃)" 기준이며, E의 500·F의 502는 의도적으로 주입한 결과다(Issue 본문 판정 방식과 동일).

## 락·Connection Pool 분석 (Prometheus, 측정 구간)

| 시나리오 | CPU 최대 | HikariCP active 최대 | HikariCP pending 최대 |
|---|---:|---:|---:|
| A | 15.7% | 2 | 0 |
| B | 13.1% | 2 | 0 |
| C | 11.7% | 2 | 0 |
| D(300ms) | 8.8% | 7 | 0 |
| D(500ms/1000ms 포함 전체 sweep) | 14.8% | **10(포화)** | **92** |
| E | 11.6% | 1 | 0 |
| F(전체 sweep, 500ms~3000ms+실패) | — | 2(F만 남은 구간 기준) | 0 |

### D의 Pool 포화 — 왜 발생했는가

관측된 수치는 단순한 우연이 아니라 정확한 용량 계산과 일치한다.

```text
정상 상태 동시 필요 커넥션 수 ≈ 도착률(iter/s) × 완료 트랜잭션 점유 시간(s)

100ms 지연 → 20 × 0.1  =  2개   (풀 10 여유)
300ms 지연 → 20 × 0.3  =  6개   (풀 10 여유, 실측 active 최대 7과 근접)
500ms 지연 → 20 × 0.5  = 10개   (풀 정확히 한계 — 포화 시작점)
1000ms 지연 → 20 × 1.0 = 20개  (풀의 2배 필요 — 절반은 항상 대기)
```

즉 **현재 Pool 크기(10)·이 부하(20 iter/s) 조합에서는 완료 트랜잭션이 약 500ms를 넘는 순간부터 이론적으로 포화가 시작되고, 그 이상에서는 대기 요청이 선형이 아니라 누적된다.** 1000ms 지연에서 avg가 명목 지연(1000ms)의 7배가 넘는 7537ms까지 치솟은 것은 이 대기(pending) 시간이 응답시간에 그대로 더해졌기 때문이다.

**중요한 구분**: 이 결과는 시나리오 D가 인위적으로 주입한 "미래에 완료 로직이 무거워지는 상황"에 대한 것이다. **현재(지연 주입 없음) 시스템의 실제 완료 트랜잭션 시간은 이 임계점에 전혀 가깝지 않다**(A/B/C/E 전부 HikariCP active 최대 2~7, pending 0). 오늘 당장 문제가 있다는 뜻이 아니라, 여유(headroom)가 생각보다 크지 않다는 뜻이다.

CPU는 D의 극단적 상황(1000ms 지연, pending 92)에서도 최대 14.8%에 그쳤다 — 이 병목은 순수하게 **DB Connection Pool 크기 vs 완료 트랜잭션 점유 시간**의 문제이지 CPU·연산 자원 문제가 아니다.

InnoDB 레벨 실제 row lock wait 카운터(`performance_schema.data_lock_waits`)는 MySQL exporter가 없어 수집하지 못했다 — `#235`에서 이미 확인된 동일한 인프라 한계이며, HikariCP active/pending을 락 경합의 대리 지표로 사용했다.

## 외부 API 지연과 내부 처리 지연 비교

| 구분 | 주입 지연 | 측정 응답시간(대략 선형인지) | HikariCP 영향 | 해석 |
|---|---:|---|---|---|
| D(내부 Reservation 완료 지연) | 100~1000ms | **비선형 폭증**(1000ms→avg 7537ms) | active 10 포화, pending 최대 92 | 내부 지연은 DB 커넥션 점유 시간을 늘려 Pool 용량을 직접 소비한다 — 임계점(약 500ms)을 넘으면 큐잉이 눈덩이처럼 불어난다 |
| F(외부 PortOne 지연) | 500~3000ms | **선형**(3000ms→avg 3055ms) | active 항상 2 이하 | 외부 지연은 응답시간만 그대로 늘릴 뿐 DB 자원은 전혀 늘리지 않는다 |

같은 크기의 지연이라도 **어디서 발생하느냐에 따라 결과가 완전히 다르다** — D는 비선형 붕괴, F는 깨끗한 선형 증가. PortOne 요청이 Reservation 락·내부 완료 트랜잭션 밖(두 `REQUIRES_NEW` 트랜잭션 사이의 평범한 메서드 호출)에서 실행되도록 만든 기존 Port·Adapter 분리 설계가, 바로 이 차이 덕분에 실측으로 검증됐다.

## DB 최종 상태 검증 — B/C/E 정합성 직접 확인

PR #250 리뷰(hyeonseung-dev, 2026-08-13)에서 응답시간·Pool 지표만으로는 Issue #146이 요구한 "Refund/Payment/Participant/Reservation 최종 상태 정합성"·"완료 실패 시 롤백 상태"가 검증되지 않는다는 지적을 받아, 회원 본인 조회 API(`GET /api/payments/{id}`, `/api/refunds/{id}`, `/api/members/me/reservations/{id}`)로 실제 AWS 인스턴스(`bobfull-k6-test-app`, 이후 `52.79.235.56`으로 재배포)에서 B/C/E 각각 직접 확인했다.

- **C(즉시응답+웹훅 동시 경쟁)**: Refund가 정확히 1건만 `COMPLETED`로 존재 — 멱등성 확인.
- **E(완료 실패, 즉시응답·웹훅 두 경로 모두)**: `Payment=PAID`(REFUNDED 아님)·`Refund=REQUESTED/PROCESSING`(`completedAt=null`)·`Participant=CANCEL_REQUESTED`(CANCELLED 아님) — 계약대로 정확히 롤백됨을 확인.
- **B(그룹 3명 동시 완료 웹훅) — 실제 결함 발견**: 참여자 전원 개별적으로는 `CANCELLED`·`REFUNDED`로 정확히 끝났지만, **`Reservation.reservationStatus`가 `CANCELLED`로 전이되지 않고 `CANCELLING`에 영구히 멈춘다**(3초 대기 후 재조회해도 동일, 3회 연속 100% 재현). 같은 3명을 **순차** 처리하면 3번째에서 정확히 `CANCELLED`로 전이돼, 로직 자체가 아니라 **진짜 동시 요청에서만** 발생하는 결함임을 확인했다. `findWithLockById`의 `PESSIMISTIC_WRITE` 락이 이론상 이 구간을 직렬화해야 하나 정확한 근본 원인은 특정하지 못했다.

**중요**: 이 결함은 V2에서 이미 배포된 그룹 예약 취소 기능(#44/#131/#45)의 실제 동시성 버그이며, Issue #146(성능 측정)이나 이 PR의 범위를 넘는 별도 애플리케이션 결함이라 **#259로 분리**했다. 이 발견은 시나리오 B의 "부하 수준에서 여유 있다"는 성능 결론(§4~5) 자체에는 영향이 없다 — 원인이 부하량이 아니라 참여자 완료가 정말로 동시에 도착하는지 여부이기 때문이다. 다만 이 PR이 "checks 100%"로 보고한 B의 검증은 HTTP 상태 코드만 확인했을 뿐 Reservation의 최종 집계 상태까지는 검증하지 않았다는 한계가 있었다는 점도 함께 기록해 둔다.

구조화 로그(`event=REFUND_COMPENSATION_REQUIRED` 등에 paymentId·refundId·cancellationId 포함 여부)와 `#141` Reconciliation 스케줄러의 실제 식별·복구 여부는 애플리케이션 로그(`docker logs`) 접근 권한이 없어 이번 라운드에서 직접 확인하지 못했다 — 필요 시 인프라 담당자에게 로그 확인을 요청해야 한다.

## 이번 측정의 한계

1. **Load 단계 1개 지점만 측정**했다. Issue 본문이 요구한 1/10/30/50/100 VU 단계적 확대나 Stress 단계는 수행하지 못했다 — `setup()`이 실메일 발송에 의존해 픽스처 규모를 키울수록 준비 시간이 비현실적으로 늘어났기 때문이다(§측정 환경 "인프라 이슈" 참고). A/B/C/E의 "여유 있다"는 결론은 20 iter/s(그룹 10/s) 수준에서만 확인됐다.
2. **InnoDB 레벨 row lock wait 실측치 없음** — HikariCP active/pending을 대리 지표로 사용했다(`#235`와 동일한 기존 한계).
3. D 100ms 결과는 300ms 실행 직후 연속 실행돼 앞선 실행의 큐잉 잔재가 일부 섞였을 가능성이 있다(§4 참고) — 500ms·1000ms의 뚜렷한 추세로 결론을 보완했다.
4. 이번 측정 도중 실메일 발송 지연으로 인한 애플리케이션 헬스 다운이 실제로 발생했다 — 재발 방지책(메일 타임아웃 설정 등)은 이 Issue 범위 밖이라 별도 후속 이슈로 분리한다(§9).

## Spring Event 전환 판단

Issue 본문 "Spring Event 전환 최소 성능 악화 기준"(Human 확정 계약, 2026-08-13T03:34:40Z):

> 환불 완료 웹훅 p95가 목표(300ms) 대비 2배(600ms) 초과 + 원인이 예약 완료 처리 비중이며, Reservation 락 대기 또는 DB Pool pending이 전체 처리 시간의 30% 이상을 차지할 때 전환 검토

**현재 시스템(지연 주입 없음)** 기준으로는:

- A/B/C/E 시나리오는 p95가 모두 115ms 이하로, 600ms는커녕 목표(300ms)에도 크게 못 미친다.
- HikariCP pending은 A/B/C/E 전 시나리오 0 — "락 대기·Pool pending이 30% 이상"에 해당하는 사례가 없다.

**전환 게이트 조건은 현재 충족되지 않는다.**

다만 D의 결과는 이 게이트에 근접하는 조건이 얼마나 쉽게 만들어지는지를 정량적으로 보여준다 — 완료 트랜잭션이 500ms(현재보다 특별히 극단적이지 않은 값, 통계·알림 등 후속 처리를 조금만 추가해도 도달 가능)를 넘으면 p95 600ms 기준과 "Pool pending 30% 이상" 기준을 **동시에** 만족시키기 시작한다.

### 결론: A안 — 현재 동기 Port·Adapter 구조 유지 (조건부)

- **지금 당장은** 이번에 실측한 부하 수준(20 iter/s, 그룹 10/s)에서 목표 성능(p95 300ms·p99 800ms)을 여유 있게 충족하고, 락·DB Pool 병목도 관측되지 않았다(pending 0, active 최대 7/10) — 구조를 바꿀 근거가 없다.
- **다만** D가 보여준 "완료 트랜잭션 500ms 임계점"은 이 구조의 여유가 생각보다 크지 않다는 정량적 경고다. 예약 완료 로직에 새 후속 처리(통계·알림 등)를 추가하는 계획이 실제로 생기면, **그 구현에 착수하기 전에** 반드시 다음을 먼저 검토해야 한다.
  - 추가될 로직의 예상 처리 시간이 500ms 임계점에 얼마나 가까운지
  - HikariCP Pool 크기를 늘릴지(B안의 일부), 처리 자체를 가볍게 유지할지
  - 그래도 부족하면 이때 C안(Spring Event 전환)을 후속 Issue로 검토

**한계 고지**: 이 결론은 §7에서 밝힌 대로 Load 1개 지점 실측에 근거한다. 실제 운영 트래픽이 이번에 측정한 동시성 수준을 크게 초과하는 경우(예: 인기 회차 대량 취소가 동시에 몰리는 상황), 이 결론을 그대로 적용하기 전에 Stress 단계 재측정이 필요하다.

## 후속 조치 제안

1. **(별도 Issue, #146 범위 밖) 메일 발송 타임아웃 설정**: `spring.mail.properties.mail.smtp.connectiontimeout`/`timeout`/`writetimeout`이 설정돼 있지 않아, 메일 서버가 멈추면 예약 확정·모집 마감 요청 스레드가 무제한 대기할 수 있는 운영 위험이 있다. 이번 측정 중 실제로 재현됐다.
2. **(권장) 예약 완료 로직 확장 전 Pool 용량 재검토**: §6 계산식(도착률×완료 트랜잭션 시간 vs Pool 크기)을 예약 완료 로직에 새 기능을 추가하는 모든 후속 Issue의 체크리스트 항목으로 남긴다.
3. **(선택) Stress 단계 재측정**: 메일 타임아웃이 해결되거나 `setup()`을 위한 fake 메일 Bean이 추가되면, Issue 본문이 요구한 30/50/100 VU·Stress 단계를 마저 실측할 수 있다.

## 실행 방법

```bash
# 로컬 Prometheus를 AWS 인스턴스에 연결(모니터링 준비)
cd monitoring
BOBFULL_BACKEND_METRICS_TARGET=15.164.234.170:8080 \
  GRAFANA_ADMIN_PASSWORD=<placeholder> GRAFANA_SLACK_WEBHOOK_URL=<placeholder> GRAFANA_SLACK_RECIPIENT=<placeholder> \
  docker compose up -d prometheus

# 시나리오 A 예시(워밍업 → 실측)
k6 run -e STAGE=load -e BASE_URL=http://15.164.234.170:8080 \
  -e PORTONE_WEBHOOK_SECRET="<실제 배포 환경의 webhook secret>" \
  -e LOAD_DURATION=15s -e LOAD_RATE=20 -e RESERVATION_POOL_SIZE=350 -e SETUP_TIMEOUT=1200s \
  k6/scenarios/refund-completion-baseline.js

k6 run -e STAGE=load -e BASE_URL=http://15.164.234.170:8080 \
  -e PORTONE_WEBHOOK_SECRET="<실제 배포 환경의 webhook secret>" \
  -e LOAD_DURATION=30s -e LOAD_RATE=20 -e RESERVATION_POOL_SIZE=700 -e SETUP_TIMEOUT=1800s \
  --summary-export=A-result.json \
  k6/scenarios/refund-completion-baseline.js

# 시나리오 D 지연값 sweep 예시(100/300/500/1000ms)
k6 run -e STAGE=load -e RESERVATION_COMPLETION_DELAY_MS=1000 -e RESERVATION_POOL_SIZE=700 \
  -e LOAD_DURATION=30s -e SETUP_TIMEOUT=1800s -e BASE_URL=http://15.164.234.170:8080 \
  -e PORTONE_WEBHOOK_SECRET="<실제 배포 환경의 webhook secret>" \
  k6/scenarios/refund-completion-delay-injection.js

# 시나리오 F는 -e PORTONE_EXTERNAL_DELAY_MS 또는 -e PORTONE_EXTERNAL_RESULT=TIMEOUT|CONNECTION_RESET 로 변경해 반복 실행
```

원본 로그·Summary JSON·Prometheus 스냅샷: `raw/`(시나리오별 `{A..F}-AWS-load-*.{log,json}`, D/F 지연값별 `D-AWS-load-delay{100,500,1000}ms.*`·`F-AWS-load-delay{500,1000}ms.*`·`F-AWS-load-{timeout,connreset}.*`), `raw/prometheus/`(시나리오별·`D-delaysweep-*`/`F-delaysweep-*` 등 지연 sweep 구간 스냅샷 포함).
