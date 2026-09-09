# ADR 운영 기준

## 1. 목적

ADR(Architecture Decision Record)은 여러 대안을 비교한 뒤 프로젝트에 영향을 주는 기술·구조 결정을 기록하는 문서다. 이미 확정된 정책을 반복하거나 아직 결정하지 않은 후보 구조를 미리 고정하는 문서가 아니다.

## 2. 작성 대상과 비대상

다음처럼 기준 문서의 변경 또는 별도 합의가 필요한 선택은 ADR로 기록한다.

- 외부 시스템·저장소·메시징·캐시 도입 또는 교체
- 데이터 정합성에 영향을 주는 트랜잭션·동시성 방식 선택
- 운영·배포·관찰 방식의 중요한 변경
- 도메인 간 책임이나 장기 유지보수 비용에 큰 영향을 주는 구조 선택
- 실제 측정 뒤 기술을 도입하지 않기로 한 장기 구조 판단
- 자동 복구와 Human 운영 사이의 장기 실패 처리 경계를 정하는 정책

다음은 ADR 대상이 아니다.

- API 명세·ERD·프로젝트 컨텍스트에 이미 확정된 내용을 다시 설명하는 경우
- 단일 Issue 안의 국소적인 구현·명명·포맷 변경
- 근거와 결정이 없는 기술 후보 나열
- 실제 선택 전의 빈 ADR 파일 사전 생성
- K6 Harness, 단일 Query 튜닝, 일회성 Bug Fix처럼 장기 아키텍처 경계를 바꾸지 않는 작업

## 3. 파일명과 상태

- 파일명은 `NNNN-간결한-kebab-case-제목.md` 형식을 사용한다. 예: `0001-payment-webhook-idempotency.md`
- 번호는 `docs/040-architecture/adr/` 안에서 순차 증가한다.
- 상태값은 `Proposed`, `Accepted`, `Superseded`, `Rejected`를 사용한다.
- `Superseded` 문서는 삭제하지 않고, 대체한 ADR 번호·링크를 남긴다.
- 기술 자체를 측정 후 미도입한 경우에도 **의사결정 문서가 확정된 것**이라면 ADR 상태는 `Accepted`로 두고 본문에 `MEASURED_AND_REJECTED` 등 결정 유형을 명시할 수 있다.

## 4. 작성·검토·변경 절차

1. 관련 Issue에서 해결할 문제, 제약, 대안을 확인한다.
2. 최신 `develop`, 실제 배포 구성, Evidence와 충돌 여부를 검토한다.
3. ADR 초안을 작성하고 해당 도메인 담당자가 사실관계·누락된 대안·검증 근거를 리뷰한다.
4. 리뷰 반영 뒤 결정된 ADR을 `Accepted`로 기록하고 구현·Evidence 링크를 연결한다.
5. 이후 결정이 바뀌면 기존 문서의 이력을 지우지 말고 필요 시 새 ADR을 작성해 `Superseded` 관계를 남긴다.

## 5. 최소 템플릿

```md
# ADR NNNN: 제목

- 상태: `Proposed | Accepted | Superseded | Rejected`
- 작성일: `YYYY-MM-DD`
- 관련 Issue·PR: #번호, PR #번호

## 배경

## 문제

## 고려한 대안

## 결정

## 선택 이유

## 장점

## 단점과 위험

## 검증 방법

## 재검토 조건 또는 후속 작업
```

## 6. 최종 기준

이 디렉터리는 운영 기준과 템플릿을 제공한다. 아직 결정하지 않은 배포·AWS·구체적인 락·트랜잭션 방식은 미리 ADR로 만들지 않는다.

ADR 내용과 과거 설계가 충돌할 경우 다음 순서를 우선한다.

```text
1. 최신 develop 실제 코드 / 설정
2. 최종 배포 환경의 실제 구성
3. Evidence 원본
4. 자동 테스트 / 실측 결과
5. ADR / Architecture / README
6. 과거 Issue / 설계안
```

성능·복구 수치는 반드시 해당 Evidence의 조건과 한계를 함께 따른다. 측정 후 미채택한 기술을 실제 적용 기술처럼 표현하지 않는다.

## 7. 현재 적용 범위 핵심

- Redis는 Refresh Token·Access Token Blacklist(ADR 0006), 검색 캐시(#62), 채팅 Pub/Sub(ADR 0011)에 사용하며, 다중 App 환경에서는 공용 ElastiCache for Valkey로 분리한다(ADR 0014).
- Kafka는 AI Moderation과 같은 `ChatMessageCreatedEvent`를 별도 Consumer가 처리해야 할 때 사용한다(ADR 0010). Kafka를 채팅 실시간 전달이나 결제·환불 재시도에 범용 적용하지 않는다.
- Kafka Broker는 현재 MSK가 아니라 App EC2와 분리된 전용 EC2의 단일 KRaft Broker로 운영한다(ADR 0018). 이는 비용·운영 범위 선택이며 Kafka Broker 다중화를 완료했다는 뜻이 아니다.
- Backend 공식 Public Endpoint는 Route 53 → ALB + ACM HTTPS → App EC2 구조다(ADR 0012).
- App 배포는 ALB Target Group 기반 Blue-Green을 사용한다(ADR 0013). Blue-Green은 배포 안전성 전략이고, 실행 중 App 장애 우회는 `ALB + Active App 2대`가 담당한다.
- Traffic Auto Scaling은 실제 측정 결과 현재 범위에서 미도입하고 Active App EC2 2대를 유지한다(ADR 0015).
- Blue-Green이 같은 RDS를 공유하므로 Production은 `ddl-auto=validate`, Rollback Window는 additive schema change 우선 정책을 사용한다(ADR 0016).
- AI Kafka Consumer는 별도 Worker/MSA로 분리하지 않고 현재 통합 실행을 유지한다(ADR 0017).
- 환불 정합성 재조정은 무한 자동 재시도가 아니라 `max-age`가 있는 유한 자동화로 운영하고, 장기 미해결 건은 Human 확인 경계로 넘긴다(ADR 0019).

## 8. 현재 ADR

| ADR | 결정 | 상태 |
|---|---|---|
| [0001](0001-reservation-seat-consistency.md) | 예약 좌석 정합성과 READY Payment 임시 선점 | `Accepted` |
| [0002](0002-payment-completion-idempotency.md) | 결제 완료 API·PortOne 웹훅 멱등성 경계 | `Accepted` |
| [0003](0003-utc-instant-and-clock.md) | UTC Instant 저장과 Clock 주입 | `Accepted` |
| [0004](0004-use-java-17.md) | Java 17 프로젝트 기준 | `Accepted` |
| [0005](0005-domain-boundary-dependency-policy.md) | 도메인 간 의존 경계와 조회 조합 원칙 | `Accepted` |
| [0006](0006-refresh-token-redis.md) | Refresh Token 저장소로 Redis 최초 도입 | `Accepted` |
| [0007](0007-s3-presigned-restaurant-image-validation.md) | S3 Presigned URL 이미지 검증 구조 | `Accepted` |
| [0008](0008-chat-room-transactional-outbox.md) | ChatRoom 생성 의도의 Transactional Outbox | `Accepted` |
| [0009](0009-ai-moderation-provider-and-model-selection.md) | AI Moderation Provider·모델 선택 | `Accepted` |
| [0010](0010-chat-message-outbox-kafka-pipeline.md) | AI Moderation Outbox + Kafka 운영 경계 | `Accepted` |
| [0011](0011-chat-redis-pubsub.md) | 다중 인스턴스 채팅 Redis Pub/Sub | `Accepted` |
| [0012](0012-backend-public-endpoint-alb-https.md) | Backend Public Endpoint ALB + HTTPS | `Accepted` |
| [0013](0013-blue-green-deployment.md) | ALB 기반 Blue-Green 배포 | `Accepted` |
| [0014](0014-shared-redis-elasticache.md) | 다중 App 공용 Redis / ElastiCache | `Accepted` |
| [0015](0015-no-app-auto-scaling.md) | 측정 결과 App Auto Scaling 미도입 | `Accepted` (`MEASURED_AND_REJECTED`) |
| [0016](0016-blue-green-db-schema-compatibility.md) | Blue-Green DB Schema 호환 전략 | `Accepted` |
| [0017](0017-keep-ai-consumer-integrated.md) | AI Consumer Worker/MSA 미분리 | `Accepted` (`MEASURED_AND_REJECTED`) |
| [0018](0018-kafka-dedicated-ec2-over-msk.md) | MSK 미도입 / Kafka 전용 EC2 운영 | `Accepted` |
| [0019](0019-bounded-refund-reconciliation.md) | 환불 정합성 재조정 상한과 Human 확인 경계 | `Accepted` |

## 9. 2026-08-18 최종 보강·담당 리뷰

Issue #285와 Draft PR #286에서 기존 ADR 및 신규 후보를 최신 구현/Evidence와 대조하고 담당자 리뷰를 반영했다.

- `0001`: 예약 담당 리뷰에서 최신 락 순서·REPEATABLE_READ 첫 Lock Query·취소/환불 경계와 일치 확인
- `0010`: #274 동일 Transactional Outbox 조건 비교와 #258 `messageId` key 최종 결정을 재확인. Kafka를 속도 또는 작업 유실을 막는 유일한 근거로 표현하지 않음
- `0012~0016`: 인프라 담당 리뷰에서 실제 AWS/Evidence와 핵심 사실관계 일치 확인
- `0013`: 후속 #191 Evidence를 추가해 Inactive STOP·Prometheus Target UP·STOP guardrail의 최종 기준을 보강하고, Blue-Green 배포 전략과 실행 중 App 장애 우회를 담당하는 `ALB + Active App 2대` 구성을 명확히 분리
- `0014`: 식당/검색 담당 리뷰에서 #62 검색 Cache의 실제 범위와 공용 Redis 서술이 일치함을 확인. #61/#235 Query·Index 튜닝은 ADR로 승격하지 않음
- `0017`: #192 Human 결정과 통합 실행 유지 결론 일치 확인
- `0018`: 인프라 리뷰에서 제안된 `MSK 미도입 / Kafka 전용 EC2` 운영 배치 결정을 #169 근거로 별도 기록
- `0019`: 예약/환불 리뷰에서 제안된 #272의 장기 자동 재조정 종료 정책을 단순 설정값이 아니라 `자동 복구 → Human 확인` 실패 처리 경계로 판단해 별도 기록

Prometheus/Grafana Monitoring EC2, Parameter Store/OIDC, CI 시간 최적화, RDS Single-AZ 자체는 현재 근거 수준에서 별도 ADR로 추가하지 않는다. RDS Single-AZ는 현재 한계로 기록하되 충분한 대안 비교 후 확정한 장기 선택으로 확대하지 않는다.

신규 ADR 수 자체가 목적이 아니라 **현재 구현·운영에서 실제로 유지해야 할 장기 결정과 미도입 판단을 재현 가능한 근거와 함께 남기는 것**을 기준으로 한다.
