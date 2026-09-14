// #63 공통 K6 Harness — 환경변수·실행 단계·실행 식별자를 한 곳에서 관리한다.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// smoke: 기능 검증(성능 결론에 쓰지 않음), load: 정상 처리 구간 확인, stress: 병목 전환점 탐색.
export const STAGE = __ENV.STAGE || 'smoke';

// 같은 실행(run)을 K6 tag·로그·Fixture 식별자에서 하나로 묶기 위한 식별자다.
// Prometheus/Grafana와 같은 시간축으로 묶을 때 이 값과 실행 시각을 함께 기록한다(Issue #63 "관측 시간축 연결").
export const RUN_ID = __ENV.RUN_ID || `local-${Date.now()}`;

export function scenarioTags(name, extra) {
    return Object.assign({ scenario: name, run_id: RUN_ID, stage: STAGE }, extra || {});
}
