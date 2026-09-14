// #63 P0-A. 식당 검색/목록 GET /api/restaurants — 조회형이라 arrival-rate를 우선 후보로 둔다
// (Issue #63 "VU와 Arrival Rate 사용 기준"). #61 SQL 개선·#62 Cache 효과가 실제 HTTP 부하에서도
// 유지되는지 재검증하는 용도로 재사용한다.
//
// 실행 예:
//   k6 run -e STAGE=smoke ops/load-test/scenarios/restaurant-search.js
//   k6 run -e STAGE=load  -e BASE_URL=https://<test-stack> ops/load-test/scenarios/restaurant-search.js
//
// stage별 rate/duration은 절대 성공 기준이 아니라 시작값이다(Issue #63 Q3 Human 결정).
// 최초 Smoke/Load 실행 결과를 보고 -e LOAD_RATE 등으로 조정한 뒤 Evidence에 실제 값을 기록한다.

import { get } from '../common/helpers.js';
import { checkStatus, checkApiSuccess } from '../common/checks.js';
import { STAGE } from '../common/config.js';

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
    scenarios: { restaurant_search: STAGE_OPTIONS[STAGE] },
};

const KEYWORDS = ['맛집', '한식', '고기', '파스타', '카페'];

export default function () {
    const keyword = KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];
    const res = get('restaurant_search', `/api/restaurants?keyword=${encodeURIComponent(keyword)}&page=0&size=20`);
    checkStatus(res, 200, 'restaurant_search');
    checkApiSuccess(res, 'restaurant_search');
}
