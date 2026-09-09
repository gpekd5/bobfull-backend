# BobFull V2 Flow Lab

기준 `develop` SHA는 `a0d519561fb7ebe20eea8a08649f4f08f2583987`이다. V1을 변경하지 않고 V2 전용 Landing과 두 Lab을 제공한다.

- `reliability-flow-debugger`: 실제 채택된 흐름을 6개 Chapter로 재생한다.
- `reliability-experiment-lab`: 실제 채택안과 비교용 가상 대안을 5개 실험으로 비교한다.

PR #179 구조화 로그는 기준 `develop`에 병합되어 `develop merged`로 표시한다. PR #177 이메일 알림은 아직 병합되지 않아 Evidence 기준 Head `33b403649a3c093719b97644ab4a1edb8d140d8b`의 `open PR basis`로 표시한다. `OPEN PR TARGET · IN PROGRESS`는 Human 확정 목표가 아직 PR Head에 구현되지 않았을 때만 사용하며, 이번 이메일 구조에는 적용하지 않는다.

각 Lab은 외부 CDN·font·API·fetch 없이 로컬 파일로 열 수 있다. HTML은 실제 Spring Boot·MySQL·Redis·PortOne·SMTP를 실행하지 않는 학습용 정적 시뮬레이터다.
