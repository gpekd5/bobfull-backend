# Issue #226 배포 파이프라인과 Docker 이미지 최적화 트러블슈팅

## 기본 정보

- 날짜: 2026-08-12
- 도메인: 배포 운영 / CI/CD / Docker 이미지
- 관련 Issue: #226
- 관련 PR: #229, #231
- 상태: 해결됨

Issue #226은 GitHub Actions 배포 파이프라인의 중복 작업과 Docker 이미지 구조를 함께 다룬 트러블슈팅이다. PR1은 CI/CD 단계의 중복과 대기 시간을 줄였고, PR2는 Docker 이미지 크기와 코드 변경 시 재전송되는 Docker Layer 크기를 줄였다.

핵심은 단순히 이미지 용량만 줄이는 것이 아니라, 코드만 바뀌는 일반적인 배포에서 기존 약 199MB Fat JAR layer 전체가 무효화되던 구조를 약 2.83MB application layer 중심 변경으로 바꾸는 것이다.

## 1. 문제 정의

Before 기준 배포 파이프라인은 전체 Workflow가 16m 51s였고, 단일 EC2 배포 특성상 관측 다운타임은 48.65s였다. CI에서 Gradle Build/Test가 약 10분을 차지했고, CI/CD 전체에는 Docker Build와 Gradle Build가 중복 수행되는 구조가 있었다.

Docker 이미지 측면에서는 GitHub Actions에서 이미 생성한 실행 JAR를 Dockerfile에서 다시 다루는 과정이 비효율적이었다. PR1 반영 후에도 최종 Runtime 이미지에는 약 199MB Fat JAR가 하나의 Docker Layer로 COPY되어, 코드만 조금 바뀌어도 해당 layer 전체가 바뀌었다.

초기 기준은 다음과 같았다.

| 항목 | Before |
|---|---:|
| 전체 Workflow | 16m 51s |
| CI Job | 11m 39s |
| Gradle Build / Test | 10m 15s |
| CI Docker Build | 1m 14s |
| CD Job | 5m 06s |
| Docker Build + ECR Push | 1m 46s |
| SSM Deploy | 2m 54s |
| 단일 EC2 관측 다운타임 | 48.65s |
| Docker Desktop image size | 약 838MB |
| docker image inspect size | 약 299MB |
| 주요 변경 Docker Layer | 약 199MB Fat JAR |

## 2. 원인 분석

### 원인 1: CI와 CD의 Docker Build 중복

CI Job에서 별도 Docker Build를 수행하고, CD Job에서도 ECR Push를 위해 다시 Docker Build를 수행했다. 같은 commit을 검증하고 배포하는 흐름에서 Docker image 생성이 한 번 이상 발생하므로, CI Job 시간과 전체 Workflow 시간이 불필요하게 늘어났다.

### 원인 2: Dockerfile 내부 Gradle bootJar 재실행

GitHub Actions의 Gradle 단계에서 이미 `bootJar`를 생성했는데, Dockerfile 내부에서도 Gradle build를 다시 수행하는 구조가 있었다. 이 구조는 CI Gradle cache와 GitHub Actions artifact를 충분히 활용하지 못하고, Docker build 단계가 애플리케이션 빌드 책임까지 중복으로 갖게 만들었다.

### 원인 3: SSM polling과 고정 sleep

SSM Run Command polling 간격이 10초였고, backend 실행 후 고정 `sleep 10`이 존재했다. 이미 readiness polling이 있는 상황에서 고정 sleep은 컨테이너가 더 빨리 준비되더라도 다음 단계로 넘어가지 못하게 만드는 대기 시간이었다.

### 원인 4: Fat JAR 단일 COPY layer

PR1 이후 Dockerfile은 GitHub Actions artifact로 전달된 `build/libs/*.jar`를 Runtime 이미지에 COPY했다. 이 구조는 Dockerfile 내부 Gradle build 중복은 제거했지만, 약 199MB Fat JAR 전체가 하나의 layer가 되는 문제는 남겼다.

Spring Boot 실행 JAR 내부를 확인하면 대부분의 크기는 애플리케이션 코드가 아니라 dependency였다.

| 구성 | 크기 |
|---|---:|
| BOOT-INF/lib | 약 188.59MiB |
| BOOT-INF/classes | 약 1.40MiB |
| Fat JAR 전체 | 약 199MB |

따라서 코드 변경 때마다 dependency까지 포함된 Fat JAR layer 전체를 다시 전송하는 것은 비효율적이었다.

### 원인 5: Runtime Base Image 용량 비중

기존 Runtime base는 `eclipse-temurin:17-jre`였다. 안정적인 선택이지만 OS/JRE layer 비중이 커서, 애플리케이션 layer 최적화와 별개로 최종 이미지 크기 절감 여지가 있었다.

단, Docker Desktop Disk Usage, `docker image inspect` size, ECR push/pull 전송량은 서로 같은 지표가 아니다. Docker Desktop은 로컬 저장소의 shared layer와 metadata 영향을 받으며, ECR 전송량은 registry에 존재하는 layer 재사용 여부와 압축 layer 크기의 영향을 받는다. 이 문서에서는 지표를 섞어 하나의 수치처럼 해석하지 않는다.

## 3. 1차 개선 - CI/CD Pipeline 최적화

### 가설

CI/CD에서 같은 commit에 대해 Docker Build와 Gradle Build를 중복 수행하지 않도록 정리하면, 기능 동작을 바꾸지 않고도 전체 Workflow와 CD Job 시간을 줄일 수 있다고 봤다. SSM polling과 고정 sleep도 실패 감지 안정성을 낮추지 않는 범위에서 줄일 수 있다고 판단했다.

### 적용

- CI Docker Build 제거
- CI에서는 Gradle `check`와 `bootJar`까지만 수행
- GitHub Actions Artifact로 `build/libs/*.jar` 전달
- CD에서만 Docker Build + ECR Push 1회 수행
- Dockerfile 내부 Gradle `bootJar` 재실행 제거
- SSM polling 간격 10초에서 3초로 변경
- backend 실행 후 고정 `sleep 10` 제거
- 고정 sleep 대신 기존 readiness polling 사용
- Parameter Store 중복 조회 제거
- Kafka 테스트의 `Thread.sleep(5000)`을 조건 기반 대기로 변경

### 1차 결과

1차 결과는 GitHub Actions 수동 배포 Run #14의 실제 Job/Step 로그와 배포 관측값 기준이다.

| 항목 | Before | 1차 결과 | 변화 |
|---|---:|---:|---:|
| 전체 Workflow | 16m 51s | 14m 37s | 2m 14s 단축 |
| CI Job | 11m 39s | 11m 00s | 39s 단축 |
| Gradle Build / Test | 10m 15s | 10m 43s | 28s 증가 |
| CI Docker Build | 1m 14s | 제거 | 1m 14s 단축 |
| CD Job | 5m 06s | 3m 29s | 1m 37s 단축 |
| Docker Build + ECR Push | 1m 46s | 32s | 1m 14s 단축 |
| SSM Deploy | 2m 54s | 2m 30s | 24s 단축 |
| 단일 EC2 관측 다운타임 | 48.65s | 46.42s | 2.23s 단축 |

1차 개선의 가장 큰 효과는 CI Docker Build 제거와 CD Docker Build/ECR Push 시간 감소였다. 반면 Gradle Build/Test는 10m 15s에서 10m 43s로 증가했다. 이는 1차 개선이 Gradle 테스트 구조 자체를 크게 바꾼 작업이 아니며, 여전히 CI의 최대 병목이 테스트 실행 구간이라는 점을 보여준다.

## 4. 2차 개선 - Docker Image / Layer 최적화

### 가설

PR1 이후에도 Docker 이미지는 약 199MB Fat JAR를 단일 layer로 COPY했다. Spring Boot Layered JAR를 활용해 dependency와 application을 별도 layer로 분리하면, dependency가 바뀌지 않는 일반 코드 변경에서 재전송되는 layer를 크게 줄일 수 있다고 봤다.

또한 Runtime Base Image를 비교해, 운영 위험이 과도하지 않은 범위에서 이미지 크기를 줄일 수 있는지 확인했다.

### 적용

Spring Boot 4 기준으로 `layertools`가 아니라 `tools` jarmode를 사용해 JAR layer를 추출했다.

```text
dependencies
spring-boot-loader
snapshot-dependencies
application
```

Dockerfile은 GitHub Actions가 만든 `build/libs/*.jar` artifact를 extractor stage에서 추출하고, Runtime stage에는 추출된 layer만 COPY하도록 변경했다. 실행 방식은 Fat JAR 실행에서 exploded jar + `JarLauncher` 실행으로 바꿨다.

### Runtime Base Image 후보 비교

| 후보 | docker image inspect size | readiness | 판단 |
|---|---:|---:|---|
| `eclipse-temurin:17-jre` | 약 298.69MB | 약 9.5s | 가장 보수적이지만 크기 절감 효과가 거의 없음 |
| `eclipse-temurin:17-jre-alpine` | 약 248.05MB | 약 10.5s | 가장 작지만 musl/native library 호환 위험 때문에 미선택 |
| `gcr.io/distroless/java17-debian12` | 약 262.63MB | 약 9.6s | 최종 선택 |

Alpine은 크기만 보면 가장 작았다. 그러나 현재 JAR에는 `zstd-jni`, `snappy-java`, `lz4-java`, Netty native 계열 의존성이 포함되어 있고, Alpine은 musl 기반이라 native library 호환 위험이 더 크다고 판단했다.

Distroless는 shell과 keytool이 없어 컨테이너 내부 디버깅이 어렵다. 대신 glibc, cacerts, tzdata layer가 유지되고 readiness가 통과했다. 현재 배포 스크립트는 컨테이너 내부 shell에 의존하지 않고 host에서 `docker run`, `docker logs`, `curl` readiness를 사용하므로, 이번 범위에서는 Distroless를 최종 선택했다.

Kafka, OpenAI, PortOne 의존성 제거는 이번 PR2 범위가 아니다. 기능 로직과 테스트 구조를 대규모로 바꾸지 않고 Docker image/layer 구조만 개선했다.

### 2차 로컬 결과

| 항목 | Before | 2차 로컬 결과 |
|---|---:|---:|
| Docker Desktop image size | 약 838MB | 약 697MB |
| docker image inspect size | 약 299MB | 약 262.6MB |
| inspect 기준 감소율 | - | 약 12.1% 감소 |
| 기존 변경 layer | 약 199MB Fat JAR | 제거 |
| dependencies layer | - | 약 198MB |
| spring-boot-loader layer | - | 약 696KB |
| snapshot-dependencies layer | - | 약 4.1KB |
| application layer | - | 약 2.83MB |

### Layer cache 검증

최종 Dockerfile 기준으로 application resource를 임시 변경한 뒤 다시 build하여 layer cache를 확인했다.

결과는 다음과 같았다.

- `dependencies` layer 재사용
- `spring-boot-loader` layer 재사용
- `snapshot-dependencies` layer 재사용
- `application` layer만 변경

RootFS layer digest 비교에서도 40개 layer 중 마지막 application layer만 변경됐다. 따라서 dependency가 바뀌지 않는 코드 변경에서는 기존 약 199MB Fat JAR layer 전체가 아니라 약 2.83MB application layer 중심으로 변경된다.

## 5. 실제 배포 검증

### PR2 최초 배포

PR2 최초 배포는 신규 Distroless base와 새로 분리된 Docker layer를 처음 Push/Pull한 배포였다.

이 배포의 전체 Workflow 시간은 GitHub Hosted Runner 대기시간이 포함되어 있어 Before나 PR1 Run #14와 단순 비교하지 않는다. 실제 비교에는 step 단위 시간과 다운타임을 분리해서 본다.

| 항목 | PR2 최초 배포 |
|---|---:|
| Docker Build + ECR Push | 약 56s |
| SSM Deploy | 약 2m 24s |
| 단일 EC2 관측 다운타임 | 약 41.36s |

### PR2 재배포

PR2 재배포는 동일 SHA ECR Image 재사용 조건에서 수행됐다. 이 경우 `Check ECR image tag` 단계에서 기존 SHA image가 존재하므로 Docker Build/Push는 Skip된다.

| 항목 | PR2 재배포 |
|---|---:|
| 전체 Workflow | 약 14m 19s |
| Docker Build / Push | 기존 SHA Image 재사용으로 Skip |
| 단일 EC2 관측 다운타임 | 약 40.25s |

PR2 재배포의 14m 19s는 동일 SHA Image 재사용 조건이므로, 새 이미지 Build/Push를 포함한 성능 개선값으로 해석하면 안 된다. 이 값은 같은 image를 재배포할 때 ECR Build/Push 비용이 빠지는 경로의 관측값이다.

## 6. 최종 결과 정리

### 통합 지표

아래 표는 Before, 1차, 2차를 한 번에 보기 위한 핵심 지표만 모은다. 2차 결과는 동일 SHA ECR image를 재사용한 재배포 기준이다. 따라서 2차 Workflow 14m 19s와 Docker Build/Push Skip은 새 이미지 Build/Push를 포함한 성능 개선값이 아니라, 같은 image를 다시 배포한 경로의 관측값이다.

| 항목 | Before | 1차 결과 | 2차 결과 |
|---|---:|---:|---:|
| 전체 Workflow | 16m 51s | 14m 37s | 약 14m 19s |
| CI Docker Build | 1m 14s | 제거 | 제거 유지 |
| Docker Build + ECR Push | 1m 46s | 32s | Skip |
| 단일 EC2 관측 다운타임 | 48.65s | 46.42s | 약 40.25s |
| Docker Desktop image size | 약 838MB | 약 838MB | 약 697MB |
| docker image inspect size | 약 299MB | 약 299MB | 약 262.6MB |
| 주요 변경 Docker Layer | 약 199MB Fat JAR | 약 199MB Fat JAR | 약 2.83MB application layer |
| dependencies layer | Fat JAR에 포함 | Fat JAR에 포함 | 약 198MB |
| spring-boot-loader layer | Fat JAR에 포함 | Fat JAR에 포함 | 약 696KB |
| snapshot-dependencies layer | Fat JAR에 포함 | Fat JAR에 포함 | 약 4.1KB |
| application layer | Fat JAR에 포함 | Fat JAR에 포함 | 약 2.83MB |

| 구간 | 핵심 결과 |
|---|---|
| Before | Workflow 16m 51s, Docker Desktop 약 838MB, docker inspect 약 299MB, 주요 변경 layer 약 199MB, 다운타임 48.65s |
| 1차 | Workflow 14m 37s, 중복 Docker Build 제거, CD Job 5m 06s에서 3m 29s로 단축 |
| 2차 | Docker Desktop 약 697MB, docker inspect 약 262.6MB, 코드 변경 시 약 2.83MB application layer 중심 변경, 다운타임 재측정 약 40.25s |

1차 개선은 중복 작업 제거와 대기 시간 축소가 중심이었다. 2차 개선은 이미지 크기 감소와 layer cache 효율 개선이 중심이었다.

특히 PR2의 핵심 성과는 Docker Desktop 기준 약 838MB에서 약 697MB로 줄어든 것만이 아니다. 더 중요한 변화는 코드 변경 시 Docker가 다시 다루는 주요 application layer가 약 199MB Fat JAR 전체에서 약 2.83MB application layer로 줄어든 것이다.

## 7. 남은 문제 / 다음 단계

### 남은 문제

- Gradle Build/Test가 여전히 약 10분 이상으로 전체 CI의 가장 큰 병목이다.
- 단일 EC2 구조이기 때문에 SSM polling과 Docker layer를 최적화해도 약 40초 수준의 서비스 중단이 남는다.
- Distroless는 shell 디버깅이 불가능하므로 장애 대응 시 `docker logs`, host 기반 health check, CloudWatch 로그 중심으로 확인해야 한다.

### 다음 단계

다음 병목은 Docker 이미지가 아니라 배포 아키텍처다. 단일 EC2에서 기존 컨테이너를 교체하는 방식은 컨테이너 중단과 새 컨테이너 readiness 사이의 다운타임을 완전히 제거하기 어렵다.

후속 개선은 EC2 다중화와 ALB Health Check 기반 Blue-Green 배포로 넘어가야 한다. 최소 2개 이상의 backend target을 두고, 새 target이 health check를 통과한 뒤 트래픽을 전환하는 구조가 필요하다.

Gradle Build/Test 병목은 별도 이슈에서 테스트 구조, Spring context 재사용, Kafka Testcontainers 대기, 병렬 실행 가능성을 분리해 다루는 것이 적절하다.

## 배운 점

CI/CD 최적화는 한 단계만 보면 효과를 과대평가하기 쉽다. PR1에서 중복 Docker Build를 제거해 전체 시간은 줄었지만 Gradle Build/Test 병목은 그대로 남았다. PR2에서 이미지 크기와 layer 구조를 개선했지만, 단일 EC2 배포의 구조적 다운타임은 완전히 사라지지 않았다.

따라서 배포 최적화는 다음 순서로 나눠 봐야 한다.

```text
중복 작업 제거
→ Docker image/layer 전송량 축소
→ 테스트 병목 분리
→ Health Check 통과 후 트래픽을 전환하는 Blue-Green 배포 아키텍처 도입
```

Issue #226은 앞의 두 단계를 처리했고, 남은 큰 병목은 Gradle 테스트 시간과 단일 EC2 배포 구조다.
