// Issue #146 시나리오 E — 예약 완료 반영 실패.
//
// PortOne 환불 완료 확인 후 예약 완료 처리(Reservation 락 구간)가 실패하면, 같은
// REQUIRES_NEW 트랜잭션에 있던 Refund COMPLETED·Payment REFUNDED까지 전부 롤백된다. 실패는
// `PerformanceTestReservationCompletionHook`(성능 프로파일 전용, `X-Perf-Reservation-
// Completion-Result: FAIL`)로 주입하며, 운영 코드에는 실패 로직이 전혀 없다.
//
// 두 경로 모두 실제로 존재하는 실패 처리 코드를 그대로 태운다 — 새로 만들지 않는다:
//   (짝수) 즉시 응답 경로: `ReservationCancellationRefundAdapter.request()`가 실패를 잡아
//          `event=REFUND_COMPENSATION_REQUIRED`(paymentId·refundId·cancellationId 포함)를
//          로그로 남기고 `REFUND_RECONCILIATION_REQUIRED`(HTTP 500)로 응답한다.
//   (홀수) 웹훅 경로: Refund를 PROCESSING 상태로 먼저 만든 뒤(즉시 응답에 `X-Perf-Refund-Result:
//          PROCESSING`) 뒤이은 `Transaction.Cancelled` 웹훅에서 실패를 주입한다.
//          `PortOneWebhookController`에는 이 실패를 잡는 전용 코드가 없어
//          `GlobalExceptionHandler`의 공용 `Exception` 핸들러가 받는다 —
//          `event=PORTONE_WEBHOOK_PROCESSING_FAILED`(paymentId·cancellationId 포함)를 남기고
//          `INTERNAL_SERVER_ERROR`(HTTP 500)로 응답한다.
//
// 측정 항목(Issue 본문 "검증 항목"):
//   - 실패 요청 응답시간(두 경로를 별도 Trend로 분리)
//   - 반복 실패가 Connection Pool·처리량에 미치는 영향(Prometheus HikariCP 지표와 함께 본다)
//   - DB 상태가 계약대로 롤백되는지, 구조화 로그에 paymentId·refundId·cancellationId가 남는지,
//     #141에서 식별·복구 가능한 상태인지는 k6로 직접 검증하지 않는다 — 애플리케이션 로그·DB를
//     실제 측정 시점에 직접 조회해 확인한다(기존 단위 테스트가 코드 계약은 이미 보장한다).
//
// 실행 예:
//   k6 run -e STAGE=smoke ops/load-test/scenarios/refund-completion-failure.js

import exec from 'k6/execution';
import http from 'k6/http';
import { Trend } from 'k6/metrics';
import { check } from 'k6';
import { post } from '../common/helpers.js';
import { STAGE } from '../common/config.js';
import { buildCreateTargetPool } from '../common/fixture.js';
import { createMemberPool, prepareAndPayReservation } from '../common/refundFixture.js';
import { cancelledWebhookRequest, refundCancellationIdFor } from '../common/portoneWebhook.js';

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
    stress: {
        executor: 'ramping-arrival-rate',
        startRate: Number(__ENV.STRESS_START_RATE || 20),
        timeUnit: '1s',
        preAllocatedVUs: Number(__ENV.STRESS_PRE_ALLOCATED_VUS || 200),
        maxVUs: Number(__ENV.STRESS_MAX_VUS || 400),
        stages: [
            { target: 40, duration: '3m' },
            { target: 80, duration: '3m' },
            { target: 160, duration: '3m' },
        ],
    },
};

export const options = {
    scenarios: { refund_completion_failure: STAGE_OPTIONS[STAGE] },
    setupTimeout: __ENV.SETUP_TIMEOUT || '180s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export const immediateFailureDuration = new Trend('refund_completion_failure_immediate_duration');
export const webhookFailureDuration = new Trend('refund_completion_failure_webhook_duration');

const RESERVATION_POOL_SIZE = Number(__ENV.RESERVATION_POOL_SIZE || { smoke: 10, load: 8000, stress: 60000 }[STAGE]);
const MEMBER_POOL_SIZE = Number(__ENV.MEMBER_POOL_SIZE || 20);
const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

export function setup() {
    // buildCreateTargetPool은 하루 단위로 세션을 채우기 때문에 요청한 poolSize보다 훨씬 많은
    // sessionId를 돌려줄 수 있다(#63 Fixture 구현 특성). 그대로 다 쓰면 필요 이상의 예약을
    // 만들며 setup() 시간을 낭비하므로, 실제 필요한 개수만큼만 자른다.
    const { sessionIds: allSessionIds } = buildCreateTargetPool('refund-e', RESERVATION_POOL_SIZE, BASE_DATE);
    const sessionIds = allSessionIds.slice(0, RESERVATION_POOL_SIZE);
    const memberTokens = createMemberPool('refund-e', MEMBER_POOL_SIZE);

    const reservations = sessionIds.map((sessionId, i) =>
        prepareAndPayReservation('refund_completion_failure', memberTokens[i % memberTokens.length], sessionId));

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
    const headers = { Authorization: `Bearer ${reservation.memberToken}` };

    if (globalIteration % 2 === 0) {
        measureImmediateFailure(reservation, headers);
    } else {
        measureWebhookFailure(reservation, headers);
    }
}

function measureImmediateFailure(reservation, headers) {
    const start = Date.now();
    const res = post('refund_completion_failure',
        `/api/reservations/${reservation.reservationId}/participations/me/cancel`,
        { reason: '성능 측정(시나리오 E, 즉시 응답 경로 실패)' },
        Object.assign({ 'X-Perf-Reservation-Completion-Result': 'FAIL' }, headers),
        'refund_cancel_immediate_failure');
    immediateFailureDuration.add(Date.now() - start);

    check(res, {
        'refund_cancel_immediate_failure status is 500': (r) => r.status === 500,
        'refund_cancel_immediate_failure body.success is false': (r) => {
            try {
                return JSON.parse(r.body).success === false;
            } catch (e) {
                return false;
            }
        },
    });
}

function measureWebhookFailure(reservation, headers) {
    const processingRes = post('refund_completion_failure',
        `/api/reservations/${reservation.reservationId}/participations/me/cancel`,
        { reason: '성능 측정(시나리오 E, 웹훅 경로 실패)' },
        Object.assign({ 'X-Perf-Refund-Result': 'PROCESSING' }, headers),
        'refund_cancel_processing_before_failure');
    check(processingRes, { 'refund_cancel_processing_before_failure status is 200': (r) => r.status === 200 });

    const cancellationId = refundCancellationIdFor(reservation.paymentId);
    // 성능 프로파일 Hook은 요청 헤더로 제어된다. 서명까지 끝낸 요청 객체(cancelledWebhookRequest)를
    // 받아 실패 지시 헤더만 덧붙여서 직접 실행한다 — 단건 발송용 sendCancelledWebhook()은 커스텀
    // 헤더를 받지 않는다.
    const req = cancelledWebhookRequest('refund_completion_failure', reservation.paymentId, cancellationId,
        'refund_webhook_failure');
    req.params.headers = Object.assign({ 'X-Perf-Reservation-Completion-Result': 'FAIL' }, req.params.headers);

    const start = Date.now();
    const webhookRes = http.request(req.method, req.url, req.body, req.params);
    webhookFailureDuration.add(Date.now() - start);

    check(webhookRes, { 'refund_webhook_failure status is 500': (r) => r.status === 500 });
}
