# Issue #65 — 정산 조회 계산 비용 측정 및 Batch·Snapshot 필요성 판단

## 기준

- 기준 Branch: `perf/65-settlement-query`
- 측정 Commit SHA: `2643fea`(로컬 SQL Query Count·Scale 실측), 이후 AWS 재배포 후 동일 커밋으로 실측
- 측정일: 2026-08-14
- Human 결정 계약: Issue #65 댓글(AI 제안, Human 채팅 승인) — Q1(A안: 성능 검증 우선), Q3(A안: 단순 Scheduler) 확정. Q2(Snapshot 도입이 성능과 무관하게 실제 업무 요구인지)는 이번 측정 결과 게이트에 도달하지 않아 보류.

## 요약

**현재 Query-time 계산 구조에서 실제 인덱스 3건 부재를 발견·수정한 뒤, 정산 조회 3개 API 전부 Load 수준 부하에서 목표를 충족했다.** Batch/Snapshot 도입 없이 Query/Index 개선만으로 문제를 해결했다.

- **시나리오 A(기준선)**: `getReservationSettlements`·`getExpectedSettlement` 모두 SQL 실행 횟수가 예약 건수와 무관하게 고정(각 6회·2회)임을 확인 — N+1 아님.
- **시나리오 B(데이터 규모 증가)**: SQL 횟수는 고정이어도 **개별 쿼리 실행 시간**이 system-wide 데이터 규모에 따라 악화되는 인덱스 부재 2건을 발견·수정했다(`payment.reservation_id`, `reservation.time_slot_id`).
- **시나리오 C(반복 조회)**: 실제 AWS 인스턴스에서 정산 총액 조회(`getExpectedSettlement`)를 반복 호출하자 **HikariCP Pool이 완전히 포화**(p95 6.5초~7.51초)되는 심각한 문제를 발견했다. 원인은 세 번째 인덱스 부재(`payment.time_slot_id`)였다. 수정 후 재배포·재측정한 결과 **p95가 6.5초→30ms(약 200배)로 개선**되고 Pool 포화가 완전히 사라졌다.

**결론(§6): B안 — 동기 구조 내부 최적화(Query/Index 개선) 후 유지.** Batch/Snapshot(C안)은 이번 측정 범위에서 근거가 없다.

## 현재 구조 확인 (실제 정산 계산 흐름)

`SettlementQueryService`(3개 API) 기준으로 실제 코드를 확인했다.

| 항목 | 내용 |
|---|---|
| 참조 Entity/Repository | `Restaurant`(소유권 검증), `TimeSlot`, `SharedTable`, `Reservation`, `Payment`, `Refund` |
| Payment 상태 조건 | `paidAt IS NOT NULL`만 확인 — `Payment.status` Enum 자체는 조건에 쓰지 않는다 |
| Refund 상태 조건 | `RefundStatus.COMPLETED`인 건만 차감 합산(`REQUESTED`/`FAILED`는 차감하지 않음 — 확정 전 금액을 미리 차감하지 않는 정합성 계약과 일치) |
| Reservation/Participant/NO_SHOW 영향 여부 | **영향 없음** — `findSettlementReservations`는 `Reservation.reservationStatus`나 NO_SHOW 여부를 조건으로 쓰지 않는다. 정산 금액은 오직 `Payment.paidAt`·`Refund.status`로만 계산되며, 취소·노쇼된 예약이라도 결제 이력이 남아 있으면 그대로 노출된다(기존 동작, 이번 Issue 범위에서 변경하지 않음) |
| 조회 API·기간 필터 | `startAt >= startDate` / `startAt < endDate+1일`(`TimeSlot.startAt` 기준, `Asia/Seoul`) — 필터 없으면 전체 기간 |
| 요청당 실제 SQL 수 | `getExpectedSettlement`=2(소유권 검증 1 + 집계 1), `getReservationSettlements`=6(소유권 검증 1 + Reservation 페이지 content/count 2 + TimeSlot 배치 1 + Payment/Refund 배치 2), `getReservationSettlement`(단건)=5(Reservation/TimeSlot/SharedTable 각 findById + Payment/Refund 각 1) |
| 집계 Query 존재 여부 | `getExpectedSettlement`는 DB 레벨 `SUM`/`CASE WHEN` 집계 1건. `getReservationSettlements`는 DB 집계 없이 Payment/Refund를 배치 조회한 뒤 애플리케이션 메모리에서 예약별로 합산 |
| N+1/반복 조회 여부 | 목록·집계 API 모두 배치 IN 쿼리 사용 — N+1 아님(시나리오 A에서 재확인). 단건 상세 API(`getReservationSettlement`)는 예약 1건 기준이라 배치가 필요 없다 |
| 이번 Issue 발견 전 Index | `payment.payment_status/expires_at/payment_id` 복합 인덱스만 존재 — `payment.reservation_id`, `payment.time_slot_id`, `reservation.time_slot_id`에는 인덱스 없음(§ DB 인덱스 변경 요약 참고) |

## 측정 환경

- 애플리케이션 인스턴스: `bobfull-k6-test-app` EC2 1대, t3.small(2 vCPU) — `#142`/`#146`/`#235`와 동일 인스턴스, `SPRING_PROFILES_ACTIVE=prod,performance`
- MySQL: 로컬 SQL Query Count·Scale 실측은 로컬 `bobfull-mysql`(Docker, MySQL 8.4) 별도 throwaway 스키마 사용. AWS Load 실측(시나리오 C)은 실제 배포 환경 MySQL(해당 인스턴스에 이번 세션 이전 #146 K6 측정으로 누적된 실제 데이터, Payment 13,861건/Refund 10,295건/Reservation 12,649건/TimeSlot 26,505건 — 인위적 noise가 아닌 실제 축적 규모)
- DB Connection Pool: HikariCP `maximum-pool-size=10`(변경 없음)
- 스키마 관리: Flyway/Liquibase 없이 Hibernate `ddl-auto=update` — 인덱스 추가는 재배포 시 자동 반영됨(별도 마이그레이션 스크립트 불필요)
- 모니터링: 로컬 Prometheus가 AWS 인스턴스 `/actuator/prometheus`를 직접 스크래핑(`#146`/`#235`와 동일 방식)

## 시나리오 A — 현재 Query-time 계산 기준선

`SettlementQueryCountInvestigationTest`(Hibernate Statistics 기반, `#61`/`#235`와 동일 관례)로 실측.

| 메서드 | 예약 건수 | SQL 실행 횟수 | 구성 |
|---|---:|---:|---|
| `getReservationSettlements` | 20 | 6 | 소유권 검증(1) + 페이지 content/count(2) + TimeSlot/Payment/Refund 배치 조회(3) |
| `getExpectedSettlement` | 50 | 2 | 소유권 검증(1) + 단일 집계 쿼리(1) |

**판정**: 둘 다 예약 건수가 늘어도 SQL 횟수가 고정 — 기존 코드는 이미 N+1이 아니라 배치/집계 쿼리로 구성돼 있었다.

## 시나리오 B — 데이터 규모 증가 (인덱스 부재 2건 발견·수정)

SQL 횟수는 고정이었지만, 실제 실행 시간이 system-wide 데이터 규모에 비례해 나빠지는지 EXPLAIN·지연 실측으로 확인했다(`SettlementQueryScaleInvestigationTest`, `SettlementReservationQueryScaleInvestigationTest`, 로컬 MySQL, noise 4만~10만 건).

| 대상 | Before EXPLAIN | Before 지연(noise 전→후) | 원인 | After EXPLAIN | After 지연 |
|---|---|---:|---|---|---:|
| `PaymentRepository.findAllByReservationIdInAndPaidAtIsNotNull` | `type=ALL, rows≈99535` | 15.2ms→40.8ms(2.68배) | `payment.reservation_id` 인덱스 없음 | `type=range, key=idx_payment_reservation_id, rows=40` | 지연 증가 없음 |
| `RefundRepository.findAllByPayment_ReservationIdIn` | `type=ALL`(refund 구동) | (위 지표에 포함) | 동일 컬럼(조인) | `type=range` | 동일 |
| `ReservationRepository.findSettlementReservations`(reservation 부분) | `type=ALL, rows≈40146` | 14.0ms→26.4ms(1.89배) | `reservation.time_slot_id` 인덱스 없음 | `type=ref, key=idx_reservation_time_slot_id, rows=1` | 지연 증가 없음 |

**수정**: `Payment.java`에 `idx_payment_reservation_id` 추가(커밋 `c3943d3`), `Reservation.java`에 `idx_reservation_time_slot_id` 추가(커밋 `df881ef`).

## 시나리오 C — 반복 조회 (핵심 발견 — 세 번째 인덱스 부재)

`k6/scenarios/settlement-repeated-query.js`로 실제 AWS 인스턴스에서 정산 조회를 반복 호출했다.

### 최초 발견 (수정 전, 2개 엔드포인트 결합, Load 20 iter/s·2분)

| 지표 | 값 |
|---|---:|
| p95 | 6.5s |
| p99 | 9.12s |
| dropped_iterations | 1365 / 2400 |
| HikariCP active | 10/10(포화) |
| HikariCP pending 최대 | 92 |
| CPU 최대 | 30%(연산 병목 아님) |

두 엔드포인트를 분리 진단한 결과, **`getExpectedSettlement` 단독 20 iter/s만으로도** median 6.63s / p95 7.51s / p99 7.67s가 나와 이 엔드포인트가 원인임을 확인했다. 코드 확인 결과 `sumSettlementAmounts`(payment ⋈ time_slot ⋈ shared_table)가 조인하는 `payment.time_slot_id`에 인덱스가 없었다 — Before/After 2건과 동일한 패턴의 세 번째 인덱스 부재였다.

`SettlementAggregateQueryScaleInvestigationTest`(로컬, noise 13,860건 — 실제 AWS 누적 규모와 동일하게 재현)로 확인: 수정 전 `type=ALL, rows≈13996`(전체 스캔), 수정 후 `type=ref, key=idx_payment_time_slot_id, rows=1`.

**수정**: `Payment.java`에 `idx_payment_time_slot_id` 추가(커밋 `2643fea`).

### 재배포 후 AWS 재측정 (동일 조건)

| 지표 | 수정 전 | 수정 후 | 개선 |
|---|---:|---:|---:|
| `getExpectedSettlement` 단독 median | 6.63s | 13.78ms | 약 481배 |
| `getExpectedSettlement` 단독 p95 | 7.51s | 91.02ms | 약 82배 |
| 결합(2개 엔드포인트) p95 | 6.5s | 30.32ms | 약 214배 |
| 결합 p99 | 9.12s | 91.69ms | 약 99배 |
| dropped_iterations | 1365 | 0 | 완전 해소 |
| HikariCP active 최대 | 10/10 | 1 | 완전 해소 |
| HikariCP pending 최대 | 92 | 0 | 완전 해소 |
| checks_succeeded | 100% | 100% | 기능은 항상 정상(지연만 문제였음) |

**판정**: 세 번째 인덱스 추가로 Pool 포화·심각한 지연이 완전히 사라졌다. 실제 배포 환경에서 재현·수정·재검증까지 전부 확인했다.

## 결과 기록 표

`getExpectedSettlement`(반복 조회 대상, AWS 실측 데이터 규모 Payment 13,861건 기준) 기준.

| 방식 | 데이터 규모 | Query 수 | p95 | p99 | DB pending(최대) | 반복 계산 비용 | 정합성 | 운영 복잡성 | 판정 |
|---|---:|---:|---:|---:|---:|---|---|---|---|
| 현재 조회 계산(인덱스 개선 전) | Payment 13,861건 | 2 | 7.51s(단독)/6.5s(결합) | 7.67s(단독)/9.12s(결합) | 92 | 매우 높음 — 반복 시 Pool 포화 | 유지(변경 없음) | 낮음 | 부적합 |
| Query/Index 개선(3건 추가) | Payment 13,861건 | 2(동일) | 91.02ms(단독)/30.32ms(결합) | 111.83ms(단독)/91.69ms(결합) | 0 | 낮음 — 반복 조회가 더 이상 비용이 아님 | 유지(변경 없음, 원본 상태가 곧 진실) | 낮음(인덱스만 추가) | **채택** |
| Snapshot 후보 | 미도입(도입 게이트 미충족으로 구현·측정하지 않음) | — | — | — | — | — | — | 중간~높음(동기화·재계산·확정 정책 필요) | 미도입 |

## DB 인덱스 변경 요약

| 테이블.컬럼 | 인덱스명 | 영향받는 쿼리 | 커밋 |
|---|---|---|---|
| `payment.reservation_id` | `idx_payment_reservation_id` | `PaymentRepository.findAllByReservationIdInAndPaidAtIsNotNull`, `RefundRepository.findAllByPayment_ReservationIdIn`(조인 경유) | `c3943d3` |
| `reservation.time_slot_id` | `idx_reservation_time_slot_id` | `ReservationRepository.findSettlementReservations` | `df881ef` |
| `payment.time_slot_id` | `idx_payment_time_slot_id` | `PaymentRepository.sumSettlementAmounts` | `2643fea` |

Flyway 없이 `ddl-auto=update`로 스키마를 관리하는 프로젝트라 별도 마이그레이션 스크립트 없이 재배포 시 자동 반영된다.

## Spring Event/Batch/Snapshot 판단

Issue #65 "Batch/Snapshot 도입 검토 조건"(본문):

> Query/Index 개선 후에도 정산 조회가 주요 DB 병목으로 남음 / 동일 정산 결과를 매우 자주 반복 계산함 / 정산 결과를 특정 시점 기준으로 확정·감사 가능하게 보존해야 함 / 외부 지급·송금 연계 전에 확정된 계산 결과가 필요함

- **"Query/Index 개선 후에도 병목으로 남음"**: 해당 없음 — 개선 후 p95 30ms, HikariCP 포화 없음.
- **"반복 계산 자체가 비용"**: 인덱스 수정 전에는 그랬지만(포화), 수정 후에는 반복 조회 자체가 더 이상 비용이 아니다.
- **"확정·보존"·"외부 지급 연계"(Q2)**: 이번 라운드에서 성능 게이트에 도달하지 않아 실제 업무 요구 여부를 다시 확인할 필요가 없어졌다. 향후 실제 PG 정산 자동화나 확정 시점 보존이 업무적으로 필요해지면 그때 별도로 Q2를 재확인한다.

**Batch/Snapshot 도입 게이트가 하나도 충족되지 않는다.**

### 결론: B안 — 동기 구조 내부 최적화(Query/Index 개선) 후 유지

- 1차 개선 순서("불필요한 반복 Query 제거 → 집계 Query/Projection → 실행 계획 → 필요한 Index")를 따라 인덱스 3건을 추가한 것만으로 반복 조회 시나리오의 심각한 병목이 완전히 해소됐다.
- A안(현재 구조 그대로 유지)이 아니라 B안인 이유: 실제로 코드/스키마 변경(인덱스 추가)이 있었다 — "아무것도 안 바꿔도 충분했다"가 아니라 "최적화가 필요했고, 그것으로 충분했다."
- C안(Batch/Snapshot)은 근거가 없다 — Issue 본문이 명시한 "단순히 배치 기술을 사용해보기 위해 도입하지 않는다" 원칙과 일치한다.

## 이번 측정의 한계

- 시나리오 B/C 모두 데이터 규모(noise) 실측은 로컬 MySQL 기준이며, 세 번째 발견(시나리오 C의 Pool 포화)만 실제 AWS 배포 환경에서 재현·수정·재검증까지 마쳤다. 첫 두 인덱스(A/B)는 로컬 EXPLAIN 근거로 충분하다고 판단해 AWS Load 재측정까지는 하지 않았다 — 필요시 후속으로 추가 가능하다.
- Stress 단계(더 높은 동시성)는 이번 라운드에서 수행하지 않았다. Load(20 iter/s) 수준에서 이미 목표를 크게 상회해(p95 30ms) 이번 범위에서는 필요하지 않다고 판단했다.
- `getReservationSettlement`(단건 상세 조회, `/api/owner/settlements/reservations/{reservationId}`)는 이번 측정 대상에 포함하지 않았다 — ID 기반 단건 조회라 목록/총액 조회와 같은 종류의 병목 위험이 낮다고 판단했다(PK 기반 조회만 사용).

## 실행 방법

```bash
# 시나리오 A: SQL Query Count(로컬 MySQL, BOBFULL_MYSQL_PERF_TEST=true)
BOBFULL_MYSQL_PERF_TEST=true BOBFULL_TEST_MYSQL_URL=... BOBFULL_TEST_MYSQL_USERNAME=... BOBFULL_TEST_MYSQL_PASSWORD=... \
  ./gradlew :test --tests "com.bobfull.payment.service.SettlementQueryCountInvestigationTest"

# 시나리오 B: 데이터 규모별 EXPLAIN·지연(로컬 MySQL)
./gradlew :test --tests "com.bobfull.payment.service.SettlementQueryScaleInvestigationTest"
./gradlew :test --tests "com.bobfull.reservation.repository.SettlementReservationQueryScaleInvestigationTest"
./gradlew :test --tests "com.bobfull.payment.repository.SettlementAggregateQueryScaleInvestigationTest"

# 시나리오 C: 반복 조회(AWS 실배포)
k6 run -e STAGE=load -e BASE_URL=http://<test-instance>:8080 \
  -e LOAD_DURATION=2m -e LOAD_RATE=20 -e RESERVATION_COUNT=50 -e SETUP_TIMEOUT=600s \
  k6/scenarios/settlement-repeated-query.js

# 엔드포인트 분리 진단
k6 run -e STAGE=load -e ENDPOINT=expected k6/scenarios/settlement-repeated-query.js
k6 run -e STAGE=load -e ENDPOINT=reservations k6/scenarios/settlement-repeated-query.js
```
