// #63 공통 K6 Harness — 시나리오마다 반복되는 응답 검증을 공통화한다.

import { check } from 'k6';

export function checkStatus(res, expected, label) {
    return check(res, {
        [`${label} status is ${expected}`]: (r) => r.status === expected,
    });
}

export function checkApiSuccess(res, label) {
    return check(res, {
        [`${label} body.success is true`]: (r) => {
            try {
                return JSON.parse(r.body).success === true;
            } catch (e) {
                return false;
            }
        },
    });
}
