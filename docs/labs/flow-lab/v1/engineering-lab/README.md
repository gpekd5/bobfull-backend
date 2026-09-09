# BobFull V1 Core Engineering Experiment Lab

V3는 실제 Spring·MySQL 실행기가 아닌 정적 의사결정 시뮬레이터다. 팀이 검토한 기술 대안의 조건을 바꾸고, 그 조건에서 예상되는 트랜잭션·DB·테스트 범위 결과를 비교한다. 실제 코드와 테스트 검증, Spring 동작 기준 예상 모델, 비교용 가상 대안을 화면 배지로 구분한다.

## 사용 방법

사례 탭을 고른 뒤 대안과 조건을 설정한다. 코드·DDL·테스트 미리보기가 즉시 바뀌며, `실험 실행`을 눌러야 예상 실행 경로와 최종 상태가 생성된다. `모든 대안 비교`는 같은 나머지 조건에서 각 대안의 Commit·Rollback·DB 상태·위험·실제 검증 여부를 나란히 보여준다. 설정 변경·사례 변경·초기화는 이전 결과를 비운다.

발표에서는 ① MANDATORY의 외부 트랜잭션 부재와 REQUIRES_NEW의 부분 성공, ② 동시 요청·soft delete 재생성에서 generated column UNIQUE의 의미, ③ WebMvcTest와 contextLoads의 보완 관계 순서로 시연한다.

라우팅 사례는 먼저 `수정 전 — 중복 매핑 존재`와 `WebMvcTest`를 선택해 충돌 미탐지 가능성을 확인한다. 이어 contextLoads와 Docker MySQL build를 선택하면 전체 Context에서의 Ambiguous mapping 탐지 범위를 비교할 수 있다. `수정 후 — 중복 매핑 제거`로 바꾼 뒤 contextLoads·Docker build를 다시 실행하면 정상 기동 모델과 PR #90 당시 79개 테스트 통과 기록을 구분해 표시한다. 수정 전 실패는 실제 재실행 결과가 아닌 PR 기록과 Spring 테스트 로딩 범위를 바탕으로 한 정적 모델이며, Docker build의 79개 통과만 PR #90 실제 검증 기록이다.

## 사례와 근거

- 결제·예약: 예약 확정 서비스 배지현 (jihyeon0930), PR #103. 전파 경계 리뷰·결제 완료 통합 검증 김현승 (hyeonseung-dev), PR #103 리뷰·#107. 관련 Issue #35, #93.
- 활성 회차 중복 방지: 김홍기 (gpekd5), PR #99, Issue #33. 실제 `TimeSlot`의 generated `active_start_at`과 UNIQUE, `TimeSlotRepositoryTest` 근거를 사용한다.
- 라우팅 충돌: 정용태 (sighingpotato, GitHub 표시명 배려하는마음), PR #90, Issue #31. 당시 Docker MySQL local clean build와 79개 테스트 통과는 역사적 PR 기록이며 현재 테스트 수가 아니다.

실제 측정 수치·성능 개선율·JVM 실행 결과를 임의로 만들지 않는다. 검색 실행 계획과 인덱스 후보는 동일 데이터·동일 쿼리에서 실제 측정 예정이며, Redis는 DB 병목 확인 후 TTL·무효화·장애 시 DB 조회로 대체되는지 검증한 경우에만 도입을 검토한다.
