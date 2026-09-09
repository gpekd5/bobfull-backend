# 🍚 BobFull Backend

> **제주 혼자 여행객을 위한 좌석 단위 합석 예약 플랫폼**

BobFull(밥풀)은 혼자 방문하기 부담스러운 식당에서 사용자가 **필요한 좌석만 예약하고**, 같은 시간대의 여행객과 자연스럽게 함께 식사할 수 있도록 연결하는 합석 예약 서비스입니다.

[🏠 Project Home](https://github.com/bobfull-project) · [📚 Technical Docs](https://github.com/bobfull-project/bobfull-docs) · [🖥️ Frontend](https://github.com/bobfull-project/bobfull-frontend) · [🔬 System Flow](https://bobfull-project.github.io/bobfull-docs/100-learning/system-flow/v3/operations-system-flow/)

```text
식당·회차 탐색 → 모임 생성·참여 → 예약금 결제 → 합석 확정 → 참여자 채팅
```

---

## 01 프로젝트 소개

| 항목 | 내용 |
|---|---|
| 프로젝트 | BobFull(밥풀) |
| 팀 | 밥조 · 4명 |
| 기간 | 2026.07.21 ~ 2026.08.24 |
| 핵심 타깃 | 제주에서 혼자 여행하는 사용자 |
| 핵심 가치 | 좌석 단위 합석 모집 · 예약금 결제 · 취소/환불/노쇼 · 실시간 채팅 |
| 사용자 역할 | `MEMBER` · `OWNER` · `ADMIN` |

### 문제 정의

| 대상 | 문제 | BobFull의 접근 |
|---|---|---|
| 사용자 | 2인 이상 주문 또는 테이블 단위 운영으로 혼자 이용하기 어려움 | 좌석 단위로 합석을 모집하고 필요한 인원만 예약 |
| 사용자 | 함께 식사할 사람을 직접 찾아야 함 | 같은 회차 참여자를 예약 후 채팅으로 연결 |
| 식당 | 테이블 단위 운영으로 빈 좌석이 발생함 | 개인 단위 합석 수요를 남은 좌석과 연결 |
| 식당 | 취소·노쇼와 예약금 관리가 복잡함 | 예약·결제·환불·노쇼·지급 예정 금액을 하나의 흐름으로 관리 |

### 사용자 이용 흐름

1. `OWNER`가 식당, 합석 테이블, 예약 가능한 회차를 등록합니다.
2. `MEMBER`가 식당·회차·인원을 선택하면 좌석을 임시 선점하고 `Payment READY`를 생성합니다.
3. PortOne 결제 후 서버가 결제 상태·금액·통화를 다시 검증하고 예약/참여 상태를 확정합니다.
4. 모집 상태가 `OPEN`이고 잔여 정원이 있으면 다른 사용자가 추가 참여할 수 있습니다.
5. 예약이 확정되면 참여자는 WebSocket/STOMP 기반 그룹 채팅을 이용합니다.
6. 모집 마감·취소·노쇼 상황에 따라 예약 및 환불 상태를 처리합니다.

상세 상태 전이와 정책은 [`project-context.md`](docs/010-product/project-context.md)와 [`bobfull-api-spec-complete.md`](docs/020-api/bobfull-api-spec-complete.md)를 기준으로 합니다.

---

## 02 팀 소개

| 이름 | 프로젝트 역할 | 주요 담당 |
|---|---|---|
| **김현승** | **팀장** | 예약금 결제·환불·지급 예정 예약금 / AI·채팅 |
| **김홍기** | **부팀장** | 합석 테이블·예약 시간·검색 / AWS·CI/CD·Blue-Green·로그·모니터링 |
| **배지현** | **발표** | 예약·참여·좌석 재고·동시성 / 프론트엔드·Kafka |
| **정용태** | **서기** | 회원·인증·사장님·식당·관리자 / 캐시·조회 성능·k6 |

---

## 03 핵심 기능

| 영역 | 주요 기능 |
|---|---|
| 식당·회차 검색 | QueryDSL 기반 동적 검색, 날짜/시간/카테고리/키워드 조건, 반복 검색 Redis Cache |
| 예약·좌석 | 최초 예약/추가 참여, `partySize` 단위 처리, READY Payment 기반 임시 선점, 동시성 제어 |
| 결제·환불 | PortOne 결제 검증, Webhook, 멱등 처리, 환불·Reconciliation, 지급 예정 예약금 조회 |
| 실시간 채팅 | WebSocket/STOMP, DB 메시지 저장, Redis Pub/Sub 기반 다중 App 실시간 전달 |
| 이벤트 처리 | Transactional Outbox, Async Executor, Kafka Consumer Group, Retry/DLT |
| AI | OpenAI 기반 채팅 Moderation, Rule Fast Path, 분할 메시지 Context 보완, 식당 피드백 Insight |
| 운영 | ADMIN 재처리·운영 조회, Actuator, Prometheus/Grafana, 구조화 로그와 장애 관측 |

---

## 04 시스템 아키텍처

최종 배포·검증 기준의 BobFull 전체 시스템 구성입니다.

<img width="1642" height="952" alt="BobFull System Architecture" src="https://github.com/user-attachments/assets/5a1371a7-7486-4fca-8a8a-43f8f1c44995" />

- **Application**: ALB 뒤 Blue/Green Target Group을 두고, 각 환경은 App EC2 2대로 구성했습니다.
- **Database**: Amazon RDS for MySQL을 영속 데이터의 기준 저장소로 사용했습니다.
- **Cache / Realtime**: ElastiCache for Valkey를 인증 상태, 검색 Cache, 다중 App 채팅 Pub/Sub에 사용했습니다.
- **Messaging**: Kafka는 전용 EC2의 Single KRaft Broker로 운영하며 AI 후속 처리 경계로 사용했습니다.
- **Monitoring**: 별도 Monitoring EC2에서 Prometheus + Grafana를 운영하고 CloudWatch Logs와 Slack Alert를 연결했습니다.
- **CI/CD**: GitHub Actions → ECR → SSM 기반 Blue-Green 배포 후 Health Check와 외부 요청 검증을 거쳐 ALB 트래픽을 전환했습니다.

> Blue-Green과 다중 App 검증은 **Application Layer** 기준입니다. 최종 구성의 RDS는 **Single-AZ**, Kafka는 **단일 Broker**이므로 전체 계층의 HA로 확대해 표현하지 않습니다.

[▶ Technical Docs — System Architecture](https://github.com/bobfull-project/bobfull-docs/blob/main/architecture/system-architecture.md)

---

## 05 기술 스택

아래 버전은 프로젝트의 **실제 빌드 설정과 최종 배포 기록**을 기준으로 정리했습니다.

| 영역 | 기술 / 버전 |
|---|---|
| Backend | Java **17** · Spring Boot **4.1.0** · Spring AI **2.0.0** · QueryDSL **5.1.0** · Gradle **9.5.1** |
| Web / Auth | Spring MVC · Spring Security · JWT **0.12.6** · Validation · WebSocket/STOMP |
| Data | MySQL **8.4** (Local) · Amazon RDS for MySQL **8.0.46 / db.t4g.micro** (최종 배포) · Redis **7-alpine** (Local) · Amazon ElastiCache for Valkey **9.1.0 / cache.t4g.micro** (최종 배포) |
| Messaging | Apache Kafka **3.9.0** · KRaft Broker on EC2 **t3.small** · Transactional Outbox |
| AI / External | OpenAI **gpt-4o-mini** · PortOne Server SDK **0.24.0** · SMTP |
| Frontend | React **19.2.0** · TypeScript **5.9.3** · Vite **7.3.1** · Tailwind CSS **3.4.17** |
| Infra / CI·CD | AWS EC2 **t3.small** · ALB · ACM · RDS · ElastiCache · S3 · CloudFront · Route 53 · Lambda · ECR · SSM / Parameter Store · Docker **25.0.14** · GitHub Actions · Blue-Green Deployment |
| Monitoring | Prometheus **3.13.2** · Grafana **13.0.2** · Grafana Alerting · AWS CloudWatch · Slack Alert |
| Test / Load | JUnit 5 · Testcontainers · k6 |

Backend 의존성은 [`build.gradle`](build.gradle), Gradle 버전은 [`gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties), 로컬 인프라는 [`docker-compose.yml`](docker-compose.yml)을 기준으로 합니다.

---

## 06 대표 Case Studies & 실측 결과

README에서는 대표 사례와 결과만 요약합니다. 문제 정의부터 실험 과정, 판단 근거, 한계까지의 상세 기록은 **bobfull-docs**와 Backend Evidence에 분리해 두었습니다.

### Representative Case Studies

| ID | 대표 사례 | 핵심 내용 |
|---|---|---|
| [CS-01](https://github.com/bobfull-project/bobfull-docs/blob/main/case-studies/cs-01-spof-to-multi-az-blue-green.md) | 단일 EC2 SPOF에서 Multi-AZ Blue-Green까지 | 단일 App 자원 경쟁 장애를 계기로 Redis/Kafka를 분리하고 App 계층 다중화·Blue-Green 배포까지 확장 |
| [CS-02](https://github.com/bobfull-project/bobfull-docs/blob/main/case-studies/cs-02-query-index-to-settlement-optimization.md) | 조회 인덱스 부재에서 정산 조회 병목 해소까지 | 실행 계획과 부하 측정으로 Query·Index 병목을 분리하고 실제 수치로 개선 효과 검증 |
| [CS-03](https://github.com/bobfull-project/bobfull-docs/blob/main/case-studies/cs-03-transaction-and-followup-failure-boundary.md) | 핵심 거래와 후속 작업의 실패 경계 | 예약·결제 핵심 거래와 ChatRoom·Email·AI 후속 작업의 실패 범위를 분리 |
| [CS-04](https://github.com/bobfull-project/bobfull-docs/blob/main/case-studies/cs-04-ai-moderation-optimization.md) | AI Moderation 호출·우회 검수 개선 | Rule Fast Path와 Context 보완으로 불필요한 LLM 호출과 분할 메시지 검수 우회 문제를 보완 |
| [CS-05](https://github.com/bobfull-project/bobfull-docs/blob/main/case-studies/cs-05-outbox-async-vs-kafka.md) | Outbox + Async vs Kafka | 같은 Outbox 조건에서 직접 비교하고 속도가 아닌 운영·복구·격리 경계를 Kafka 유지 근거로 판단 |
| [CS-06](https://github.com/bobfull-project/bobfull-docs/blob/main/case-studies/cs-06-post-payment-processing-strategy.md) | AFTER_COMMIT / Outbox / Kafka 선택 기준 | 결제 확정 이후 후속 기능마다 필요한 신뢰성·실패 격리를 기준으로 처리 방식을 구분 |

### 대표 실측 결과

| 검증 | 실측 결과 | 판단 |
|---|---|---|
| 인기 회차 핵심 조회 경로 | 쿼리 `83 → 7`, 고부하 단계 p95 `13.14s → 1.34s` | 반복 쿼리를 배치 조회로 줄여 병목 임계점을 뒤로 이동 |
| 정산 조회 | p95 `6.5s → 30.32ms`, Hikari 대기 `92 → 0` | Batch/Snapshot 대신 Query·Index 개선 유지 |
| 반복 식당 검색 Cache | p95 `43ms → 14ms`, DB Query `2 → 0` | 정합성 영향이 낮은 반복 검색에 제한 적용, Redis 장애 시 DB 조회로 Fail-open |
| Kafka AI Partition Key | 전체 처리 완료 `15.616s → 7.271s`, 활성 Partition `1 → 3` | 방 순서 계약이 없는 AI 검수는 `messageId` 기반 분산으로 병렬성 확보 |
| Blue-Green 배포 | 외부 요청 `2,787 / 2,787` HTTP 200, 실패 `0`, 관측 다운타임 `0s` | Application Layer 트래픽 전환 검증 |
| Outbox + Async / Kafka | Async `5.394s`, Kafka `7.210s`, 양쪽 유실/중복 `0 / 0` | Kafka는 속도가 아니라 Broker backlog·Consumer Group·Retry/DLT·운영 경계 때문에 AI에 한정 유지 |

전체 측정 조건과 한계는 [`V3 Final Claim Matrix`](docs/110-records/evidence/v3/final-claim-matrix.md)와 각 Evidence 문서를 기준으로 합니다.

---

## 07 AI-Native 개발 방식

BobFull은 AI를 요구사항 정리, 구현 보조, 테스트·리뷰 체크리스트 생성, 문서 초안 작성에 활용했지만 **AI의 제안을 최종 결정으로 사용하지 않았습니다.**

```text
Issue → AI Implementation → Human Review → PR Checklist → Feedback → Merge
```

최종 의도, 비즈니스 정책, 데이터 정합성, 권한·보안, 트랜잭션 경계, 성능 수치는 사람이 코드와 Evidence를 기준으로 검증했습니다.

[▶ AI Workflow](docs/060-ai/ai-workflow.md)

---

## 08 Technical Documentation

상세 기술 문서는 별도 **[bobfull-docs](https://github.com/bobfull-project/bobfull-docs)** 저장소를 중심으로 탐색할 수 있습니다. Backend README는 프로젝트의 입구와 요약 역할만 담당합니다.

| 문서 | 역할 |
|---|---|
| [System Architecture](https://github.com/bobfull-project/bobfull-docs/blob/main/architecture/system-architecture.md) | 최종 운영 구조와 책임 경계 |
| [Case Studies](https://github.com/bobfull-project/bobfull-docs/tree/main/case-studies) | 최종 발표 기준으로 정제한 대표 문제 해결 사례 |
| [ADR](https://github.com/bobfull-project/bobfull-docs/tree/main/adr) | 주요 Architecture Decision Record 탐색 |
| [Technical Decisions](https://github.com/bobfull-project/bobfull-docs/tree/main/decisions) | 도메인·정책·구현 단위 의사결정 |
| [Troubleshooting](https://github.com/bobfull-project/bobfull-docs/tree/main/troubleshooting) | 개별 장애·버그·정합성 문제 해결 기록 |
| [Performance](https://github.com/bobfull-project/bobfull-docs/tree/main/performance) | 실제 측정 기반 성능 결과 |
| [Engineering Records](https://github.com/bobfull-project/bobfull-docs/tree/main/engineering-records) | 인프라·배포·모니터링의 발전 과정 |
| [System Flow](https://bobfull-project.github.io/bobfull-docs/100-learning/system-flow/v3/operations-system-flow/) | 실제 코드/Evidence 기반 핵심 Backend 흐름 시뮬레이션 |

### Backend Source of Truth

구현 세부와 포트폴리오 문서가 충돌할 경우 최신 Backend 코드와 아래 문서를 우선합니다.

| 문서 | 기준 |
|---|---|
| [`project-context.md`](docs/010-product/project-context.md) | 서비스 정책·역할·상태 기준 |
| [`bobfull-api-spec-complete.md`](docs/020-api/bobfull-api-spec-complete.md) | HTTP API 계약 |
| [`erd.md`](docs/030-data/erd.md) | 데이터 모델 |
| [`architecture.md`](docs/040-architecture/architecture.md) | 논리 아키텍처·책임 경계 |
| [`domain-dependencies.md`](docs/040-architecture/domain-dependencies.md) | 도메인 및 패키지 의존 관계 |
| [`docs/adr`](docs/040-architecture/adr/README.md) | Backend 공식 번호형 ADR |
| [`final-claim-matrix.md`](docs/110-records/evidence/v3/final-claim-matrix.md) | 구현·검증·실측 주장과 Evidence |

협업 규칙, 코드/테스트 Convention, AI 작업 가이드는 Backend `docs/`에 유지하되 README의 핵심 탐색 경로와 분리합니다.

---

## 09 Local Development

### Requirements

- Java 17
- Docker / Docker Compose

### 1) 로컬 설정 준비

```bash
cp .env.example .env
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

`.env`의 DB/JWT 및 필요한 외부 연동 값을 로컬 환경에 맞게 설정합니다. 실제 비밀값은 커밋하지 않습니다.

### 2) 로컬 인프라 실행

```bash
docker compose up -d mysql redis kafka
```

MySQL **8.4**, Redis **7-alpine**, Kafka **3.9.0**을 Docker로 실행하고 Spring Boot 애플리케이션은 IDE 또는 Gradle Wrapper로 직접 실행할 수 있습니다.

### 3) 애플리케이션 실행

macOS / Linux:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Windows PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE="local"
.\gradlew.bat bootRun
```

전체 애플리케이션까지 Docker로 검증하려면:

```bash
docker compose --profile app up --build -d
```

---

### 더 자세히 보기

- [BobFull Technical Docs](https://github.com/bobfull-project/bobfull-docs)
- [Backend ADR](docs/040-architecture/adr/README.md)
- [Backend Evidence](docs/110-records/evidence/v3/README.md)
- [System Flow](https://bobfull-project.github.io/bobfull-docs/100-learning/system-flow/v3/operations-system-flow/)
