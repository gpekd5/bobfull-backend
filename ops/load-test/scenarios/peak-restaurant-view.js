// #142 시나리오 A — 예약 페이지 조회 폭주. #63의 조회 시나리오(restaurant-search.js,
// dining-session-availability.js)는 여러 식당·키워드에 걸친 균등 부하를 모사하지만, 이번
// 시나리오는 "인기 회차가 공개된 순간 같은 식당·같은 날짜에 조회가 집중되는" hot-key 패턴을
// 모사한다(Issue #142 시나리오 A "목적"). setup()에서 만든 단 하나의 식당·날짜만 반복 조회한다.
//
// 실행 예:
//   k6 run -e STAGE=smoke ops/load-test/scenarios/peak-restaurant-view.js
//
// 측정 항목(Issue #142 "공통 측정 지표"): p50/p90/p95/p99, RPS, 오류율, 요청당 쿼리 수(서버
// 로그/Grafana에서 별도 확인), DB Connection Pool active/pending, CPU/메모리. 이 스크립트는
// HTTP 레벨 지표만 낸다 — 나머지는 #64 Prometheus/Grafana와 같은 시간축으로 함께 봐야 한다
// (Issue #142 "측정 환경 계약").

import { get } from '../common/helpers.js';
import { checkStatus, checkApiSuccess } from '../common/checks.js';
import { STAGE } from '../common/config.js';
import { createOwnerRestaurantTable, createSessionsBulk } from '../common/fixture.js';

const STAGE_OPTIONS = {
    smoke: {
        executor: 'constant-arrival-rate',
        rate: 1,
        timeUnit: '1s',
        duration: '20s',
        preAllocatedVUs: 2,
        maxVUs: 5,
    },
    load: {
        executor: 'constant-arrival-rate',
        rate: Number(__ENV.LOAD_RATE || 20),
        timeUnit: '1s',
        duration: __ENV.LOAD_DURATION || '5m',
        preAllocatedVUs: Number(__ENV.LOAD_VUS || 20),
        maxVUs: Number(__ENV.LOAD_MAX_VUS || 60),
    },
    stress: {
        executor: 'ramping-arrival-rate',
        startRate: Number(__ENV.STRESS_START_RATE || 20),
        timeUnit: '1s',
        preAllocatedVUs: Number(__ENV.STRESS_VUS || 50),
        maxVUs: Number(__ENV.STRESS_MAX_VUS || 300),
        stages: [
            { target: Number(__ENV.STRESS_START_RATE || 20), duration: '1m' },
            { target: 40, duration: '3m' },
            { target: 80, duration: '3m' },
            { target: 160, duration: '3m' },
            { target: 320, duration: '3m' },
        ],
    },
};

export const options = {
    // k6 기본 summaryTrendStats는 p99를 포함하지 않는다 — Issue #142 "공통 측정 지표"가
    // p50·p90·p95·p99를 요구해 명시적으로 추가한다(PR #220 재검토 반영).
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: { peak_restaurant_view: STAGE_OPTIONS[STAGE] },
};

const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

export function setup() {
    const { ownerHeaders, restaurantId, tableId } = createOwnerRestaurantTable('peak-view', 8);
    createSessionsBulk(ownerHeaders, tableId, [BASE_DATE], '00:00', '23:45', 15);
    return { restaurantId, date: BASE_DATE };
}

export default function (data) {
    const detailRes = get('peak_restaurant_view', `/api/restaurants/${data.restaurantId}`, {}, 'peak_restaurant_detail');
    checkStatus(detailRes, 200, 'peak_restaurant_detail');
    checkApiSuccess(detailRes, 'peak_restaurant_detail');

    const sessionsRes = get('peak_restaurant_view', `/api/restaurants/${data.restaurantId}/dining-sessions?date=${data.date}`, {}, 'peak_dining_sessions');
    checkStatus(sessionsRes, 200, 'peak_dining_sessions');
    checkApiSuccess(sessionsRes, 'peak_dining_sessions');
}
