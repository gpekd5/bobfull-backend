# Local SonarQube Analysis

## Purpose

이 문서는 BobFull backend를 로컬 SonarQube Community Build에서 같은 조건으로 분석하는 절차를 정의한다.
분석 결과의 기준값은 Issue별 Evidence에 기록하며, 이 문서는 실행 방법만 유지한다.

## Pinned Versions

| Component | Version |
|---|---|
| SonarQube Community Build | `26.9.0.129388-community` |
| SonarScanner for Gradle Plugin | `7.5.0.8588` |
| Project Gradle Wrapper | `9.5.1` |
| Project Java toolchain | `17` |

SonarQube와 scanner 버전은 2026-09-10 기준 공식 Docker image와 Gradle Plugin Portal에서 확인한 고정
버전이다. Scanner는 기본 JRE 자동 프로비저닝을 사용하므로 Java 17로 빌드되는 프로젝트를 변경하지 않는다.

## Requirements

- Docker Engine 20.10 이상
- Docker Compose
- SonarQube 소규모 설치 기준 최소 2 CPU, RAM 4 GB, 디스크 30 GB와 10% 이상 여유 공간
- 저장소의 Java 17과 Gradle Wrapper를 실행할 수 있는 환경

현재 Compose는 개인 개발 PC에서 Baseline을 재현하기 위한 구성이다. 외부 DB 없이 내장 H2를 사용하므로
공유 서버나 운영 SonarQube 설치에 사용하지 않는다.

## Start SonarQube

Windows 환경에서 이 저장소가 확인한 명령은 다음과 같다.

```powershell
docker-compose -f docker-compose.sonar.yml up -d
Invoke-RestMethod http://localhost:9000/api/system/status
```

Compose plugin을 제공하는 환경에서는 다음 명령도 사용할 수 있다.

```text
docker compose -f docker-compose.sonar.yml up -d
```

`api/system/status` 응답의 `status`가 `UP`이 될 때까지 기다린다. 포트 9000이 이미 사용 중이면 시작 전에
`SONARQUBE_PORT`를 다른 값으로 설정하고 이후 `sonar.host.url`에도 같은 포트를 사용한다.

## Create A Local Token

1. `http://localhost:9000`에 최초 계정 `admin` / `admin`으로 로그인한다.
2. 최초 비밀번호를 변경한다.
3. `My Account > Security`에서 User Token을 생성한다.
4. 토큰을 현재 shell 환경 변수에만 저장한다.

```powershell
$env:SONAR_TOKEN = '<local-user-token>'
$env:SONAR_HOST_URL = 'http://localhost:9000'
```

토큰, 변경한 관리자 비밀번호, SonarQube 데이터 파일은 Commit하지 않는다.

## Run Build And Analysis

Java 분석에는 컴파일된 bytecode가 필요하므로 테스트를 먼저 실행한 뒤 같은 명령에서 `sonar` task를 실행한다.

```powershell
$revision = git rev-parse HEAD
.\gradlew.bat clean test sonar "-Dsonar.host.url=$env:SONAR_HOST_URL" "-Dsonar.token=$env:SONAR_TOKEN" "-Dsonar.scm.revision=$revision"
```

macOS와 Linux에서는 `./gradlew`를 사용한다. 분석 완료 후 출력된 dashboard URL 또는 SonarQube Web API에서
Quality Gate와 주요 지표를 확인한다.

현재 프로젝트에는 JaCoCo 등 Coverage report 생성 설정이 없다. Coverage는 이 절차만으로 수집되지 않으며,
Coverage 체계 도입은 별도 Issue에서 결정한다.

## Re-measure After Refactoring

1. 이 문서의 고정 버전과 동일한 Compose 환경을 사용한다.
2. 비교할 Commit을 checkout하고 작업 트리가 깨끗한지 확인한다.
3. 동일한 `sonar.projectKey`와 Quality Profile, Quality Gate를 유지한다.
4. 위의 build와 분석 명령을 실행하고 대상 Commit SHA를 함께 기록한다.
5. Reliability, Security, Maintainability, Security Hotspots, Duplication, Quality Gate를 같은 API와 단위로 비교한다.
6. 버전, Profile, Gate 또는 실행 환경이 달라졌다면 직접 개선율을 계산하지 않고 차이를 기록한다.

Baseline 원본은 [`Issue #10 Evidence`](../110-records/evidence/v3/10-sonarqube-baseline/README.md)에 기록한다.

## Stop Or Reset

서버를 중지하되 Baseline 데이터를 유지한다.

```powershell
docker-compose -f docker-compose.sonar.yml down
```

볼륨 삭제 옵션은 분석 결과와 로컬 계정을 함께 제거한다. 재측정 환경을 초기화하려는 경우에만 명시적으로
`down --volumes`를 사용한다.

## Official References

- [SonarQube Community Build Docker installation](https://docs.sonarsource.com/sonarqube-community-build/server-installation/from-docker-image/installation-overview)
- [SonarScanner for Gradle](https://docs.sonarsource.com/sonarqube-community-build/analyzing-source-code/scanners/sonarscanner-for-gradle)
- [Scanner environment requirements](https://docs.sonarsource.com/sonarqube-community-build/analyzing-source-code/scanners/scanner-environment/general-requirements)
- [Gradle Plugin: org.sonarqube](https://plugins.gradle.org/plugin/org.sonarqube)
