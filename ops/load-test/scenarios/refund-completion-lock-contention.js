// Issue #146 시나리오 B — 동일 Reservation 락 경쟁.
//
// (로컬 실배포 검증 중 정정) 여러 참여자가 각자 `.../participations/me/cancel`을 호출하는
// 설계는 실제 API 계약과 맞지 않는다 — `ReservationCancellationTransactionService.accept()`는
// 예약 생성자가 취소를 접수하면 유효 참여자 전원을 한 번에 CANCEL_REQUESTED로 전환하고
// Reservation을 CANCELLING으로 전이한다(`acceptEntireReservationCancellation`). 그 뒤에 다른
// 참여자가 같은 엔드포인트를 불러도 `validateReservationCancellable()`이 이미 CANCELLING인
// 예약을 `RESERVATION_ALREADY_CANCELLED`(409)로 거부한다 — 로컬 실배포 검증에서 실제로 이
// 409를 재현해 확인했다.
//
// 즉 "동일 Reservation 락 경쟁"의 실제 발생 지점은 취소 접수(요청)가 아니라 환불 완료(웹훅)
// 단계다: 생성자의 접수 한 번이 참여자 수만큼의 Refund를 만들고, PortOne이 그 각각을 완료
// 처리하면(웹훅 또는 재조회) 참여자별 `RefundCompletionService.completeFromWebhook()` →
// `ReservationCancellationCompletionService.complete()`가 전부 같은 Reservation 행 비관적
// 락(`findWithLockById`)에서 직렬화된다. 이 시나리오는 그 직렬화 비용을 측정한다:
//
//   1. 생성자가 그룹 취소를 접수한다(`X-Perf-Refund-Result: PROCESSING`이므로 접수 시점에는
//      아무 Refund도 완료되지 않는다 — 참여자 수만큼의 Refund가 REQUESTED로만 남는다).
//   2. 참여자별 `Transaction.Cancelled` 웹훅을 `http.batch()`로 진짜 동시에 쏴서, 그 Refund들이
//      전부 같은 시점에 완료 처리를 시도하게 만든다.
//
// k6 `http.batch()`로 쏴야 직렬화가 실제로 재현된다 — 서로 다른 VU/iteration에 나눠 보내면
// 도착 시각이 우연에 좌우돼 락 경쟁이 보장되지 않는다(Issue #63 "동시성 재현" 원칙과 동일).
//
// 정합성(모든 참여자가 정확히 한 번만 CANCELLED되는지, 마지막 참여자만 Reservation을 확정하는지)
// 은 이미 `ReservationCancellationCompletionServiceTest` 등 기존 유닛 테스트가 보장한다 — 이
// 스크립트는 그 위에서 실제 동시 부하일 때의 지연·처리량만 측정한다.
//
// 실행 예:
//   k6 run -e STAGE=smoke ops/load-test/scenarios/refund-completion-lock-contention.js

import exec from 'k6/execution';
import http from 'k6/http';
import { Trend } from 'k6/metrics';
import { check } from 'k6';
import { post } from '../common/helpers.js';
import { checkStatus, checkApiSuccess } from '../common/checks.js';
import { STAGE } from '../common/config.js';
import { buildCreateTargetPool } from '../common/fixture.js';
import { createMemberPool, prepareGroupReservation } from '../common/refundFixture.js';
import { cancelledWebhookRequest, refundCancellationIdFor } from '../common/portoneWebhook.js';

// fixture.js의 SharedTable capacity 기본값(8)을 넘으면 JOIN이 정원 초과로 실패한다.
const PARTICIPANTS_PER_GROUP = Number(__ENV.PARTICIPANTS_PER_GROUP || 3);

const STAGE_OPTIONS = {
    smoke: {
        executor: 'per-vu-iterations',
        vus: 1,
        iterations: 3,
        maxDuration: '30s',
    },
    load: {
        executor: 'constant-arrival-rate',
        rate: Number(__ENV.LOAD_RATE || 10),
        timeUnit: '1s',
        duration: __ENV.LOAD_DURATION || '5m',
        preAllocatedVUs: Number(__ENV.LOAD_PRE_ALLOCATED_VUS || 40),
        maxVUs: Number(__ENV.LOAD_MAX_VUS || 100),
    },
    stress: {
        executor: 'ramping-arrival-rate',
        startRate: Number(__ENV.STRESS_START_RATE || 10),
        timeUnit: '1s',
        preAllocatedVUs: Number(__ENV.STRESS_PRE_ALLOCATED_VUS || 200),
        maxVUs: Number(__ENV.STRESS_MAX_VUS || 400),
        stages: [
            { target: 20, duration: '3m' },
            { target: 40, duration: '3m' },
            { target: 80, duration: '3m' },
            { target: 160, duration: '3m' },
        ],
    },
};

export const options = {
    scenarios: { refund_lock_contention: STAGE_OPTIONS[STAGE] },
    setupTimeout: __ENV.SETUP_TIMEOUT || '180s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

// 그룹 전체(참여자 전원 웹훅 동시 완료)가 끝나는 데 걸린 전체 시간과, 참여자별 개별 응답
// 시간을 나눠 기록한다 — 직렬화 비용이 있다면 늦게 락을 잡는 참여자일수록 개별 응답 시간이
// 늘어난다.
export const groupCancelBatchDuration = new Trend('refund_group_cancel_batch_duration');
export const participantCancelDuration = new Trend('refund_group_cancel_participant_duration');

const GROUP_POOL_SIZE = Number(__ENV.GROUP_POOL_SIZE || { smoke: 3, load: 1500, stress: 3000 }[STAGE]);
const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

export function setup() {
    // buildCreateTargetPool은 하루 단위로 세션을 채우기 때문에 요청한 poolSize(그룹 수)보다
    // 훨씬 많은 sessionId를 돌려줄 수 있다(#63 Fixture 구현 특성) — 그대로 다 쓰면 그룹 수만큼만
    // 마련해 둔 memberTokens 풀이 바닥나 뒤쪽 그룹은 참여자 0명으로 잘린다. 실제 그룹 수만큼만 자른다.
    const { sessionIds: allSessionIds } = buildCreateTargetPool('refund-b', GROUP_POOL_SIZE, BASE_DATE);
    const sessionIds = allSessionIds.slice(0, GROUP_POOL_SIZE);
    const memberTokens = createMemberPool('refund-b', GROUP_POOL_SIZE * PARTICIPANTS_PER_GROUP);

    const groups = sessionIds.map((sessionId, i) => {
        const groupTokens = memberTokens.slice(
            i * PARTICIPANTS_PER_GROUP, (i + 1) * PARTICIPANTS_PER_GROUP);
        return prepareGroupReservation('refund_lock_contention', groupTokens, sessionId);
    });

    return { groups };
}

export default function (data) {
    const globalIteration = exec.scenario.iterationInTest;
    if (globalIteration >= data.groups.length) {
        throw new Error(
            `그룹 Fixture 풀 소진: iteration=${globalIteration} poolSize=${data.groups.length} — GROUP_POOL_SIZE를 늘려 재실행하라.`
        );
    }
    const group = data.groups[globalIteration];
    const creator = group.participants[0];

    // 1) 생성자가 그룹 전체 취소를 접수한다. PortOne 즉시 응답은 PROCESSING으로 강제해, 접수
    //    시점에는 어떤 참여자의 Refund도 완료되지 않게 한다(측정 대상은 웹훅 동시 도착 자체다).
    const acceptRes = post('refund_lock_contention',
        `/api/reservations/${group.reservationId}/participations/me/cancel`,
        { reason: '성능 측정(시나리오 B, 그룹 취소 접수)' },
        { Authorization: `Bearer ${creator.memberToken}`, 'X-Perf-Refund-Result': 'PROCESSING' },
        'refund_cancel_group_accept');
    checkStatus(acceptRes, 200, 'refund_cancel_group_accept');
    checkApiSuccess(acceptRes, 'refund_cancel_group_accept');

    // 2) 참여자별 완료 웹훅을 http.batch()로 진짜 동시에 쏴서 Reservation 행 락 경쟁을 재현한다.
    const requests = group.participants.map((participant) =>
        cancelledWebhookRequest('refund_lock_contention', participant.paymentId,
            refundCancellationIdFor(participant.paymentId), 'refund_webhook_group_participant'));

    const batchStart = Date.now();
    const responses = http.batch(requests);
    groupCancelBatchDuration.add(Date.now() - batchStart);

    responses.forEach((res, i) => {
        participantCancelDuration.add(res.timings.duration);
        check(res, {
            'refund_webhook_group_participant status is 200': (r) => r.status === 200,
        }, { participantIndex: String(i) });
    });
}
