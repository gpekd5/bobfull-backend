// Issue #146 시나리오 F — PortOne 외부 API 지연 분리 측정.
//
// 목적은 외부 API 지연과 내부 완료 경로 병목을 혼동하지 않는 것이다. 이 스크립트는 새 코드
// 없이 이미 시나리오 A/C/D/E에서 쓴 `PerformanceTestRefundRequester`(성능 프로파일 전용
// fake Bean)의 지연·강제 결과 제어(`X-Perf-Refund-Delay-Ms`/`X-Perf-Refund-Result`)를 그대로
// 재사용한다 — PortOne 요청은 원래 `ReservationCancellationRefundAdapter.request()`에서
// Reservation 락·내부 완료 트랜잭션 밖(두 REQUIRES_NEW 트랜잭션 사이의 평범한 메서드 호출)에서
// 실행되므로, 이 지연은 정확히 "외부 API만 느려진 상황"을 재현한다.
//
// 지연 조건(Issue 본문 예시)은 부하를 고정한 채(Load 20 iter/s) `PORTONE_EXTERNAL_DELAY_MS`
// 또는 `PORTONE_EXTERNAL_RESULT`만 바꿔가며 같은 스크립트를 여러 번 실행해 비교한다:
//   - PORTONE_EXTERNAL_DELAY_MS: 0(기준)·500·1000·3000
//   - PORTONE_EXTERNAL_RESULT: SUCCESS(기본, 지연만 적용) | TIMEOUT | CONNECTION_RESET
//     TIMEOUT/CONNECTION_RESET일 때는 `ReservationCancellationRefundAdapter.requestFromPortOne()`이
//     `event=REFUND_RESULT_UNKNOWN`을 로그로 남기고 Refund는 REQUESTED로 유지된 채(자동 재환불
//     없음) `PORTONE_REFUND_FAILED`(HTTP 502)로 응답한다 — Issue 본문 다이어그램("Refund
//     REQUESTED 유지 → Payment PAID 유지 → 자동 재환불 없음")과 동일한 코드 경로다.
//
// "PortOne 요청이 실제로 Reservation 락 밖에서 실행되는지"(다른 참여자의 완료 처리를 막지
// 않는지)는 k6 응답만으로는 확인할 수 없다 — 실제 측정 시점에 지연이 큰 구간(3s)에서
// Prometheus `hikaricp_connections_active`나 다른 참여자 요청의 지연이 함께 늘지 않는지
// 직접 대조해서 확인한다.
//
// 실행 예:
//   k6 run -e STAGE=smoke -e PORTONE_EXTERNAL_DELAY_MS=3000 ops/load-test/scenarios/refund-completion-external-delay.js
//   k6 run -e STAGE=smoke -e PORTONE_EXTERNAL_RESULT=TIMEOUT ops/load-test/scenarios/refund-completion-external-delay.js

import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import { check } from 'k6';
import { post } from '../common/helpers.js';
import { STAGE } from '../common/config.js';
import { buildCreateTargetPool } from '../common/fixture.js';
import { createMemberPool, prepareAndPayReservation } from '../common/refundFixture.js';

const PORTONE_EXTERNAL_DELAY_MS = Number(__ENV.PORTONE_EXTERNAL_DELAY_MS || 0);
const PORTONE_EXTERNAL_RESULT = __ENV.PORTONE_EXTERNAL_RESULT || 'SUCCESS';
const EXPECT_FAILURE = PORTONE_EXTERNAL_RESULT === 'TIMEOUT' || PORTONE_EXTERNAL_RESULT === 'CONNECTION_RESET';

const STAGE_OPTIONS = {
    smoke: {
        executor: 'per-vu-iterations',
        vus: 2,
        iterations: 4,
        maxDuration: '30s',
    },
    load: {
        executor: 'constant-arrival-rate',
        rate: Number(__ENV.LOAD_RATE || 20),
        timeUnit: '1s',
        duration: __ENV.LOAD_DURATION || '5m',
        preAllocatedVUs: Number(__ENV.LOAD_PRE_ALLOCATED_VUS || 40),
        maxVUs: Number(__ENV.LOAD_MAX_VUS || 100),
    },
};

export const options = {
    scenarios: { refund_external_delay: STAGE_OPTIONS[STAGE] },
    setupTimeout: __ENV.SETUP_TIMEOUT || '180s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export const externalDelayDuration = new Trend('refund_external_delay_duration');

const RESERVATION_POOL_SIZE = Number(__ENV.RESERVATION_POOL_SIZE || { smoke: 10, load: 8000 }[STAGE]);
const MEMBER_POOL_SIZE = Number(__ENV.MEMBER_POOL_SIZE || 20);
const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

export function setup() {
    // buildCreateTargetPool은 하루 단위로 세션을 채우기 때문에 요청한 poolSize보다 훨씬 많은
    // sessionId를 돌려줄 수 있다(#63 Fixture 구현 특성). 그대로 다 쓰면 필요 이상의 예약을
    // 만들며 setup() 시간을 낭비하므로, 실제 필요한 개수만큼만 자른다.
    const { sessionIds: allSessionIds } = buildCreateTargetPool('refund-f', RESERVATION_POOL_SIZE, BASE_DATE);
    const sessionIds = allSessionIds.slice(0, RESERVATION_POOL_SIZE);
    const memberTokens = createMemberPool('refund-f', MEMBER_POOL_SIZE);

    const reservations = sessionIds.map((sessionId, i) =>
        prepareAndPayReservation('refund_external_delay', memberTokens[i % memberTokens.length], sessionId));

    return { reservations };
}

export default function (data) {
    const globalIteration = exec.scenario.iterationInTest;
    if (globalIteration >= data.reservations.length) {
        throw new Error(
            `예약 Fixture 풀 소진: iteration=${globalIteration} poolSize=${data.reservations.length} — RESERVATION_POOL_SIZE를 늘려 재실행하라.`
        );
    }
    const reservation = data.reservations[globalIteration];
    const headers = Object.assign(
        { Authorization: `Bearer ${reservation.memberToken}` },
        PORTONE_EXTERNAL_DELAY_MS > 0 ? { 'X-Perf-Refund-Delay-Ms': String(PORTONE_EXTERNAL_DELAY_MS) } : {},
        PORTONE_EXTERNAL_RESULT !== 'SUCCESS' ? { 'X-Perf-Refund-Result': PORTONE_EXTERNAL_RESULT } : {},
    );

    const start = Date.now();
    const res = post('refund_external_delay',
        `/api/reservations/${reservation.reservationId}/participations/me/cancel`,
        { reason: `성능 측정(시나리오 F, PortOne 지연 ${PORTONE_EXTERNAL_DELAY_MS}ms·결과 ${PORTONE_EXTERNAL_RESULT})` },
        headers,
        'refund_cancel_external_delay');
    externalDelayDuration.add(Date.now() - start);

    if (EXPECT_FAILURE) {
        check(res, { 'refund_cancel_external_delay status is 502(PORTONE_REFUND_FAILED)': (r) => r.status === 502 });
    } else {
        check(res, {
            'refund_cancel_external_delay status is 200': (r) => r.status === 200,
            'refund_cancel_external_delay body.success is true': (r) => {
                try {
                    return JSON.parse(r.body).success === true;
                } catch (e) {
                    return false;
                }
            },
        });
    }
}
