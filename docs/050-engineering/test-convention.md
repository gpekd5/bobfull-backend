# 테스트 컨벤션

이 문서는 Codex, Claude Code 등 구현 AI와 Human 개발자가 밥풀 테스트 코드를 작성할 때 따르는 기준이다.

## 1. 기본 원칙

- Issue 완료 조건과 실제 실패 위험을 증명하는 테스트를 우선한다.
- 테스트 메서드명은 **한글 설명형**으로 작성한다.
- 단어는 `_`로 구분해 조건과 기대 결과가 드러나게 작성한다.
- 테스트 본문은 `given → when → then` 순서로 구분한다.
- 하나의 테스트는 하나의 핵심 동작이나 결과를 검증한다.
- 구현하지 않은 기능이나 과도한 내부 동작을 억지로 테스트하지 않는다.

## 2. 테스트 이름

좋은 예시:

```java
@Test
void 결제_실패시_예약과_참여자가_생성되지_않는다() {
}

@Test
void 마지막_좌석에_동시_참여하면_한명만_성공한다() {
}

@Test
void 다른_사용자의_예약은_취소할수_없다() {
}
```

피해야 할 예시:

```java
@Test
void testCreate() {
}

@Test
void successTest() {
}

@Test
void test1() {
}
```

테스트 이름만 읽어도 다음이 보여야 한다.

```text
어떤 조건에서
→ 무엇을 실행하면
→ 어떤 결과가 나오는가
```

## 3. Given-When-Then

```java
@Test
void 결제_실패시_예약과_참여자가_생성되지_않는다() {
    // given
    Member member = 회원을_준비한다();
    TimeSlot timeSlot = 예약가능한_회차를_준비한다();
    결제가_실패하도록_설정한다();

    // when
    Throwable result = catchThrowable(
            () -> reservationService.create(member.getId(), timeSlot.getId())
    );

    // then
    assertThat(result).isInstanceOf(PaymentFailedException.class);
    assertThat(reservationRepository.count()).isZero();
    assertThat(participantRepository.count()).isZero();
}
```

- `given`: 테스트 데이터, Mock 동작과 사전 상태를 준비한다.
- `when`: 검증 대상 동작을 한 번 실행한다.
- `then`: 응답, 예외, 상태 변경, 저장 결과를 검증한다.
- 구분이 명확하면 각 영역을 불필요하게 길게 만들지 않는다.

## 4. 우선 검증 대상

기능에 필요한 항목만 선택한다.

- 정상 흐름
- 입력 오류와 실패 흐름
- 상태 전이와 경계값
- 인증·인가와 소유권
- 트랜잭션과 Rollback
- 중복 요청과 동시 요청
- 좌석·금액·참여 인원 정합성
- Repository 조회 조건과 락

## 5. 테스트 증거

PR에는 다음을 기록한다.

- 작성한 테스트 클래스와 한글 메서드명
- 테스트를 작성한 이유
- 실제 실행 명령
- PASS·FAIL·HOLD·NOT_RUN 결과
- 통과 건수, 실패 로그 또는 DB·응답 증거
- 테스트로 확인한 범위와 확인하지 못한 범위

실행하지 않은 테스트를 `PASS`로 기록하지 않는다.
테스트 통과만으로 운영 성능, 분산 환경, 보안 전체가 검증됐다고 일반화하지 않는다.
