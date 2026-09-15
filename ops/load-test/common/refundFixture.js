// Issue #146 — 환불 완료 K6 시나리오 공통 Fixture. #63 Harness(fixture.js/auth.js)를 재사용해
// 회원이 예약을 만들고 결제를 PAID로 확정한 뒤, 취소 요청 직전 상태까지 준비한다.
//
// 결제 확정은 인증된 회원용 `POST /api/payments/{paymentId}/complete`를 그대로 쓴다. 이
// 엔드포인트도 내부적으로 PortOnePaymentReader.read()를 호출하지만, `performance` 프로파일에서는
// PerformanceTestPaymentReader(성능 프로파일 전용 fake Bean)가 실제 PortOne 호출 없이 저장된
// Payment 금액과 그대로 일치시켜 즉시 PAID로 확정해 준다 — 별도 웹훅 서명 없이도 Fixture를
// 준비할 수 있다(웹훅 서명은 측정 대상인 취소·환불 완료 자체에만 쓴다, `portoneWebhook.js`).

import { post, parseData } from './helpers.js';
import { signupMember, login, authHeaders, uniquePhoneNumber } from './auth.js';
import { RUN_ID } from './config.js';

/** poolSize명의 회원을 만들어 로그인 토큰만 돌려준다. */
export function createMemberPool(prefix, poolSize) {
    const tokens = [];
    for (let i = 0; i < poolSize; i += 1) {
        const email = `${prefix}-member-${i}-${RUN_ID}@bobfull.test`;
        const password = 'Perf1234!aA';
        const signupRes = signupMember(email, password, `${prefix}회원${i}`, uniquePhoneNumber(email));
        if (signupRes.status !== 201) {
            throw new Error(`Fixture 회원 가입 실패(i=${i}): status=${signupRes.status} body=${signupRes.body}`);
        }
        tokens.push(login(email, password));
    }
    return tokens;
}

/**
 * type(CREATE|JOIN)·targetId(sessionId|reservationId)에 대해 memberToken 회원이 예약을
 * 준비하고 결제를 즉시 PAID로 확정한다. partySize는 1로 고정한다.
 */
function prepareAndPay(scenarioName, memberToken, type, targetId) {
    const headers = authHeaders(memberToken);
    const prepareRes = post(scenarioName, '/api/reservations/prepare', {
        type,
        targetId,
        partySize: 1,
    }, headers, `fixture_reservation_prepare_${type.toLowerCase()}`);
    if (prepareRes.status !== 200) {
        throw new Error(`Fixture 예약 준비(${type}) 실패: status=${prepareRes.status} body=${prepareRes.body}`);
    }
    const paymentId = parseData(prepareRes).paymentId;

    const completeRes = post(scenarioName, `/api/payments/${paymentId}/complete`, {}, headers, 'fixture_payment_complete');
    if (completeRes.status !== 200) {
        throw new Error(`Fixture 결제 확정 실패: status=${completeRes.status} body=${completeRes.body}`);
    }
    const completion = parseData(completeRes);
    return {
        paymentId,
        reservationId: completion.reservationId,
        participationId: completion.participationId,
        memberToken,
    };
}

/**
 * sessionId 하나에 대해 memberToken 회원이 CREATE 예약을 준비하고 결제를 즉시 PAID로 확정한다.
 * 이 Fixture는 "완료된 예약 1건 + 참여자 1명"만 필요한 시나리오 A 공통 전제에 맞춘다.
 */
export function prepareAndPayReservation(scenarioName, memberToken, sessionId) {
    return prepareAndPay(scenarioName, memberToken, 'CREATE', sessionId);
}

/**
 * sessionId 하나에 memberTokens.length명이 모두 참여하는 예약 1건을 만든다 — 첫 번째 회원이
 * CREATE로 예약을 만들고, 나머지는 그 reservationId로 JOIN한다. 모두 결제를 PAID로 확정한다.
 *
 * 시나리오 B(동일 Reservation 락 경쟁)가 "같은 Reservation을 여러 참여자가 동시에 취소"하는
 * 상황을 재현하려면 먼저 이렇게 참여자가 여러 명인 예약을 만들어 둬야 한다. sessionId가 속한
 * SharedTable capacity가 memberTokens.length 이상이어야 JOIN이 전부 성공한다(기본 capacity 8).
 */
export function prepareGroupReservation(scenarioName, memberTokens, sessionId) {
    if (memberTokens.length < 2) {
        throw new Error(`그룹 예약은 참여자가 2명 이상이어야 한다: 실제 ${memberTokens.length}명`);
    }
    const [creatorToken, ...joinerTokens] = memberTokens;
    const creator = prepareAndPay(scenarioName, creatorToken, 'CREATE', sessionId);
    const participants = [creator];
    joinerTokens.forEach((token) => {
        participants.push(prepareAndPay(scenarioName, token, 'JOIN', creator.reservationId));
    });
    return { reservationId: creator.reservationId, participants };
}
