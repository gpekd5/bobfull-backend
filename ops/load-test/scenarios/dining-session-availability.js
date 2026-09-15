// #63 P0-B. 식당 예약 가능 회차 조회 GET /api/restaurants/{restaurantId}/dining-sessions —
// 조회형이라 arrival-rate를 우선 후보로 둔다. setup()에서 Fixture 식당·테이블·회차를 만들어
// 반복 조회의 latency와 DB Query/Connection 영향을 관찰한다(Issue #63 P0-B 목적).

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
    scenarios: { dining_session_availability: STAGE_OPTIONS[STAGE] },
};

const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

export function setup() {
    const { ownerHeaders, restaurantId, tableId } = createOwnerRestaurantTable('avail', 8);
    createSessionsBulk(ownerHeaders, tableId, [BASE_DATE], '00:00', '23:30', 30);
    return { restaurantId };
}

export default function (data) {
    const res = get('dining_session_availability', `/api/restaurants/${data.restaurantId}/dining-sessions?date=${BASE_DATE}`);
    checkStatus(res, 200, 'dining_session_availability');
    checkApiSuccess(res, 'dining_session_availability');
}
