# Issue #10 SonarQube Baseline Evidence

## 검증 대상

BobFull backend 전체 Gradle project를 고정된 로컬 SonarQube 환경에서 분석하고, 이후 리팩토링과 비교할
수 있는 최초 정적 분석 결과를 기록한다.

## 측정 계약

- Primary KPI: 전체 분석 성공과 분석 대상 Commit SHA 기준 Baseline 확보
- Secondary KPI: Reliability, Security, Maintainability, Security Hotspots, Duplication, Quality Gate
- 안전 확인: 애플리케이션 코드 미변경과 기존 build/test 통과

## 기준 코드

- 분석 SHA: `d0eb2fd2a82d6a40213c591b3c7366ae1779fa25`
- 기준 브랜치: fork `develop`에서 생성한 Issue #10 전용 브랜치

## 환경·데이터·실행 조건

- 측정일: 2026-09-10 23:11 KST
- SonarQube Community Build: `26.9.0.129388-community`
- SonarScanner for Gradle Plugin: `7.5.0.8588`
- Gradle Wrapper: `9.5.1`
- Java toolchain / launcher: `17` / Oracle JDK `17.0.12`
- 실행 환경: Windows 11 amd64, Docker Desktop `4.80.0`, Docker Engine `29.6.1`, Docker Compose `5.1.4`
- SonarQube project key: `bobfull-backend`

```powershell
docker-compose -f docker-compose.sonar.yml up -d
Invoke-RestMethod http://localhost:9000/api/system/status
$env:SONAR_TOKEN = '<local-user-token>'
$env:SONAR_HOST_URL = 'http://localhost:9000'
$revision = git rev-parse HEAD
.\gradlew.bat clean classes testClasses sonar "-Dsonar.host.url=$env:SONAR_HOST_URL" "-Dsonar.token=$env:SONAR_TOKEN" "-Dsonar.scm.revision=$revision"
```

## Baseline 결과

SonarQube Compute Engine task `02184477-6fd6-4b23-9b3e-ba228e805547`가 `SUCCESS`로 완료된 뒤 Web API에서
확인한 결과다.

| 지표 | Baseline | 판정·해석 |
|---|---:|---|
| 분석 성공 | `SUCCESS` | 전체 Gradle project와 하위 Lambda project 분석 완료 |
| Quality Gate | `OK` | Clean as You Code 준수, 최신 반복 분석에서 new violations `0` |
| Reliability rating | `C` (`3.0`) | legacy `bugs` metric `14` |
| Security rating | `D` (`4.0`) | legacy `vulnerabilities` metric `1` |
| Maintainability rating | `A` (`1.0`) | legacy `code_smells` metric `313` |
| Security Hotspots | `0` | 검토 대기 Hotspot 없음 |
| Duplication | `1.3%` | 중복 line `236`, block `16`, file `14` |
| Coverage | `0.0%` | Coverage report 미수집이므로 품질 Baseline으로 해석하지 않음 |
| 기술 부채 | `2,027분` | 약 33시간 47분 |
| Reliability remediation effort | `126분` | SonarQube 추정값 |
| Security remediation effort | `5분` | SonarQube 추정값 |
| 규모 | `14,537 ncloc` | file `404`, class `459`, function `1,321`, statement `4,377` |

미해결 Issue 검색 결과는 main과 test scope를 합쳐 `329`건이다.

| 분류 방식 | Reliability / Bug | Security / Vulnerability | Maintainability / Code Smell |
|---|---:|---:|---:|
| Software Quality impact | `19` | `1` | `310` |
| legacy type facet | `15` | `1` | `313` |
| main scope legacy type | `10` | `1` | `103` |

Software Quality impact는 한 Issue가 둘 이상의 품질에 영향을 줄 수 있어 합계를 전체 Issue 수로 사용하지 않는다.
또한 component measure와 Issue 검색 facet은 scope와 분류 기준이 다르므로 이후 비교에서도 같은 API, metric,
scope를 유지한다.

## 변경 내용

- 분석 전용 Docker Compose 구성
- Gradle SonarScanner 연결
- 로컬 실행 및 재측정 절차 문서화

## 정합성 회귀 검증

| 검증 | 결과 | 근거 |
|---|---|---|
| SonarQube Compose 해석 | `PASS` | `docker-compose -f docker-compose.sonar.yml config` |
| SonarQube 기동 | `PASS` | `/api/system/status`: `UP`, version `26.9.0.129388` |
| main/test compile + 분석 | `PASS` | 12 actionable tasks, Compute Engine `SUCCESS` |
| 하위 Lambda 포함 | `PASS` | component tree에서 `lambda/restaurant-image-validator/src/main`과 `src/test` 확인 |
| test 제외 clean build | `PASS` | 13 actionable tasks, 테스트는 의도적으로 미실행 |
| 전체 clean build/test | `FAIL` | 944 tests, 11 failed, 64 skipped; Sonar 분석 전 중단 |

전체 build 실패는 `PaymentCompletionIdempotencyIntegrationTest`와
`PaymentReservationConfirmationTransactionIntegrationTest`의 11개 case에서 발생했다. 주요 실패는
`PaymentExpiredException`이며 fixture의 결제 만료 시각이 `2026-09-01T00:00:00Z`, 실행일이
2026-09-10인 기존 시간 의존 상태다. 이번 Issue에서는 테스트나 애플리케이션 코드를 수정하지 않았다.

## 결과 해석

이번 결과는 리팩토링 전 현재 코드의 기준값이다. 이후 리팩토링은 같은 image, Plugin, project key, API metric과
scope로 재측정해야 한다. 발견된 문제와 기존 테스트 실패는 이 Issue에서 수정하지 않는다.

## 검증 한계

- 현재 프로젝트에는 JaCoCo 등 Coverage report 생성 설정이 없어 Coverage는 수집하지 않는다.
- 로컬 개발용 내장 H2 구성은 공유 또는 운영 SonarQube 설치 기준이 아니다.
- Quality Gate는 첫 분석에서 조건 없이 `OK`였고, 동일 SHA 반복 분석에서는 이전 분석을 기준으로
  `new_violations = 0` 조건이 적용됐다. 전체 기존 Issue가 없다는 의미가 아니다.
- 분석 중 deprecated API, unchecked operation 경고가 출력됐지만 이 Issue 범위에서는 수정하지 않았다.

## 관련

- [Issue #10](https://github.com/gpekd5/bobfull-backend/issues/10)
- [로컬 SonarQube 실행 방법](../../../../050-engineering/sonarqube-local.md)
