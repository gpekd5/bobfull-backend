# Refactoring Evidence Standard

## 목적

이 경로는 Refactor Learning Mode의 구조 변경을 Before/After로 비교하고 기존 동작이 유지됐는지 기록한다.
성능 향상이나 신뢰성 개선 효과를 증명하기 위한 무거운 실험 기준은 적용하지 않는다.

## 라우팅

| 작업 | Evidence 기준 |
|---|---|
| 패키지·모듈·계층 구조 변경과 기존 동작 보존 | `docs/110-records/evidence/refactoring/` |
| 성능·신뢰성·동시성·인프라·캐시·Kafka/Outbox·AI 개선 효과 | `docs/110-records/evidence/v3/` |

구조 변경과 함께 개선 효과까지 주장하면 개선 효과는 v3 기준으로 검증한다. 구조 변경 기록을 별도로 남길
필요가 있을 때만 refactoring Evidence를 함께 사용하고 두 문서의 결과를 중복 작성하지 않는다.

## 최소 기록 항목

- 검증 대상과 리팩토링 범위
- Before/After Commit SHA와 동일 비교 조건
- 파일·패키지·모듈·책임·의존성 등 변경 구조
- API, DB, 비즈니스 정책, Transaction, Lock, Event 등 유지해야 할 동작
- 관련 테스트와 전체 build 결과
- 변경 범위와 직접 관련된 SonarQube 또는 정적 분석 회귀
- 실행하지 못한 검증, 환경 차이와 남은 한계

수치가 의미 있는 구조 항목은 실제 검색 명령과 집계 방법을 함께 기록한다. 외부 참조나 Transaction처럼
감소 목표가 아닌 항목은 개선 수치가 아니라 동일성 Guardrail로 명시한다.

## 기록 원칙

- 기존 동작을 바꾸지 않는다는 계약과 실제 검증을 연결한다.
- Before와 After는 가능한 한 같은 검색·build·test·분석 조건으로 비교한다.
- 실행하지 않은 결과를 `PASS`로 쓰거나 기존 이슈를 신규 회귀처럼 추정하지 않는다.
- 구조 이동으로 정적 분석 이슈 identity가 바뀌면 총수·유형·위치와 이력을 대조해 실제 회귀 여부를 구분한다.
- 기존 전체 정적 분석 이슈를 리팩토링 범위에서 모두 수정하지 않는다.
- 민감정보와 대용량 원본 로그를 Commit하지 않는다.

## Issue별 README 양식

```markdown
# Issue #번호 제목 Evidence

## 검증 대상

## 유지할 기존 동작

## 기준 코드
- Before SHA:
- After SHA:

## 환경·실행 조건

## 측정 방법

## Before 결과

## 변경 내용

## After 결과

## build/test/Sonar 회귀 검증

## 결과 해석

## 검증 한계

## 관련
- Issue:
- PR:
```

Issue 특성상 필요 없는 항목은 억지로 채우지 않고 `NOT_APPLICABLE` 또는 미실행 이유를 기록한다.
