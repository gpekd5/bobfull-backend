# Issue #12 Payment Expiration Test Evidence

## 검증 대상

결제 완료 통합 테스트의 READY Payment fixture가 특정 미래 날짜를 지나면 만료되어 실패하는 문제를 재현하고,
production 동작을 바꾸지 않은 채 테스트 데이터의 시간 의존성만 제거한다.

## 측정 계약

- Primary KPI: 대상 두 클래스의 13개 테스트에서 실패 `11 -> 0`
- Secondary KPI: 전체 clean build 성공
- 안전 확인: production code, 결제 정책, API, DB, Transaction 변경 없음

## 기준 코드

- Before SHA: `7f4c1fee6dbe073533031f1f224279cd047c2b50`
- After code SHA: `c7f8da89716efa971a039286fc8709a1fea5b0dc`

## 환경·데이터·실행 조건

- 실행일: 2026-09-10 KST
- Java: Oracle JDK `17.0.12`
- Gradle Wrapper: `9.5.1`
- 대상: `PaymentCompletionIdempotencyIntegrationTest`, `PaymentReservationConfirmationTransactionIntegrationTest`

## Before 결과

```powershell
.\gradlew.bat clean test --tests "com.bobfull.payment.service.PaymentCompletionIdempotencyIntegrationTest" --tests "com.bobfull.payment.service.PaymentReservationConfirmationTransactionIntegrationTest"
```

- 결과: `FAIL`
- 테스트: 13 completed, 11 failed
- 주요 예외: `PaymentExpiredException`
- 원인: READY Payment fixture의 `expiresAt`이 `2026-09-01T00:00:00Z`로 고정되어 실행일 기준 만료됨
- PR #11과의 관계: fork `develop`에서 동일한 클래스와 실패 수가 재현되어 SonarQube 변경과 무관한 기존 실패임

## 변경 내용

- 대상 테스트 fixture의 READY Payment 만료 시각을 생성 시점 기준 10분 후로 설정
- TimeSlot 등 실패와 무관한 고정 테스트 데이터는 변경하지 않음

## After 결과

```powershell
.\gradlew.bat clean :test --tests "com.bobfull.payment.service.PaymentCompletionIdempotencyIntegrationTest" --tests "com.bobfull.payment.service.PaymentReservationConfirmationTransactionIntegrationTest"
```

- 결과: `PASS` (`BUILD SUCCESSFUL`)
- 테스트: 13 completed, 0 failed
- 참고: 루트 프로젝트의 대상 테스트만 실행하도록 `:test` task를 명시했다. `test` task를 사용하면 동일한 필터가 테스트 클래스가 없는 Lambda 하위 프로젝트에도 적용된다.

```powershell
.\gradlew.bat clean build
```

- 결과: `PASS` (`BUILD SUCCESSFUL`, 3m 44s)
- 테스트: 955 completed, 0 failed, 0 errors, 64 skipped
- Gradle tasks: 14 actionable tasks (13 executed, 1 up-to-date)

## 정합성 회귀 검증

- Markdown 상대 링크: `PASS` (111 files, 181 links, 0 broken)
- `git diff --check`: `PASS`
- production source 변경: 없음

## 결과 해석

특정 달력 날짜가 지나도 유효한 READY Payment를 사용하는 테스트 의도가 유지되어야 한다.

## 검증 한계

- 이번 변경은 실패가 재현된 두 통합 테스트의 fixture만 다룬다.
- 만료·경계 동작 자체는 고정 `Clock`을 사용하는 기존 단위 테스트 범위이며 변경하지 않는다.

## 관련

- [Issue #12](https://github.com/gpekd5/bobfull-backend/issues/12)
- [SonarQube Baseline PR #11](https://github.com/gpekd5/bobfull-backend/pull/11)
