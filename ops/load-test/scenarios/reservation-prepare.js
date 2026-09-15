// #63 P0-C. 예약 준비(CREATE) POST /api/reservations/prepare 일반 Load —
// 상태 변경형이라 closed-model VU를 검토한다(Issue #63 "VU와 Arrival Rate 사용 기준").
// 같은 회차에 좌석 수를 초과하는 폭발적 동시 클릭/락 경쟁은 #142로 위임하며, 여기서는 일반
// Load 수준만 본다.
//
// CREATE는 TimeSlot(DiningSession)당 단 하나만 성공하고 두 번째부터는 409
// ACTIVE_RESERVATION_ALREADY_EXISTS다(ReservationPreparationService#validateNoActiveCreate).
// 그래서 setup()에서 VU*iteration 총량 이상의 미사용 회차 풀을 만들고, 각 반복이 겹치지 않게
// 하나씩 소비한다 — 같은 대상을 반복 호출해 멱등 응답/409만 측정하는 걸 막기 위한 장치다
// (Issue #63 "테스트 데이터 계약").
//
// 실행 예:
//   k6 run -e STAGE=smoke ops/load-test/scenarios/reservation-prepare.js
//
// SESSION_POOL_SIZE는 (VU 수 * 예상 iteration 수) 이상으로 넉넉히 잡아야 한다. 풀이 부족하면
// setup()이 예외로 즉시 실패한다(무음으로 409만 쌓이는 것보다 실행 실패가 안전하다).
//
// (PR #208 리뷰 반영) 예약 준비 API는 호출당 수십ms라 think-time 없이 돌리면 VU 수 대비
// iteration이 지나치게 빨라져, 아무리 SESSION_POOL_SIZE를 키워도 5분 안정 구간을 버티기 전에
// 풀이 바닥날 수 있다(hyeonseung-dev 재검토, MAJOR). load/stress에는 실제 사용자의 "생각하는
// 시간"에 해당하는 THINK_TIME_SECONDS(기본 1초) sleep을 넣어 소비 속도를 현실적인 범위로
// 낮췄다 — smoke는 스크립트 동작 검증만 하면 되므로 think-time을 넣지 않는다.

import exec from 'k6/execution';
import { sleep } from 'k6';
import { post, parseData } from '../common/helpers.js';
import { checkStatus, checkApiSuccess } from '../common/checks.js';
import { STAGE, RUN_ID } from '../common/config.js';
import { buildCreateTargetPool } from '../common/fixture.js';
import { signupMember, login, authHeaders, uniquePhoneNumber } from '../common/auth.js';

const STAGE_OPTIONS = {
    smoke: {
        executor: 'per-vu-iterations',
        vus: 2,
        iterations: 3,
        maxDuration: '30s',
    },
    load: {
        executor: 'constant-vus',
        vus: Number(__ENV.LOAD_VUS || 20),
        duration: __ENV.LOAD_DURATION || '5m',
    },
    stress: {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [
            { target: Number(__ENV.STRESS_START_VUS || 20), duration: '1m' },
            { target: 40, duration: '3m' },
            { target: 80, duration: '3m' },
            { target: 160, duration: '3m' },
        ],
    },
};

export const options = {
    scenarios: { reservation_prepare: STAGE_OPTIONS[STAGE] },
};

// Smoke=vus*iterations로 정확히 계산 가능. load/stress는 duration 기반이라 총량을 미리
// 정확히 알 수 없으므로, THINK_TIME_SECONDS로 소비 속도를 낮춘 뒤에도 넉넉하도록 잡는다.
// 그래도 실제 실행(#207)에서는 VU×duration/(latency+think-time)으로 다시 계산해 조정해야 한다.
const SESSION_POOL_SIZE = Number(__ENV.SESSION_POOL_SIZE || { smoke: 10, load: 8000, stress: 50000 }[STAGE]);
const MEMBER_POOL_SIZE = Number(__ENV.MEMBER_POOL_SIZE || 20);
const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || (STAGE === 'smoke' ? 0 : 1));

export function setup() {
    const { restaurantId, sessionIds } = buildCreateTargetPool('prepare', SESSION_POOL_SIZE, BASE_DATE);

    const memberTokens = [];
    for (let i = 0; i < MEMBER_POOL_SIZE; i += 1) {
        const email = `prepare-member-${i}-${RUN_ID}@bobfull.test`;
        const password = 'Perf1234!aA';
        const signupRes = signupMember(email, password, `부하테스트회원${i}`, uniquePhoneNumber(email));
        if (signupRes.status !== 201) {
            throw new Error(`Fixture 회원 가입 실패(i=${i}): status=${signupRes.status} body=${signupRes.body}`);
        }
        memberTokens.push(login(email, password));
    }

    return { restaurantId, sessionIds, memberTokens };
}

export default function (data) {
    // exec.scenario.iterationInTest: 이 시나리오 전체에서 겹치지 않는 전역 반복 번호다.
    // 회차 풀 인덱스로 써서 같은 회차를 두 번 소비하지 않게 한다.
    const globalIteration = exec.scenario.iterationInTest;
    if (globalIteration >= data.sessionIds.length) {
        throw new Error(
            `회차 풀 소진: iteration=${globalIteration} poolSize=${data.sessionIds.length} — SESSION_POOL_SIZE를 늘려 재실행하라.`
        );
    }
    const sessionId = data.sessionIds[globalIteration];
    const token = data.memberTokens[globalIteration % data.memberTokens.length];

    const res = post('reservation_prepare', '/api/reservations/prepare', {
        type: 'CREATE',
        targetId: sessionId,
        partySize: 2,
    }, authHeaders(token));

    checkStatus(res, 200, 'reservation_prepare');
    checkApiSuccess(res, 'reservation_prepare');

    if (THINK_TIME_SECONDS > 0) {
        sleep(THINK_TIME_SECONDS);
    }
}
