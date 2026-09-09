# #63 K6 Fixture Seed·Cleanup 계약

Issue #63 "테스트 데이터 계약"에 따라, 시나리오별로 필요한 Fixture와 소비/정리 방식을 정리한다.

## 공통 원칙

- 실제 운영 데이터는 절대 쓰지 않는다. 모든 계정·식당·회차는 이번 실행의 `RUN_ID`를 이메일/이름에 포함해 만든 합성 데이터다.
- PortOne/SMTP 등 외부 서비스는 호출하지 않는다(회원가입·로그인·식당/회차 등록·조회·예약 준비까지만 사용하며, 결제 확정 단계는 이 Harness 범위 밖이다).
- 각 시나리오는 `setup()`에서 필요한 Fixture를 API로 직접 만든다 — 별도 SQL 스크립트나 수동 시딩에 의존하지 않아 재현 가능하다.

## 시나리오 A. 식당 검색 (`restaurant-search.js`)

- 별도 Fixture 불필요. 기존 DB에 있는 식당 데이터로 검색 부하를 건다.
- 검색 결과가 0건이어도(빈 DB) API 자체는 200을 반환하므로 Smoke 검증에는 영향이 없지만, Load/Stress에서 캐시(#62)·검색 SQL(#61) 효과를 보려면 사전에 식당 데이터가 어느 정도(수십~수백 건) 있어야 한다. 실행 전 대상 환경에 그 정도 데이터가 있는지 확인한다.

## 시나리오 B. 회차/가용 조회 (`dining-session-availability.js`)

- `setup()`에서 Owner 1명 + Restaurant 1개 + SharedTable 1개(capacity 8) + `BASE_DATE`(기본: 실행일+1일) 하루치 회차(00:00~23:30, 30분 간격)를 만든다.
- 조회만 반복하므로 상태 소비가 없다 — 같은 회차를 몇 번 조회해도 결과가 달라지지 않는다.
- Cleanup: Owner/Restaurant/SharedTable/DiningSession을 삭제하는 API가 있으면 실행 후 정리한다(`DELETE /api/owner/restaurants/{id}`, `DELETE /api/owner/dining-sessions/{id}` 등). 별도 테스트 스택이라면 스택 자체를 폐기해도 된다.

## 시나리오 C. 예약 준비 CREATE (`reservation-prepare.js`)

- **핵심 위험**: CREATE는 TimeSlot(DiningSession)당 단 하나만 성공한다. 같은 회차로 반복 호출하면 두 번째부터 `409 ACTIVE_RESERVATION_ALREADY_EXISTS`(`ReservationPreparationService#validateNoActiveCreate`)가 나서, 실제 부하 대신 오류 응답만 반복 측정하게 된다.
- 그래서 `setup()`에서:
  - `SESSION_POOL_SIZE`(기본: smoke=10, load/stress=500, `-e SESSION_POOL_SIZE=`로 조정) 이상의 미사용 회차를 여러 날짜에 걸쳐 만든다.
  - `MEMBER_POOL_SIZE`(기본 20)명의 회원 계정을 만들어 로그인해둔다. CREATE의 중복 방지 제약은 회차 단위이지 회원 단위가 아니므로(코드 확인: `validateNoActiveCreate(timeSlotId)`), 회원 계정은 재사용해도 된다.
  - 각 반복은 `exec.scenario.iterationInTest`(전역 반복 번호)로 회차 풀에서 겹치지 않게 하나씩 소비한다.
- **주의(PR #208 리뷰로 발견)**: 처음 구현은 테이블 1개 × 최대 30일 × 30분 간격으로만 회차를 만들어 최대 1,440개까지밖에 확보하지 못했다. 예약 준비 API는 호출당 수십ms라 `constant-vus`/`ramping-vus`로 think-time 없이 돌리면 5분 안에도 수만 건이 소비될 수 있어, 풀이 Load duration을 버티지 못하고 중간에 소진돼버렸다. 지금은 두 가지로 고쳤다.
  - `buildCreateTargetPool()`이 필요한 만큼 **여러 테이블**에 나눠 회차를 만든다(`FIXTURE_INTERVAL_MINUTES` 기본 15분·`FIXTURE_MAX_DAYS` 기본 60일·`FIXTURE_MAX_TABLES` 기본 30개 — 기본값 기준 최대 약 17만 건까지 확보 가능). 요청한 `SESSION_POOL_SIZE`가 이 상한을 넘으면 API를 하나도 부르지 않고 즉시 실패한다.
  - `reservation-prepare.js`의 load/stress 단계에는 실제 사용자의 "생각하는 시간"에 해당하는 `THINK_TIME_SECONDS`(기본 1초) sleep을 넣어 소비 속도 자체를 낮췄다.
  - 그래도 `SESSION_POOL_SIZE`는 실제 계획한 VU×duration/(응답시간+think-time) 이상으로 넉넉히 잡아야 한다. 부족하면 스크립트가 예외로 즉시 실패한다(조용히 409만 쌓이는 것보다 실행 실패가 안전하다는 판단). #207에서 실제 실행 전에 이 값을 다시 계산해 조정한다.
- Cleanup: 이번 시나리오가 만든 Reservation/Payment(READY)는 시간이 지나면 만료 스케줄러가 정리하거나(`docs/010-product/project-context.md`의 READY 만료 정책 참고), 별도 테스트 스택이면 스택 자체를 폐기해도 된다. 운영 DB에서는 절대 실행하지 않는다.

## 실행 후 정합성 확인 (Issue #63 "정합성 검증")

시나리오 C 실행 후 다음을 확인한다.

- 같은 sessionId로 Reservation이 중복 생성되지 않았는지
- 생성된 Reservation/Payment 수가 실제 성공(200) 응답 수와 일치하는지
- DB에 없는 성공 응답(즉 200을 받았는데 실제로 Reservation/Payment가 없는 경우)이 없는지

## 시나리오 D(#142 A). 예약 페이지 조회 폭주 (`peak-restaurant-view.js`)

- `setup()`에서 Owner 1명 + Restaurant 1개 + SharedTable 1개 + `BASE_DATE` 하루치 회차를 만든다. 시나리오 B(`dining-session-availability.js`)와 구조는 같지만, 목적이 "여러 식당에 걸친 균등 부하"가 아니라 "단 하나의 인기 식당·날짜에 조회가 집중되는" 패턴이라 별도 시나리오로 분리했다.
- 조회만 반복하므로 상태 소비가 없다.

## 시나리오 E(#142 B). 예약 버튼 동시 클릭 — CREATE 경쟁 (`peak-reservation-create-race.js`)

- **범위 한계**: 결제 완료 전에는 `Reservation`이 생성되지 않으므로(`ReservationPreparationService` 클래스 Javadoc), JOIN 대상이 되려면 최초 참여자의 결제가 실제로 완료돼야 한다. 이 저장소엔 PortOne을 대신할 Fake 결제 확인 어댑터가 없어(실제 구현체는 `PortOneSdkPaymentReader` 하나) k6로 결제 완료를 자동화할 수 없고, Issue #142 "제외 범위"의 "PortOne 실서비스 반복 결제 요청"과도 충돌한다. 그래서 이 시나리오는 **CREATE 경쟁만** 다룬다 — JOIN 기반 좌석초과 테스트는 별도 Issue(Fake 결제 확인 어댑터 추가)가 필요하다.
- `setup()`에서 Owner 1명 + Restaurant 1개 + SharedTable 1개(capacity 4) + 경쟁 대상 회차 1개, 그리고 `CONCURRENT_USERS`명의 회원 계정 + 검증용 회원 1명을 만든다.
- `default()`에서 `CONCURRENT_USERS`명이 정확히 같은 sessionId로 동시에 CREATE를 시도한다(회차를 여러 개 나눠 쓰는 다른 시나리오와 반대로, 이 시나리오는 의도적으로 "같은 대상에 몰리는" 경쟁을 만든다).
- `teardown()`에서 검증용 회원으로 같은 회차에 CREATE를 한 번 더 시도해 409가 나는지로, 경쟁 종료 후에도 CREATE 배타 선점이 유지되는지 독립 검증한다. 최초 시도 때 GET 회차 조회의 `reservationId`로 검증하려 했으나, CREATE 성공(200)은 결제 완료 전이라 `reservationId`가 채워지지 않아 오탐이 났다(문서 `docs/110-records/evidence/v3/142-reservation-peak/README.md` "트러블슈팅" 참고).

## AWS 실행 시 추가 사항

Issue #207(AWS 실행 환경 준비)에서 실제 테스트 스택에 대해 실행할 때는 위 Fixture 규칙을 그대로 따르되, `BASE_URL`을 테스트 스택 엔드포인트로 지정하고 `RUN_ID`에 실행 날짜·Commit SHA를 포함해 Evidence와 연결한다.
