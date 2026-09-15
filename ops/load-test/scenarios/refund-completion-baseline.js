// Issue #146 시나리오 A — 환불 완료 동기 경로 기준선.
//
// 추가 지연·장애를 주입하지 않은 실제 V2 구조(RefundCompletionService REQUIRES_NEW →
// ReservationCancellationCompletionService MANDATORY)의 정상 성능을 측정한다. Issue 본문이
// 요구한 대로 "즉시 완료 응답 경로"와 "웹훅 경로"를 각각 별도 Trend로 측정해 p95/p99를 분리한다.
//
// - 즉시 응답 경로: `POST /api/reservations/{id}/participations/me/cancel` 하나로 PortOne
//   즉시 성공 응답(X-Perf-Refund-Result 생략 = SUCCESS)까지 받아 환불이 완료된다.
// - 웹훅 경로: 같은 취소 요청을 PortOne이 아직 처리 중이라고 응답하는 상황(X-Perf-Refund-Result:
//   PROCESSING)으로 보낸 뒤, 뒤이어 도착하는 `Transaction.Cancelled` 웹훅을 K6가 직접 서명해
//   보내 완료시킨다 — 이 웹훅 호출만 별도로 측정한다.
//
// 두 경로 모두 취소 요청은 PortOne 네트워크 호출 없이 `performance` 프로파일 전용 fake Bean
// (PerformanceTestRefundRequester/PerformanceTestPaymentReader)으로 재현한다(Issue #146 구현
// 중 발견한 인프라 결정, PR 본문 참고).
//
// Fixture는 한 예약을 한 번만 취소할 수 있어(ParticipationStatus CANCEL_REQUESTED로 소비),
// setup()에서 총 반복 수만큼 "PAID 결제 + 확정된 예약"을 미리 만들어 둔다(Issue #63 "테스트
// 데이터 계약"과 동일한 전략).
//
// 실행 예:
//   k6 run -e STAGE=smoke ops/load-test/scenarios/refund-completion-baseline.js

import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import { post } from '../common/helpers.js';
import { checkStatus, checkApiSuccess } from '../common/checks.js';
import { STAGE, RUN_ID } from '../common/config.js';
import { buildCreateTargetPool } from '../common/fixture.js';
import { createMemberPool, prepareAndPayReservation } from '../common/refundFixture.js';
import { sendCancelledWebhook, refundCancellationIdFor } from '../common/portoneWebhook.js';

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
            { target: 320, duration: '3m' },
        ],
    },
};

export const options = {
    scenarios: { refund_completion_baseline: STAGE_OPTIONS[STAGE] },
    setupTimeout: __ENV.SETUP_TIMEOUT || '180s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

// 즉시 응답 경로와 웹훅 경로를 같은 http_req_duration에 섞으면 서로 다른 두 작업(취소 요청 1회 vs
// 취소 요청 1회 + 웹훅 1회)의 지연이 뒤섞여 어느 쪽도 대표하지 못한다(Issue #63/#220의 교훈과
// 동일). default()가 실제로 측정하려는 호출 하나만 각 Trend에 기록해 분리한다.
export const immediateCompletionDuration = new Trend('refund_immediate_completion_duration');
export const webhookCompletionDuration = new Trend('refund_webhook_completion_duration');

const RESERVATION_POOL_SIZE = Number(__ENV.RESERVATION_POOL_SIZE || { smoke: 10, load: 8000, stress: 60000 }[STAGE]);
const MEMBER_POOL_SIZE = Number(__ENV.MEMBER_POOL_SIZE || 20);
const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

export function setup() {
    // buildCreateTargetPool은 하루 단위로 세션을 채우기 때문에 요청한 poolSize보다 훨씬 많은
    // sessionId를 돌려줄 수 있다(#63 Fixture 구현 특성). 그대로 다 쓰면 필요 이상의 예약을
    // 만들며 setup() 시간을 낭비하므로, 실제 필요한 개수만큼만 자른다.
    const { sessionIds: allSessionIds } = buildCreateTargetPool('refund-a', RESERVATION_POOL_SIZE, BASE_DATE);
    const sessionIds = allSessionIds.slice(0, RESERVATION_POOL_SIZE);
    const memberTokens = createMemberPool('refund-a', MEMBER_POOL_SIZE);

    const reservations = sessionIds.map((sessionId, i) =>
        prepareAndPayReservation('refund_completion_baseline', memberTokens[i % memberTokens.length], sessionId));

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

    // 짝수 반복은 즉시 응답 경로, 홀수 반복은 웹훅 경로를 측정한다 — 두 경로를 절반씩 섞어야
    // Issue 본문이 요구한 "즉시 완료 응답과 웹훅 경로를 각각 측정"을 한 실행으로 충족한다.
    if (globalIteration % 2 === 0) {
        measureImmediateCompletion(reservation, headers);
    } else {
        measureWebhookCompletion(reservation, headers);
    }
}

function measureImmediateCompletion(reservation, headers) {
    const start = Date.now();
    const res = post('refund_completion_baseline',
        `/api/reservations/${reservation.reservationId}/participations/me/cancel`,
        { reason: '성능 측정(시나리오 A, 즉시 응답 경로)' },
        headers,
        'refund_cancel_immediate');
    immediateCompletionDuration.add(Date.now() - start);

    checkStatus(res, 200, 'refund_cancel_immediate');
    checkApiSuccess(res, 'refund_cancel_immediate');
}

function measureWebhookCompletion(reservation, headers) {
    const cancelRes = post('refund_completion_baseline',
        `/api/reservations/${reservation.reservationId}/participations/me/cancel`,
        { reason: '성능 측정(시나리오 A, 웹훅 경로)' },
        Object.assign({ 'X-Perf-Refund-Result': 'PROCESSING' }, headers),
        'refund_cancel_processing');
    checkStatus(cancelRes, 200, 'refund_cancel_processing');
    checkApiSuccess(cancelRes, 'refund_cancel_processing');

    const cancellationId = refundCancellationIdFor(reservation.paymentId);
    const start = Date.now();
    const webhookRes = sendCancelledWebhook('refund_completion_baseline', reservation.paymentId, cancellationId,
        'refund_webhook_cancelled');
    webhookCompletionDuration.add(Date.now() - start);

    checkStatus(webhookRes, 200, 'refund_webhook_cancelled');
}
