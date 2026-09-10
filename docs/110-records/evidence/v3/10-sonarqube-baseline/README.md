# Issue #10 SonarQube Baseline Evidence

## 검증 대상

BobFull backend 전체 Gradle project를 고정된 로컬 SonarQube 환경에서 분석하고, 이후 리팩토링과 비교할
수 있는 최초 정적 분석 결과를 기록한다.

## 측정 계약

- Primary KPI: 전체 분석 성공과 분석 대상 Commit SHA 기준 Baseline 확보
- Secondary KPI: Reliability, Security, Maintainability, Security Hotspots, Duplication, Quality Gate
- 안전 확인: 애플리케이션 코드 미변경과 기존 build/test 통과

## 기준 코드

- 분석 SHA: 측정 후 기록
- 기준 브랜치: fork `develop`에서 생성한 Issue #10 전용 브랜치

## 환경·데이터·실행 조건

- 측정일: 측정 후 기록
- SonarQube Community Build: `26.9.0.129388-community`
- SonarScanner for Gradle Plugin: `7.5.0.8588`
- Gradle Wrapper: `9.5.1`
- Java toolchain: `17`
- 실행 환경: 측정 후 기록
- 실행 명령: 측정 후 기록

## Baseline 결과

실제 분석 완료 후 Web API에서 확인한 수치를 기록한다.

## 변경 내용

- 분석 전용 Docker Compose 구성
- Gradle SonarScanner 연결
- 로컬 실행 및 재측정 절차 문서화

## 정합성 회귀 검증

실제 검증 완료 후 결과를 기록한다.

## 결과 해석

이번 결과는 리팩토링 전 현재 코드의 기준값이다. 발견된 문제는 이 Issue에서 수정하지 않는다.

## 검증 한계

- 현재 프로젝트에는 JaCoCo 등 Coverage report 생성 설정이 없어 Coverage는 수집하지 않는다.
- 로컬 개발용 내장 H2 구성은 공유 또는 운영 SonarQube 설치 기준이 아니다.

## 관련

- [Issue #10](https://github.com/gpekd5/bobfull-backend/issues/10)
- [로컬 SonarQube 실행 방법](../../../../050-engineering/sonarqube-local.md)
