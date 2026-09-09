# ADR 0018: Kafka Broker를 MSK 대신 전용 EC2로 운영

- 상태: `Accepted`
- 작성일: `2026-08-18`
- 관련 Issue·PR: #169, PR #257
- 주요 Evidence: `docs/evidence/v3/169-app-ha/README.md`

## 배경

다중 App EC2 전환 전에는 Spring Boot, Redis, Kafka가 한 App EC2에서 함께 실행되고 있었다. 실제 운영 중 메모리 리소스 경쟁으로 App Health Check·Prometheus·SSM까지 영향을 받은 경험이 있었고, 다중 App 구조로 전환하려면 Redis와 Kafka를 App 인스턴스 밖의 공용 자원으로 분리할 필요가 있었다.

Redis는 인증 상태·Blacklist·Cache·Pub/Sub을 여러 App이 공유해야 하므로 ElastiCache로 분리했다(ADR 0014). Kafka 역시 App별 로컬 Broker를 둘 수 없으므로 공용 Broker가 필요했지만, 현재 프로젝트 규모에서 관리형 MSK를 도입할지 직접 운영할지를 별도로 결정해야 했다.

이 ADR은 **Kafka를 사용할지 여부**를 결정하지 않는다. Outbox + Kafka를 왜 유지하는지는 ADR 0010이 담당하고, 이 문서는 **채택한 Kafka Broker를 어디에 배치·운영할지**만 다룬다.

## 문제

현재 Kafka 사용 범위와 프로젝트 규모에서 다음을 함께 만족해야 했다.

- 여러 App EC2가 동일 Kafka Broker에 접근
- App EC2의 메모리·배포 생명주기와 Kafka 생명주기 분리
- 현재 사용량에 비해 과도한 상시 관리형 비용을 피함
- 필요 시 AI 비동기 기능과 독립적으로 Broker를 운영·중지할 수 있음
- 다만 운영 편의성·Broker HA를 과장하지 않음

## 고려한 대안

### App EC2 내부 Kafka 유지

- 별도 인프라 비용과 구성이 단순하다.
- App 인스턴스의 메모리·재배포·장애와 Broker가 같은 실패 경계를 공유한다.
- 다중 App EC2에서는 어느 App의 Broker를 공용 기준으로 삼을지 경계가 불명확하다.

### Amazon MSK Provisioned

- 관리형 Kafka 운영과 Broker 관리 편의성이 높다.
- Broker 인스턴스와 스토리지에 대한 지속 비용이 발생한다.
- 당시 Kafka 사용 범위가 제한적이어서 현재 규모 대비 과한 선택으로 판단했다.

### Amazon MSK Serverless

- Broker 인스턴스 직접 관리 부담을 줄일 수 있다.
- 저빈도 사용이어도 Cluster/Partition/Data/Storage 과금 구조가 존재한다.
- 현재 제한된 Kafka 사용 범위에서는 비용 대비 이점이 충분하지 않다고 판단했다.

### Kafka 전용 EC2

- App EC2와 Broker 실패·배포 경계를 분리할 수 있다.
- EC2 실행 시간과 EBS 중심으로 비용을 관리하고 필요하지 않은 기간에는 인스턴스를 STOP할 수 있다.
- Kafka 설치·업그레이드·모니터링·장애 대응을 팀이 직접 책임져야 한다.
- 단일 EC2·단일 Broker이면 Kafka HA는 제공하지 않는다.

## 결정

현재 프로젝트에서는 **MSK를 도입하지 않고 Kafka 전용 EC2에서 단일 KRaft Broker를 운영**한다.

```text
App EC2 Blue / Green
        ↓
   Kafka 전용 EC2
   single KRaft broker
```

Kafka Broker는 App EC2 내부 Docker에서 분리하고, Blue/Green 어느 App이 Active이든 동일한 공용 Broker를 사용한다.

결정 당시 Kafka의 주 사용 범위는 AI Moderation 비동기 처리였다. 이후 동일 `ChatMessageCreatedEvent`를 Restaurant Feedback Insight Consumer Group도 재사용할 수 있게 됐지만, Producer/Consumer 역할 추가가 Broker 배치 결정을 바꾸지는 않는다.

## 선택 이유

#169에서 MSK Provisioned, MSK Serverless, Kafka 전용 EC2의 비용 구조와 운영 책임을 비교했다. 현재 규모에서는 관리 편의성과 HA보다 **제한된 사용 범위에 맞는 비용 효율과 App 실행 경계 분리**를 우선했다.

실제 최종 AWS 구성에서도 Kafka는 App EC2와 분리된 전용 EC2에서 실행되며, 단일 KRaft Broker라는 한계를 명시하고 있다.

## 장점

- App EC2와 Kafka Broker의 배포·메모리 생명주기를 분리한다.
- 여러 App EC2가 동일 Broker를 공용으로 사용한다.
- 현재 규모에서 관리형 Kafka의 상시 비용을 피한다.
- 사용하지 않는 기간에는 EC2 STOP으로 컴퓨팅 비용을 제한할 수 있다.

## 단점과 위험

- Kafka 설치·설정·패치·장애 복구를 직접 운영해야 한다.
- 현재 단일 EC2·단일 Broker이므로 Broker 장애 HA를 보장하지 않는다.
- EC2 STOP 중에는 Kafka 기반 Consumer 처리가 진행되지 않는다.
- Kafka의 장기 backlog, Broker 장애 복구, 다중 Broker failover를 실제 HA 환경으로 검증한 것은 아니다.

## 검증 방법

- App EC2에서 Kafka를 제거하고 전용 Kafka EC2에 연결
- Blue/Green App 인스턴스가 동일 Broker를 사용함을 운영 구성에서 확인
- Kafka가 단일 EC2 / 단일 KRaft Broker임을 Architecture·Evidence와 일치시킴

이 ADR은 MSK 대비 성능 우위를 주장하지 않는다. 당시 비용 구조와 현재 사용 범위에 따른 운영 선택을 기록한다.

## 재검토 조건

- Kafka 의존 기능과 트래픽이 크게 증가할 때
- Broker 장애가 서비스 요구 가용성을 만족하지 못할 때
- 다중 Broker·자동 failover·관리형 운영의 가치가 직접 운영 비용보다 커질 때
- 팀이 Kafka patching·monitoring·recovery를 직접 운영하기 어려워질 때
