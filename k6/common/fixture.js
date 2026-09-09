// #63 공통 K6 Harness — 시나리오가 필요한 Restaurant/SharedTable/DiningSession Fixture를
// 실제 API 호출로 만든다(docs/020-api/bobfull-api-spec-complete.md 3-x, 4-x, 5-4절).
//
// 상태를 소비하는 예약 준비(CREATE) 시나리오는 반복 호출마다 "아직 아무도 선점하지 않은"
// DiningSession이 필요하다(같은 세션에 두 번째 CREATE는 409 ACTIVE_RESERVATION_ALREADY_EXISTS,
// ReservationPreparationService#validateNoActiveCreate). 이번 Fixture는 그 풀(pool)을
// 충분히 만들어 "같은 데이터 반복 호출로 멱등 응답/409만 측정하는" 위험(Issue #63 "테스트 데이터
// 계약")을 피한다.

import { post, get, parseData } from './helpers.js';
import { signupOwner, login, authHeaders, uniquePhoneNumber } from './auth.js';
import { RUN_ID } from './config.js';

/**
 * Owner 1명 + Restaurant 1개 + SharedTable 1개를 새로 만든다.
 * capacity는 partySize 부하를 고려해 넉넉하게 잡는다(기본 8명).
 */
export function createOwnerRestaurantTable(prefix, capacity) {
    const email = `${prefix}-owner-${RUN_ID}@bobfull.test`;
    const password = 'Perf1234!aA';
    const signupRes = signupOwner(email, password, `${prefix} 사장님`, uniquePhoneNumber(email), `biz-${RUN_ID}-${prefix}`);
    if (signupRes.status !== 201) {
        throw new Error(`Fixture Owner 가입 실패: status=${signupRes.status} body=${signupRes.body}`);
    }
    const ownerToken = login(email, password);
    const ownerHeaders = authHeaders(ownerToken);

    const restaurantRes = post('fixture', '/api/owner/restaurants', {
        name: `${prefix} K6 성능테스트 식당 ${RUN_ID}`,
        address: '서울시 성능테스트로 1',
        category: '한식',
        description: 'K6 성능 테스트용 Fixture 식당(#63)',
        keyword: prefix,
        depositPerPerson: 10000,
    }, ownerHeaders, 'fixture_create_restaurant');
    if (restaurantRes.status !== 201) {
        throw new Error(`Fixture 식당 생성 실패: status=${restaurantRes.status} body=${restaurantRes.body}`);
    }
    const restaurantId = parseData(restaurantRes).restaurantId;

    const tableId = createTable(ownerHeaders, restaurantId, capacity);

    return { ownerHeaders, restaurantId, tableId };
}

export function createTable(ownerHeaders, restaurantId, capacity) {
    const res = post('fixture', `/api/owner/restaurants/${restaurantId}/tables`, {
        capacity: capacity || 8,
    }, ownerHeaders, 'fixture_create_table');
    if (res.status !== 201) {
        throw new Error(`Fixture 테이블 생성 실패: status=${res.status} body=${res.body}`);
    }
    return parseData(res).tableId;
}

/**
 * dates 각각에 startTime~endTime 구간을 intervalMinutes 간격으로 나눠 DiningSession을 만든다.
 * 벌크 응답은 개수만 반환하므로(DiningSessionBulkResponse), 실제 sessionId 목록은
 * 이어서 listAvailableSessions로 다시 조회해야 한다.
 */
export function createSessionsBulk(ownerHeaders, tableId, dates, startTime, endTime, intervalMinutes) {
    const res = post('fixture', `/api/owner/tables/${tableId}/dining-sessions/bulk`, {
        dates, startTime, endTime, intervalMinutes,
    }, ownerHeaders, 'fixture_create_sessions_bulk');
    if (res.status !== 201) {
        throw new Error(`Fixture 회차 생성 실패: status=${res.status} body=${res.body}`);
    }
    return parseData(res).createdSessionCount;
}

/** 아직 예약이 없는(reservationId === null) 회차만 CREATE Fixture 풀로 사용한다. */
export function listAvailableSessionIds(restaurantId, date) {
    const res = get('fixture', `/api/restaurants/${restaurantId}/dining-sessions?date=${date}`, {}, 'fixture_list_sessions');
    if (res.status !== 200) {
        throw new Error(`Fixture 회차 조회 실패: status=${res.status} body=${res.body}`);
    }
    return parseData(res).content
        .filter((session) => session.reservationId === null)
        .map((session) => session.sessionId);
}

/**
 * poolSize 이상의 미사용 DiningSession을 만들어 sessionId 배열로 돌려준다.
 *
 * (PR #208 리뷰 반영) 이전 구현은 테이블 1개 × 최대 30일 × 30분 간격으로 만들어 최대
 * 1,440개까지만 확보할 수 있었다. 예약 준비 API는 호출당 수십ms 수준이라, `constant-vus`
 * 20 VU로 5분(300s)만 돌려도 이론상 수만 건이 소비될 수 있어 실제 Load duration을 버티기
 * 전에 풀이 바닥나 버렸다(hyeonseung-dev 재검토, MAJOR). 이번엔 필요한 테이블 수를 먼저
 * 계산해 여러 테이블에 나눠 만들어, poolSize를 훨씬 크게(FIXTURE_MAX_* 상한 안에서) 확보할
 * 수 있게 했다.
 *
 * 상한(FIXTURE_MAX_TABLES × FIXTURE_MAX_DAYS × 하루 슬롯 수)을 넘는 poolSize는 API를 하나도
 * 호출하지 않고 즉시 실패한다 — 대량의 Fixture를 만들다가 뒤늦게 실패하는 것보다 안전하다.
 *
 * (PR #208 2차 재검토 반영) `slotsPerTablePerDay`를 "하루 24시간 ÷ interval"로 어림잡았더니
 * 실제 서버(`TimeSlotService#toIntervalTimeRanges`, `while (currentStart.isBefore(endTime))`)가
 * 만드는 개수와 1칸 어긋났다(15분 간격이면 24:00을 endTime으로 쓸 수 없어 실제로는 95개인데
 * 96개로 계산). `dailyEndTime()`으로 실제 API에 넘길 endTime과 슬롯 수를 같은 값에서 계산해
 * 다시는 벌어지지 않게 했다.
 */
function dailyEndTime(intervalMinutes) {
    const totalMinutes = Math.floor((24 * 60 - 1) / intervalMinutes) * intervalMinutes;
    const hour = Math.floor(totalMinutes / 60);
    const minute = totalMinutes % 60;
    return {
        slotsPerDay: totalMinutes / intervalMinutes,
        endTime: `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`,
    };
}

export function buildCreateTargetPool(prefix, poolSize, baseDate) {
    const intervalMinutes = Number(__ENV.FIXTURE_INTERVAL_MINUTES || 15);
    const maxDays = Number(__ENV.FIXTURE_MAX_DAYS || 60);
    const maxTables = Number(__ENV.FIXTURE_MAX_TABLES || 30);
    const { slotsPerDay: slotsPerTablePerDay, endTime } = dailyEndTime(intervalMinutes);

    const maxAchievablePool = slotsPerTablePerDay * maxDays * maxTables;
    if (poolSize > maxAchievablePool) {
        throw new Error(
            `요청한 poolSize(${poolSize})가 현재 상한(FIXTURE_MAX_TABLES=${maxTables} × ` +
            `FIXTURE_MAX_DAYS=${maxDays} × 하루 ${slotsPerTablePerDay}회차 = ${maxAchievablePool})을 넘는다. ` +
            `FIXTURE_MAX_TABLES/FIXTURE_MAX_DAYS/FIXTURE_INTERVAL_MINUTES를 조정해 재실행하라.`
        );
    }

    const tablesNeeded = Math.min(maxTables, Math.max(1, Math.ceil(poolSize / (slotsPerTablePerDay * maxDays))));
    const daysNeeded = Math.min(maxDays, Math.max(1, Math.ceil(poolSize / (slotsPerTablePerDay * tablesNeeded))));

    const { ownerHeaders, restaurantId, tableId: firstTableId } = createOwnerRestaurantTable(prefix, 8);
    const tableIds = [firstTableId];
    for (let i = 1; i < tablesNeeded; i += 1) {
        tableIds.push(createTable(ownerHeaders, restaurantId, 8));
    }

    let sessionIds = [];
    for (let dayOffset = 0; dayOffset < daysNeeded && sessionIds.length < poolSize; dayOffset += 1) {
        const date = addDays(baseDate, dayOffset);
        // 이 날짜에 대해 모든 테이블에 회차를 만든 뒤 한 번만 조회한다(테이블별로 따로
        // 조회하면 restaurant 단위 응답에 이미 만든 다른 테이블의 회차가 섞여 중복 집계된다).
        tableIds.forEach((tableId) => {
            createSessionsBulk(ownerHeaders, tableId, [date], '00:00', endTime, intervalMinutes);
        });
        sessionIds = sessionIds.concat(listAvailableSessionIds(restaurantId, date));
    }

    if (sessionIds.length < poolSize) {
        throw new Error(
            `Fixture 회차 풀이 부족하다: 필요=${poolSize} 생성=${sessionIds.length} ` +
            `(테이블 ${tableIds.length}개 × ${daysNeeded}일 사용, 상한 테이블 ${maxTables}/일 ${maxDays})`
        );
    }
    return { ownerHeaders, restaurantId, sessionIds };
}

function addDays(dateStr, days) {
    const d = new Date(`${dateStr}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + days);
    return d.toISOString().slice(0, 10);
}
