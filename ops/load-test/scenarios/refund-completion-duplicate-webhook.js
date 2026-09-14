// Issue #146 시나리오 C — 즉시 응답과 웹훅 중복·경쟁.
//
// PortOne이 같은 환불에 대해 즉시 완료 응답과 `Transaction.Cancelled` 웹훅을 모두 보내는 경우
// (또는 웹훅을 재전송하는 경우)를 재현한다. 두 경로 모두 결국 같은 `RefundCompletionService.
// completeFromWebhook()`/`reflectExternalResult()` → `ReservationCancellationCompletionService.
// complete()`에 도달하는데, 후자는 `completeCancelIfRequested()`의 조건부 UPDATE("CANCEL_REQUESTED
// 상태일 때만 CANCELLED로 전이")로 두 번째 도착을 멱등하게 무시한다. 이 스크립트는 그 무해함을
// 실제 HTTP 응답(200, 오류 없음)으로 재확인하고, 중복 경로가 지연에 미치는 영향을 측정한다.
//
// 두 하위 시나리오를 반복마다 절반씩 섞는다:
//   (짝수) 순차 중복 — 즉시 응답으로 이미 완료된 뒤, 같은 cancellationId로 웹훅이 뒤늦게
//          도착하는 가장 흔한 순서. 웹훅 자체의 응답시간만 측정한다.
//   (홀수) 동시 경쟁 — 즉시 응답 요청과 웹훅 요청을 `http.batch()`로 진짜 동시에 쏴서 Issue
//          본문의 다이어그램(즉시 응답 ─┐ / 웹훅 ───────┘ → 같은 Refund 완료 경로 경쟁)을
//          그대로 재현한다. 웹훅이 Refund 생성 이전에 도착해 조회 자체가 안 되는(공백 처리)
//          타이밍도 나올 수 있는데, 이 역시 실제 PortOne 환경에서 벌어질 수 있는 유효한 결과다.
//
// 정합성(Refund·Payment가 정확히 한 번만 완료되는지, 재계산이 중복 실행되지 않는지)은 기존
// `RefundTransactionServiceTest`/`ReservationCancellationCompletionServiceTest`가 보장한다 — 이
// 스크립트는 그 위에서 중복 경로가 실제 지연·오류율에 미치는 영향만 측정한다.
//
// 실행 예:
//   k6 run -e STAGE=smoke ops/load-test/scenarios/refund-completion-duplicate-webhook.js

import exec from 'k6/execution';
import http from 'k6/http';
import { Trend } from 'k6/metrics';
import { check } from 'k6';
import { post } from '../common/helpers.js';
import { checkStatus, checkApiSuccess } from '../common/checks.js';
import { BASE_URL, STAGE, scenarioTags } from '../common/config.js';
import { buildCreateTargetPool } from '../common/fixture.js';
import { createMemberPool, prepareAndPayReservation } from '../common/refundFixture.js';
import { sendCancelledWebhook, cancelledWebhookRequest, refundCancellationIdFor } from '../common/portoneWebhook.js';

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
    scenarios: { refund_duplicate_webhook: STAGE_OPTIONS[STAGE] },
    setupTimeout: __ENV.SETUP_TIMEOUT || '180s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

// 순차 중복(웹훅만)과 동시 경쟁(즉시 응답+웹훅 전체)은 서로 다른 작업이라 같은 Trend에 섞지 않는다.
export const duplicateWebhookAfterImmediateDuration = new Trend('refund_duplicate_webhook_after_immediate_duration');
export const concurrentRaceBatchDuration = new Trend('refund_concurrent_race_batch_duration');

const RESERVATION_POOL_SIZE = Number(__ENV.RESERVATION_POOL_SIZE || { smoke: 10, load: 8000, stress: 60000 }[STAGE]);
const MEMBER_POOL_SIZE = Number(__ENV.MEMBER_POOL_SIZE || 20);
const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

export function setup() {
    // buildCreateTargetPool은 하루 단위로 세션을 채우기 때문에 요청한 poolSize보다 훨씬 많은
    // sessionId를 돌려줄 수 있다(#63 Fixture 구현 특성). 그대로 다 쓰면 필요 이상의 예약을
    // 만들며 setup() 시간을 낭비하므로, 실제 필요한 개수만큼만 자른다.
    const { sessionIds: allSessionIds } = buildCreateTargetPool('refund-c', RESERVATION_POOL_SIZE, BASE_DATE);
    const sessionIds = allSessionIds.slice(0, RESERVATION_POOL_SIZE);
    const memberTokens = createMemberPool('refund-c', MEMBER_POOL_SIZE);

    const reservations = sessionIds.map((sessionId, i) =>
        prepareAndPayReservation('refund_duplicate_webhook', memberTokens[i % memberTokens.length], sessionId));

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
        measureDuplicateWebhookAfterImmediate(reservation, headers);
    } else {
        measureConcurrentRace(reservation, headers);
    }
}

function measureDuplicateWebhookAfterImmediate(reservation, headers) {
    const cancelRes = post('refund_duplicate_webhook',
        `/api/reservations/${reservation.reservationId}/participations/me/cancel`,
        { reason: '성능 측정(시나리오 C, 순차 중복 웹훅)' },
        headers,
        'refund_cancel_immediate_before_duplicate');
    checkStatus(cancelRes, 200, 'refund_cancel_immediate_before_duplicate');
    checkApiSuccess(cancelRes, 'refund_cancel_immediate_before_duplicate');

    const cancellationId = refundCancellationIdFor(reservation.paymentId);
    const start = Date.now();
    const webhookRes = sendCancelledWebhook('refund_duplicate_webhook', reservation.paymentId, cancellationId,
        'refund_webhook_duplicate_after_immediate');
    duplicateWebhookAfterImmediateDuration.add(Date.now() - start);

    checkStatus(webhookRes, 200, 'refund_webhook_duplicate_after_immediate');
}

function measureConcurrentRace(reservation, headers) {
    const cancellationId = refundCancellationIdFor(reservation.paymentId);

    const start = Date.now();
    const [cancelRes, webhookRes] = http.batch([
        {
            method: 'POST',
            url: `${BASE_URL}/api/reservations/${reservation.reservationId}/participations/me/cancel`,
            body: JSON.stringify({ reason: '성능 측정(시나리오 C, 동시 경쟁)' }),
            params: {
                headers: Object.assign({ 'Content-Type': 'application/json' }, headers),
                tags: scenarioTags('refund_duplicate_webhook', { name: 'refund_cancel_concurrent_race' }),
            },
        },
        cancelledWebhookRequest('refund_duplicate_webhook', reservation.paymentId, cancellationId,
            'refund_webhook_concurrent_race'),
    ]);
    concurrentRaceBatchDuration.add(Date.now() - start);

    check(cancelRes, { 'refund_cancel_concurrent_race status is 200': (r) => r.status === 200 });
    check(webhookRes, { 'refund_webhook_concurrent_race status is 200': (r) => r.status === 200 });
}
