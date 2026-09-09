# ADR 0012: Backend Public Endpoint를 ALB + HTTPS로 구성

- 상태: `Accepted`
- 작성일: `2026-08-18`
- 관련 Issue: #206
- 주요 Evidence: `docs/evidence/v3/206-backend-ingress-https/README.md`

## 배경

초기 BobFull Backend는 App EC2의 `:8080`을 인터넷에 직접 공개하고 HTTP로 요청을 받았다. 이 구조는 단일 EC2 단계에서는 단순했지만, 애플리케이션 서버가 외부 진입점·TLS·향후 다중 인스턴스 요청 분산 책임까지 함께 갖게 된다.

## 문제

다음 요구를 동시에 만족할 진입 구조가 필요했다.

- 외부 Backend API를 HTTPS로 제공
- App EC2 `:8080` 직접 공개 제거
- Health Check가 가능한 공식 Public Endpoint 확보
- 이후 다중 App EC2와 Blue-Green 배포에서도 같은 진입점 유지
- Monitoring EC2의 Prometheus 접근은 유지

## 고려한 대안

1. **EC2 직접 HTTPS** — 별도 LB 비용은 없지만 인증서·TLS를 App EC2에서 직접 관리하고 다중 인스턴스 전환 시 진입 구조를 다시 설계해야 한다.
2. **CloudFront → Backend** — HTTPS/CDN 장점은 있지만 현재 목적의 HTTP Health Check·다중 Target Load Balancing을 위해 별도 계층이 다시 필요할 수 있다.
3. **API Gateway** — API 관리 기능은 강하지만 현재 요구보다 복잡도가 크다.
4. **NLB** — L4 Load Balancing에는 적합하지만 Spring Boot HTTP API의 L7 Health Check·라우팅에는 ALB가 더 직접적이다.
5. **ALB + ACM + Route 53** — HTTPS termination, Target Group Health Check, Security Group 경계와 이후 다중 EC2 확장을 같은 진입 구조에서 지원한다.

## 결정

Backend 공식 Public Endpoint를 다음 구조로 변경한다.

```text
Client
→ Route 53 `api.bobfull.click`
→ Application Load Balancer
→ ACM TLS termination :443
→ Target Group
→ App EC2 HTTP :8080
```

HTTP `:80` 요청은 HTTPS `:443`으로 301 Redirect한다. App EC2 `:8080`의 `0.0.0.0/0` 공개 규칙은 제거하고 ALB Security Group과 Monitoring Security Group에서만 접근하도록 제한한다.

Spring Boot 자체에는 TLS 인증서를 설치하지 않고 ALB에서 TLS를 종료한다.

## 선택 이유

ALB는 현재 필요한 HTTPS 진입, Health Check, Security Group 경계를 제공하면서 #169 다중 App EC2와 Blue-Green 배포에서도 진입점을 바꾸지 않고 Target만 확장할 수 있다.

실제 AWS 환경에서 `https://api.bobfull.click/actuator/health` HTTP 200/UP, HTTP→HTTPS 301 Redirect, Target Group Healthy, App EC2 `:8080` 직접 공개 제거와 Monitoring Prometheus 접근 유지를 확인했다.

## 장점

- 애플리케이션 서버와 외부 진입 책임을 분리한다.
- TLS 인증서 관리를 ACM/ALB 경계에 둔다.
- App EC2 Public `:8080` 노출을 제거한다.
- Target Group Health Check와 이후 다중 EC2 요청 분산 기반을 확보한다.
- Blue-Green에서도 동일한 Public Endpoint를 유지할 수 있다.

## 단점과 위험

- ALB와 Route 53/ACM 운영 비용·설정 복잡도가 추가된다.
- ALB가 공식 진입점이 되므로 Listener·Target Group·Security Group 설정 오류가 전체 Backend 진입에 영향을 줄 수 있다.
- 이 결정만으로 RDS·Redis·Kafka를 포함한 전체 시스템 HA가 확보되는 것은 아니다.

## 검증 방법

- HTTPS Health Check `200 / UP`
- HTTP `:80` → HTTPS `:443` 301 Redirect
- Target Group `Healthy`
- App EC2 `:8080`의 `0.0.0.0/0` 제거 후 ALB 경유 API 정상 동작
- Monitoring EC2 → App EC2 Prometheus 접근 유지

상세 결과는 #206 Evidence를 기준으로 한다.

## 재검토 조건

- WAF/API Gateway 등 별도 API Edge 요구가 생길 때
- ALB 기능으로 충족할 수 없는 라우팅·프로토콜 요구가 생길 때
- 비용 대비 진입 계층 단순화 필요성이 커질 때
