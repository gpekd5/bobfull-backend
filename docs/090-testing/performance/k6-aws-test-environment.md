# K6 AWS 성능 테스트 환경

Issue #207의 AWS 실행 환경 준비 결과를 정리한다.

## 구성 목적

운영 환경에 Load/Stress 부하를 직접 주지 않도록, 부하를 직접 받는 App EC2와 RDS만 테스트용으로 분리했다.
VPC·Subnet·ECR 등 기존 공통 인프라는 재사용해 일회성 성능 테스트 환경의 비용과 구성 복잡도를 줄였다.

## 최종 구성

```text
Tester PC (k6)
    |
    | HTTP :8080
    v
Test App EC2
  |- Spring Boot :8080
  `- Redis (Docker :6379)
        |
        v
Test RDS MySQL
  `- bobfull_test
```

- Test App EC2
  - 기존 VPC / Public Subnet 재사용
  - SSM Session Manager 사용
  - Docker 기반 Spring Boot 실행
  - 기존 ECR 백엔드 이미지 사용
  - Redis 7 Alpine을 동일 EC2의 Docker 컨테이너로 실행
- Test RDS
  - MySQL, Single-AZ
  - Private 접근
  - 테스트 전용 `bobfull_test` 스키마 사용
  - 3306 인바운드는 Test App EC2 Security Group만 허용
- 외부 접근
  - k6 실행자별 공인 IPv4 `/32`만 Test App EC2의 8080 포트에 허용
  - RDS와 Redis는 외부에서 직접 접근하지 않음

## 배포 및 검증

기존 백엔드 배포 스크립트를 재사용하되 DB 연결 값은 테스트 RDS로 지정했다.

검증 결과:

- Test App EC2 -> Test RDS 3306 연결 확인
- Spring Boot -> `bobfull_test` JDBC 연결 확인
- Backend container `running`
- Redis container `running`
- `/actuator/health` HTTP 200 / `UP` 확인

## 팀원 사용 방법

먼저 본인의 공인 IPv4를 인프라 담당자에게 전달한다.
해당 IP가 Test App EC2 Security Group의 8080 포트에 `/32`로 등록된 뒤 사용한다.

연결 확인:

```bash
curl http://<test-ec2-public-ip>:8080/actuator/health
```

Windows PowerShell에서는 다음처럼 실행할 수 있다.

```powershell
curl.exe http://<test-ec2-public-ip>:8080/actuator/health
```

k6 실행 시 `BASE_URL`을 테스트 EC2 주소로 지정한다.

```bash
k6 run \
  -e STAGE=smoke \
  -e BASE_URL=http://<test-ec2-public-ip>:8080 \
  k6/scenarios/restaurant-search.js
```

Load 예시:

```bash
k6 run \
  -e STAGE=load \
  -e BASE_URL=http://<test-ec2-public-ip>:8080 \
  k6/scenarios/restaurant-search.js
```

`k6/common/config.js`는 실행 시 전달한 `BASE_URL`을 우선 사용하고, 미지정 시 `http://localhost:8080`을 사용한다.

## 테스트 데이터

테스트 요청은 운영 DB가 아니라 테스트 RDS의 `bobfull_test`를 사용한다.
Fixture 시딩과 Smoke/Load/Stress 실행 결과는 각 k6 시나리오 및 Evidence 작업에서 이어서 관리한다.

## 비용 및 정리

이 환경은 성능 테스트 기간에만 유지한다.
테스트 완료 후 추가한 Test App EC2와 Test RDS를 삭제해 비용을 통제한다.
기존 VPC·Subnet·ECR·모니터링 인프라는 공용 리소스이므로 삭제 대상이 아니다.
