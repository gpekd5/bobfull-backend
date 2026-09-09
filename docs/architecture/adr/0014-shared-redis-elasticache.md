# ADR 0014: 다중 App 환경의 공용 Redis를 ElastiCache로 분리

- 상태: `Accepted`
- 작성일: `2026-08-18`
- 관련 Issue: #62, #169, #170
- 주요 Evidence: `docs/evidence/v3/62-search-cache/README.md`, `docs/evidence/v3/169-app-ha/README.md`, `docs/evidence/v3/170-chat-redis-pubsub/README.md`

## 배경

BobFull의 Redis 사용은 Refresh Token 저장소(ADR 0006)에서 시작했고, 이후 Access Token Blacklist, 식당 검색 캐시, 채팅 Redis Pub/Sub으로 범위가 확장됐다.

단일 App EC2 단계에서는 App과 Redis를 같은 EC2에 둘 수 있었지만, ALB 뒤에 App EC2가 2대 이상 존재하면 인스턴스별 Redis를 따로 두는 순간 동일 서비스의 상태가 서버마다 갈라진다.

## 문제

다중 App 환경에서 App-local Redis를 유지하면 다음 문제가 생긴다.

- 로그인/재발급 요청이 다른 App으로 분산될 때 Refresh Token 상태 불일치
- 로그아웃 후 Access Token Blacklist 상태 불일치
- 검색 캐시 namespace와 무효화 상태 불일치
- 같은 채팅방 사용자가 다른 App에 연결될 때 Redis Pub/Sub 채널 분리

App 인스턴스는 교체·증감 가능한 실행 단위여야 하므로, 여러 App이 공유해야 하는 상태를 특정 App EC2 내부 Redis에 묶어둘 수 없다.

## 고려한 대안

1. **App EC2마다 Redis 개별 실행** — 비용과 초기 구성은 단순하지만 App 간 상태가 분리된다.
2. **한 App EC2의 Redis를 다른 App이 공유** — 상태는 공유할 수 있지만 특정 App EC2에 Redis 생명주기가 종속되고 장애 경계가 섞인다.
3. **Redis 전용 EC2 직접 운영** — 공용 Redis는 확보하지만 패치·장애 복구·백업·TLS 등 운영 책임이 커진다.
4. **ElastiCache for Valkey 공용 Redis** — App과 Redis 생명주기를 분리하고 다중 App이 동일 Endpoint를 사용한다.

## 결정

다중 App 환경의 Redis를 App EC2에서 분리하고 **공용 ElastiCache for Valkey**를 사용한다.

```text
App EC2 A ─┐
App EC2 B ─┼→ ElastiCache for Valkey
Green A   ─┤
Green B   ─┘
```

모든 App 인스턴스는 같은 Redis를 사용한다. 현재 공용 Redis의 사용 범위는 다음과 같다.

- Refresh Token
- Access Token Blacklist
- 식당 검색 캐시
- 채팅 Redis Pub/Sub

실제 AWS 구성에서는 ElastiCache Valkey와 in-transit encryption 사용을 확인했다.

## 기존 ADR과의 관계

- **ADR 0006**은 Refresh Token 저장소로 Redis를 선택한 인증 도메인 결정이다.
- **ADR 0011**은 Redis Pub/Sub을 채팅 실시간 전달 방식으로 선택한 메시지 전달 결정이다.
- **ADR 0014**는 다중 App 배포 환경에서 이 Redis 사용처들이 같은 상태를 공유하기 위해 Redis의 **배치·운영 경계 자체를 App EC2 밖의 공용 인프라로 분리한 결정**이다.

따라서 기존 ADR을 대체하지 않고 배포 환경에서의 책임을 보완한다.

## 선택 이유

#169 다중 App 구조에서는 App별 Redis가 분리되면 인증·캐시·채팅 상태가 서로 달라져 서비스 인스턴스 간 일관성을 유지할 수 없다. 공용 Redis를 사용하면 App 내부에 인증·채팅 상태를 직접 들고 있지 않아도 되고, App 교체/Blue-Green 전환 시에도 같은 공유 상태를 사용할 수 있다.

#169 실제 AWS Evidence에서 서로 다른 App EC2가 공용 ElastiCache Redis를 통해 동일 채팅 메시지를 발행·구독하고 양방향 실시간 채팅이 동작함을 확인했다.

## 장점

- App 인스턴스 수와 무관하게 인증·캐시·Pub/Sub 상태가 하나의 공용 Redis를 사용한다.
- App EC2 교체/Blue-Green 배포와 Redis 생명주기를 분리한다.
- Redis를 App JVM/EC2 자원 경쟁에서 분리한다.
- 서로 다른 App EC2 사이의 채팅 전달을 동일 Redis 채널로 처리할 수 있다.

## 단점과 위험

- Redis가 여러 기능의 공용 의존성이므로 장애 영향 범위가 커진다.
- Redis 장애 시 기능마다 처리 방식이 다르다. Access Token Blacklist 조회에 실패하면 요청을 바로 막지 않고 기존 인증 흐름을 계속 진행하지만, Refresh Token 재발급은 Redis 조회에 실패하면 새 토큰을 발급하지 않는다. 채팅 Pub/Sub에서 놓친 메시지는 DB cursor 조회로 다시 가져온다.
- ElastiCache 비용과 네트워크/TLS 설정이 추가된다.
- Redis Pub/Sub은 메시지를 저장해 나중에 다시 보내주는 큐가 아니므로, 공용 ElastiCache로 이전했다고 전달 보장이 생기는 것은 아니다.

## 검증 방법

- 다중 App EC2가 동일 Redis Endpoint를 사용하는지 확인
- Refresh Token/Blacklist/검색 캐시 회귀 검증
- 서로 다른 App EC2 사이 Redis Pub/Sub publish/subscribe 검증
- Blue-Green 전환 뒤에도 동일 Redis 상태 공유 확인

## 재검토 조건

- Redis 장애 영향 때문에 기능별 Redis 분리 또는 별도 cluster가 필요해질 때
- Cache와 인증/실시간 메시징의 자원 사용 패턴이 서로 간섭할 정도로 커질 때
- Redis 다중화·복제·클러스터 구성 요구가 현재 관리형 구성을 넘어설 때
