// Issue #65 시나리오 C — 반복 조회.
//
// 같은 식당·기간의 정산 조회(지급 예정 총액 + 정산 대상 예약 목록)를 여러 사용자가 반복
// 호출하는 상황을 재현해, 동일 계산 반복 자체가 실제 주요 비용인지 확인한다. Redis
// Cache/Snapshot을 이 Issue에서 선도입하지 않으므로, 이 스크립트는 그 필요성을 "과대평가하지
// 않기 위한" 근거 확보가 목적이다 — 반복 조회가 이미 충분히 빠르면 Cache 도입 근거가 약하다.
//
// SettlementQueryCountInvestigationTest/SettlementQueryScaleInvestigationTest/
// SettlementReservationQueryScaleInvestigationTest(H2/MySQL 통합 테스트)가 SQL 횟수·인덱스
// 효과를 이미 확인했다 — 이 스크립트는 그 위에서 실제 배포 환경의 동시 반복 조회 시
// HTTP p95/p99·DB Pool 영향만 측정한다.
//
// 실행 예:
//   k6 run -e STAGE=smoke ops/load-test/scenarios/settlement-repeated-query.js
//
// ENDPOINT=expected|reservations|both(기본값) — 두 엔드포인트 중 어느 쪽이 DB Pool 부담의
// 주 원인인지 분리 진단할 때 하나만 골라 실행한다.
//   k6 run -e STAGE=load -e ENDPOINT=expected ops/load-test/scenarios/settlement-repeated-query.js
//   k6 run -e STAGE=load -e ENDPOINT=reservations ops/load-test/scenarios/settlement-repeated-query.js

import { get } from '../common/helpers.js';
import { checkStatus, checkApiSuccess } from '../common/checks.js';
import { STAGE } from '../common/config.js';
import { buildCreateTargetPool } from '../common/fixture.js';
import { createMemberPool, prepareAndPayReservation } from '../common/refundFixture.js';

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
        duration: __ENV.LOAD_DURATION || '2m',
        preAllocatedVUs: Number(__ENV.LOAD_PRE_ALLOCATED_VUS || 40),
        maxVUs: Number(__ENV.LOAD_MAX_VUS || 100),
    },
};

export const options = {
    scenarios: { settlement_repeated_query: STAGE_OPTIONS[STAGE] },
    setupTimeout: __ENV.SETUP_TIMEOUT || '300s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const RESERVATION_COUNT = Number(__ENV.RESERVATION_COUNT || { smoke: 10, load: 50 }[STAGE]);
const BASE_DATE = __ENV.BASE_DATE || new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
const ENDPOINT = __ENV.ENDPOINT || 'both';

export function setup() {
    // 식당 1개 + 정산 대상 예약 RESERVATION_COUNT건을 미리 준비한다. 반복 조회 자체가 측정
    // 대상이므로(같은 식당·같은 기간을 계속 다시 조회), Fixture는 setup()에서 한 번만 만든다.
    const { ownerHeaders, restaurantId, sessionIds: allSessionIds } = buildCreateTargetPool(
        'settlement-c', RESERVATION_COUNT, BASE_DATE);
    const sessionIds = allSessionIds.slice(0, RESERVATION_COUNT);
    const memberTokens = createMemberPool('settlement-c', RESERVATION_COUNT);

    sessionIds.forEach((sessionId, i) => {
        prepareAndPayReservation('settlement_repeated_query', memberTokens[i % memberTokens.length], sessionId);
    });

    return { ownerHeaders, restaurantId, startDate: BASE_DATE, endDate: BASE_DATE };
}

export default function (data) {
    const query = `?startDate=${data.startDate}&endDate=${data.endDate}`;

    if (ENDPOINT === 'expected' || ENDPOINT === 'both') {
        const expectedRes = get('settlement_repeated_query',
            `/api/owner/restaurants/${data.restaurantId}/settlements/expected${query}`,
            data.ownerHeaders, 'settlement_expected');
        checkStatus(expectedRes, 200, 'settlement_expected');
        checkApiSuccess(expectedRes, 'settlement_expected');
    }

    if (ENDPOINT === 'reservations' || ENDPOINT === 'both') {
        const listRes = get('settlement_repeated_query',
            `/api/owner/restaurants/${data.restaurantId}/settlements/reservations${query}&size=20`,
            data.ownerHeaders, 'settlement_reservations');
        checkStatus(listRes, 200, 'settlement_reservations');
        checkApiSuccess(listRes, 'settlement_reservations');
    }
}
