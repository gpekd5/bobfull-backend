// #235 — #142 시나리오 A(peak-restaurant-view.js)는 한 iteration에서 식당 상세(detail)와
// 회차 조회(dining-sessions)를 함께 호출해, 둘 중 어느 쪽이 CPU·DB Pool 포화에 더 크게
// 기여하는지 분리해서 보지 못했다(Issue #235 "핵심 원칙": 병목 API를 확인하기 전에 특정
// Query/Index/Cache를 미리 정답으로 확정하지 않는다). 이 스크립트는 `TARGET` env var로 두 API를
// 독립적으로 측정한다 — 같은 STAGE_OPTIONS·Fixture·측정 지표를 peak-restaurant-view.js와
// 그대로 재사용해 #142 Evidence와 직접 비교 가능하게 한다.
//
// 실행 예:
//   k6 run -e STAGE=stress -e TARGET=detail   ops/load-test/scenarios/restaurant-view-hotpath.js
//   k6 run -e STAGE=stress -e TARGET=sessions ops/load-test/scenarios/restaurant-view-hotpath.js
//
// TARGET을 생략하면 오류로 즉시 중단한다(둘 중 하나를 명시하지 않으면 #142와 같은 "합쳐서
// 측정"이 되어 이 스크립트를 쓰는 의미가 없다).

import { get } from '../common/helpers.js';
import { checkStatus, checkApiSuccess } from '../common/checks.js';
import { STAGE } from '../common/config.js';
import { createOwnerRestaurantTable, createSessionsBulk } from '../common/fixture.js';

const TARGET = __ENV.TARGET;
if (TARGET !== 'detail' && TARGET !== 'sessions') {
    throw new Error(`TARGET은 'detail' 또는 'sessions' 중 하나여야 한다(현재: ${JSON.stringify(TARGET)}).`);
}

// #142의 STAGE_OPTIONS와 완전히 동일 — Before/After 비교의 "동일 K6 부하 단계·duration" 조건
// (Issue #235 "4. 동일 조건 K6 After 측정")을 만족시키기 위해 값을 절대 바꾸지 않는다.
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
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: { restaurant_view_hotpath: STAGE_OPTIONS[STAGE] },
};

const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

export function setup() {
    // #142와 동일하게 좌석 8석 테이블 하나에 하루치 회차(15분 간격)를 만든다 — Fixture 규모가
    // 다르면 요청당 SQL 수·응답 크기가 달라져 Before/After 비교가 무의미해진다.
    const { ownerHeaders, restaurantId, tableId } = createOwnerRestaurantTable('hotpath-view', 8);
    createSessionsBulk(ownerHeaders, tableId, [BASE_DATE], '00:00', '23:45', 15);
    return { restaurantId, date: BASE_DATE };
}

export default function (data) {
    if (TARGET === 'detail') {
        const res = get('restaurant_view_hotpath', `/api/restaurants/${data.restaurantId}`, {}, 'hotpath_restaurant_detail');
        checkStatus(res, 200, 'hotpath_restaurant_detail');
        checkApiSuccess(res, 'hotpath_restaurant_detail');
        return;
    }

    const res = get('restaurant_view_hotpath', `/api/restaurants/${data.restaurantId}/dining-sessions?date=${data.date}`, {}, 'hotpath_dining_sessions');
    checkStatus(res, 200, 'hotpath_dining_sessions');
    checkApiSuccess(res, 'hotpath_dining_sessions');
}
