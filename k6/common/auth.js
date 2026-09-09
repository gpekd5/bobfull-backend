// #63 공통 K6 Harness — 로그인/가입 등 인증 준비를 한 곳에서 관리한다(docs/api/bobfull-api-spec-complete.md 2-3, 2-x절).

import { post, parseData } from './helpers.js';

export function signupMember(email, password, name, phoneNumber) {
    return post('auth', '/api/auth/signup/users', { email, password, name, phoneNumber }, {}, 'auth_signup_member');
}

export function signupOwner(email, password, name, phoneNumber, businessNumber) {
    return post('auth', '/api/auth/signup/owners', { email, password, name, phoneNumber, businessNumber }, {}, 'auth_signup_owner');
}

// 로그인에 실패하면 이후 모든 요청이 401로 새는 대신 setup()에서 바로 실패하도록 예외를 던진다.
export function login(email, password) {
    const res = post('auth', '/api/auth/login', { email, password }, {}, 'auth_login');
    if (res.status !== 200) {
        throw new Error(`login 실패: status=${res.status} body=${res.body}`);
    }
    return parseData(res).accessToken;
}

export function authHeaders(accessToken) {
    return { Authorization: `Bearer ${accessToken}` };
}

// Member.phoneNumber는 DB에 unique 제약이 있다. seed(이메일 등 실행마다 달라지는 값)를 그대로
// 해시해 "010" + 8자리로 만들어, 회원가입 계정마다 겹치지 않게 한다.
export function uniquePhoneNumber(seed) {
    let hash = 0;
    for (let i = 0; i < seed.length; i += 1) {
        hash = (hash * 31 + seed.charCodeAt(i)) % 100000000;
    }
    return `010${String(Math.abs(hash)).padStart(8, '0').slice(-8)}`;
}
