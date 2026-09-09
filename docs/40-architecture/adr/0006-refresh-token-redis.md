# ADR 0006: Refresh Token 저장소로 Redis를 최초 도입

- 상태: `Accepted`
- 작성일: `2026-08-03`
- 관련 Issue: `#43`, `#125`

## 배경

V1 로그인은 서명·만료만 검증하는 무상태 Access Token만 발급한다. 서버는 어떤 토큰 상태도 저장하지 않아 로그아웃·재발급·탈취 대응 자체가 불가능하다. `#43` 스파이크는 이 문제를 프로젝트 전체의 Redis 적용 후보 중 하나로 검토했다.

## 문제

Access Token만으로는 로그아웃 시 즉시 무효화가 안 되고, 재발급을 위한 별도 자격 증명도 없다. Refresh Token을 도입해도 순수 서명 기반(JWT)이면 여전히 폐기가 불가능해 문제가 반복된다. 폐기 가능한 Refresh Token은 서버 측에 검증 가능한 상태가 있어야 하며, 이 상태를 MySQL과 Redis 중 어디에 둘지 결정해야 한다.

## 고려한 대안

- 서명된 Refresh Token(두 번째 JWT)만 발급하고 서버 상태를 두지 않는다.
- MySQL에 Refresh Token 테이블을 만들어 관리한다.
- Redis에 `refreshToken → memberId`만 저장하고 TTL로 자동 만료시킨다.

## 결정

Redis에 `refreshToken(불투명 문자열) → memberId`를 저장하고, 회원당 Refresh Token은 항상 1건만 유지한다(단일 세션). 로그인·재발급마다 기존 키를 삭제하고 새 키를 발급하며(회전), 로그아웃은 인증된 memberId 기준으로 그 회원의 Refresh Token 키를 즉시 삭제한다. Refresh Token 자체는 서명된 JWT가 아니라 `SecureRandom` 기반 불투명 토큰이다 — 유효성 판단 기준이 서명이 아니라 Redis 존재 여부이기 때문이다.

재발급은 Redis 조회 실패(연결 장애 등)를 포함한 모든 무효 상황을 동일하게 `401 UNAUTHORIZED`로 거부한다. Redis에서 Refresh Token을 확인하지 못하면 새 Access·Refresh Token을 내주지 않는다. 로그아웃의 Redis 실패는 감추지 않고 그대로 전파해 시스템 오류로 남긴다. Access Token Blacklist(모든 인증 요청마다 Redis 조회)는 이번 결정에 포함하지 않는다 — Refresh Token 저장소는 재발급·로그아웃 두 엔드포인트에만 관여해 영향 범위가 작지만, Blacklist는 모든 인증 경로에 Redis를 필수 의존성으로 만들기 때문이다. Refresh Token 재사용 탐지(탈취된 토큰이 다시 쓰이면 그 회원의 전체 세션을 무효화하는 방식)도 이번 범위에 넣지 않는다.

## 선택 이유

Refresh Token·Blacklist 후보는 모두 "특정 시점 이후 자동 소멸해야 하는 key-value" 형태다. Redis는 TTL을 네이티브로 지원해 만료 삭제를 위한 별도 스케줄러가 필요 없고, 매 요청 조회 비용이 작다. MySQL 테이블로 관리하면 `PaymentExpirationScheduler`와 같은 폴링 잡을 추가로 만들어야 한다.

## 장점

- 로그아웃·재발급이 실제로 폐기 가능해진다(V1의 구조적 한계 해소).
- TTL 자동 만료로 별도 정리 배치가 필요 없다.
- Blacklist를 제외해 기존 인증 경로(모든 요청)에는 Redis 의존성을 추가하지 않는다.

## 단점과 위험

- Redis가 이 프로젝트의 첫 인프라 의존성이 되어, Redis 장애가 로그인 유지(재발급)에 영향을 준다. 이 경우 재발급은 새 토큰을 발급하지 않고 401로 거부된다.
- Refresh Token 재사용 탐지가 없어, 탈취된 토큰이 사용되기 전까지는 소유권 경쟁으로만 제한되고 탈취 자체를 알아내지는 못한다.
- 단일 세션 모델이라 다중 기기 동시 로그인 시 나중 로그인이 이전 세션의 Refresh Token을 무효화한다(다중 기기 세션 관리는 범위 밖).

## 검증 방법

로그인 → 재발급(회전) → 로그아웃 → 재발급 거부 흐름과, TTL 만료 후 자동 삭제, Redis 연결 실패 시 재발급 거부를 실제 Redis(Docker)로 검증한다. 상세 계약은 [project-context.md](../../10-product/project-context.md), [API 명세](../../20-api/bobfull-api-spec-complete.md)를 따른다.

## 재검토 조건

- ADMIN 역할처럼 탈취 시 위험도가 높은 대상이 도입돼 재사용 탐지가 필요해질 때
- 다중 기기 동시 세션 지원이 실제로 필요해질 때
- Access Token 즉시 폐기(Blacklist)가 실제로 필요해질 때
- Redis 장애 빈도·영향이 커서 재발급을 401로 거부하는 정책 자체를 재검토해야 할 때

## 후속 결정: Access Token Blacklist 도입 (Issue #186, 2026-08-07)

위 재검토 조건 중 "Access Token 즉시 폐기(Blacklist)가 실제로 필요해질 때"가 충족되어 Blacklist를 도입한다.

- Access Token 만료를 3600초에서 1800초로 줄이고, 발급 시 `jti` Claim을 부여한다.
- 로그아웃 시 그 Access Token의 `jti`를 Redis에 등록한다. 키는 Refresh Token과 동일한 소문자·콜론 관례를 따라 `auth:access-token-blacklist:{jti}`로 하고(Issue 본문의 대문자·하이픈 예시와는 다름), TTL은 등록 시점 기준 남은 유효시간으로 설정한다.
- 인증 필터는 서명·만료 검증 통과 후 매 요청마다 이 Blacklist를 조회한다. 이 조회에서 Redis 예외가 발생해도 요청은 막지 않고 인증을 허용한다. Refresh Token 재발급은 `/api/auth/reissue` 단일 엔드포인트에만 영향을 주지만, Blacklist 조회는 인증 필터를 거치는 모든 요청에 실행되어 Redis 장애가 곧 전체 API 장애로 번질 수 있기 때문이다. 이때 노출되는 위험은 직전 로그아웃한 토큰이 만료 시각까지 잠시 재사용되는 좁은 범위로 한정한다. 로그아웃 자체(Blacklist 등록·Refresh Token 삭제)의 Redis 실패는 감추지 않고 그대로 전파한다. 이 결정은 매 요청 조회에만 적용된다.
- 재발급(`/api/auth/reissue`)이 교체 대상 기존 Access Token을 Blacklist에 등록하는 것은 이번 결정 범위에 포함하지 않는다 — 기존 Access Token은 재발급 후에도 자연 만료까지 유효하다(§2-7 계약 유지).
