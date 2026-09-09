# ADR 0011: 다중 인스턴스 채팅 실시간 전파에 Redis Pub/Sub 사용

- 상태: `Accepted`
- 작성일: `2026-08-12`
- 관련 Issue·PR: #170

> 상태 갱신: #170 구현 Evidence와 #169 다중 App EC2 + 공용 ElastiCache Redis 인스턴스 간 전달 검증으로 2026-08-18 Final QA에서 Accepted로 정리했다. 아래 본문은 최초 제안 당시의 문제·대안·검증 계획을 보존한다.

## 배경

Spring STOMP Simple Broker는 현재 애플리케이션 인스턴스의 WebSocket 세션만 안다. ALB 뒤 다중 EC2에서 같은 채팅방 참여자가 서로 다른 인스턴스에 연결되면 Controller의 로컬 STOMP 발행만으로는 원격 세션에 전달할 수 없다.

## 고려한 대안

1. Sticky Session만 유지: 연결 고정일 뿐 다른 참여자가 다른 인스턴스에 연결되는 문제를 해결하지 못한다.
2. Redis Pub/Sub: 공용 채널을 모든 인스턴스가 구독해 각자의 Simple Broker로 전달한다.
3. Redis Streams 또는 실시간 전파용 Transactional Outbox: 재생·재처리 요구가 있으나, 현재 실시간 채팅 목적에는 복잡도가 과도하다.
4. Kafka로 WebSocket 실시간 전달: AI Moderation의 Outbox→Kafka 책임과 실시간 전파 책임을 불필요하게 결합한다.

## 결정

`ChatMessage`와 AI 분석용 `CHAT_MESSAGE_CREATED` Outbox를 기존처럼 한 DB 트랜잭션에 저장한다. 커밋 뒤 Redis Pub/Sub 채널 `bobfull:chat:messages`에 한 번 발행하고, 각 인스턴스의 subscriber가 자신의 `/sub/chat/rooms/{chatRoomId}`에 한 번만 전달한다. Controller의 직접 STOMP 발행은 제거한다.

Redis 발행·구독 실패는 이미 저장된 메시지를 롤백하거나 재발행하지 않는다. DB cursor 조회가 누락 메시지의 공식 복구 경로이며, subscriber는 DB 저장이나 Redis 재발행을 수행하지 않는다.

## 장점

- 여러 인스턴스의 로컬 Simple Broker 세션에 동일 payload를 전달한다.
- DB 영속성과 AI용 Outbox→Kafka의 재처리 계약을 바꾸지 않는다.
- 발행 서버가 로컬 STOMP로 직접 한 번 더 보내지 않게 해 중복 수신을 피한다.

## 단점과 위험

- Redis Pub/Sub은 메시지를 저장하지 않으며, 연결 단절 동안 놓친 메시지를 다시 보내주지 않는다.
- Redis 장애 시 실시간 전달이 실패할 수 있고, 복구는 클라이언트 cursor 조회에 의존한다.
- #169 또는 동등한 2-instance WSS 환경의 A↔B 실측 Evidence가 있어야 최종 Accepted 및 Issue 전체 완료를 판단할 수 있다.

## 검증 방법

단일 인스턴스에서는 커밋 후 발행, 롤백 미발행, payload, subscriber의 단일 local 전달, Redis 실패 격리와 기존 AI Outbox 회귀를 자동 테스트로 확인한다. 다중 인스턴스 WSS/Redis 장애·복구·cursor 복구는 #169 환경에서 별도 Evidence로 확인한다.

## 후속 작업

#170 구현 Evidence와 #169 환경의 다중 인스턴스 Evidence가 기록되어 상태를 `Accepted`로 갱신했다. Redis Pub/Sub이 메시지를 저장·재생하지 않는 한계와 DB cursor 복구 경로는 유지한다.
