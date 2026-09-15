// Issue #146 시나리오 D — 예약 완료 로직이 무거워진 상황.
//
// 향후 예약 완료 후속 처리(통계·알림 등)가 늘어나면 웹훅 응답·DB 자원에 어떤 영향이 있는지,
// `ReservationCancellationCompletionService.complete()` 안에 인위적 지연을 주입해 미리 확인한다.
//
// 지연 주입은 `performance` 프로파일 전용 fake Bean(`PerformanceTestReservationCompletionHook`,
// Issue #146 구현 중 Human이 승인한 지연 주입 방법)으로만 이뤄진다 — 운영 코드에는 지연이 전혀
// 없고, Reservation 락을 잡은 직후(참여자 조건부 UPDATE 이전) `X-Perf-Reservation-Completion-
// Delay-Ms` 헤더 값만큼 `Thread.sleep()`한다. 이 지연은 실제 통계·알림 처리와 달리 CPU·DB
// 자원을 전혀 쓰지 않고 Reservation 락만 그만큼 더 오래 쥔다는 차이가 있다 — 순수 락 보유
// 시간 증가가 웹훅 지연·처리량에 미치는 영향만 분리해서 보여준다(실제 무거운 로직은 CPU·DB
// 부하도 함께 늘어나므로 이 결과보다 더 나쁠 수 있다).
//
// 부하 단계는 고정하고(Load, 20 iter/s) `RESERVATION_COMPLETION_DELAY_MS`만 바꿔가며 같은
// 스크립트를 여러 번 실행해 비교한다(Issue 본문 제시 단계: 0(기준)·100·300·500·1000ms).
//
// 실행 예:
//   k6 run -e STAGE=smoke -e RESERVATION_COMPLETION_DELAY_MS=300 ops/load-test/scenarios/refund-completion-delay-injection.js

import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import { post } from '../common/helpers.js';
import { checkStatus, checkApiSuccess } from '../common/checks.js';
import { STAGE } from '../common/config.js';
import { buildCreateTargetPool } from '../common/fixture.js';
import { createMemberPool, prepareAndPayReservation } from '../common/refundFixture.js';

const RESERVATION_COMPLETION_DELAY_MS = Number(__ENV.RESERVATION_COMPLETION_DELAY_MS || 0);

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
    scenarios: { refund_delay_injection: STAGE_OPTIONS[STAGE] },
    setupTimeout: __ENV.SETUP_TIMEOUT || '180s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export const delayInjectionDuration = new Trend('refund_delay_injection_duration');

const RESERVATION_POOL_SIZE = Number(__ENV.RESERVATION_POOL_SIZE || { smoke: 10, load: 8000 }[STAGE]);
const MEMBER_POOL_SIZE = Number(__ENV.MEMBER_POOL_SIZE || 20);
const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

export function setup() {
    // buildCreateTargetPool은 하루 단위로 세션을 채우기 때문에 요청한 poolSize보다 훨씬 많은
    // sessionId를 돌려줄 수 있다(#63 Fixture 구현 특성). 그대로 다 쓰면 필요 이상의 예약을
    // 만들며 setup() 시간을 낭비하므로, 실제 필요한 개수만큼만 자른다.
    const { sessionIds: allSessionIds } = buildCreateTargetPool('refund-d', RESERVATION_POOL_SIZE, BASE_DATE);
    const sessionIds = allSessionIds.slice(0, RESERVATION_POOL_SIZE);
    const memberTokens = createMemberPool('refund-d', MEMBER_POOL_SIZE);

    const reservations = sessionIds.map((sessionId, i) =>
        prepareAndPayReservation('refund_delay_injection', memberTokens[i % memberTokens.length], sessionId));

    return { reservations, delayMs: RESERVATION_COMPLETION_DELAY_MS };
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
    if (data.delayMs > 0) {
        headers['X-Perf-Reservation-Completion-Delay-Ms'] = String(data.delayMs);
    }

    const start = Date.now();
    const res = post('refund_delay_injection',
        `/api/reservations/${reservation.reservationId}/participations/me/cancel`,
        { reason: `성능 측정(시나리오 D, 지연 ${data.delayMs}ms)` },
        headers,
        'refund_cancel_delay_injection');
    delayInjectionDuration.add(Date.now() - start);

    checkStatus(res, 200, 'refund_cancel_delay_injection');
    checkApiSuccess(res, 'refund_cancel_delay_injection');
}
