# BobFull docs

이 디렉터리는 BobFull Backend의 기준 문서, 운영 기록, Evidence, 학습 산출물을 역할별로 나누어 관리한다.

## Structure

| Directory | Role |
|---|---|
| [`product`](product/project-context.md) | 제품 방향, 프로젝트 정책, 역할, 상태 기준 |
| [`api`](api/README.md) | HTTP API, WebSocket, Actuator, Webhook 계약 |
| [`data`](data/erd.md) | 관계형 데이터 모델과 migration 기록 |
| [`architecture`](architecture/architecture.md) | 논리 아키텍처, 도메인 의존성, ADR |
| [`engineering`](engineering/code-convention.md) | 코드, 테스트, GitHub, Issue 규칙 |
| [`ai`](ai/ai-workflow.md) | AI 협업 절차, 구현/리뷰 가이드, Human 검토 기록 |
| [`operations`](operations/monitoring-runbook.md) | 배포, 인프라, 모니터링, 운영 runbook |
| [`evidence`](evidence/v3/README.md) | V3 실측 Evidence와 raw 산출물 |
| [`labs`](labs/flow-lab/v3/operations-flow-lab/README.md) | Flow Lab 등 학습/발표용 정적 산출물 |
| [`troubleshooting`](troubleshooting/README.md) | 문제 원인, 해결 과정, 재발 방지 기록 |
| [`templates`](templates/troubleshooting-template.md) | 문서 작성 양식 |
| [`records`](records/til/README.md) | TIL 등 회고성 기록 |

## Naming

- 디렉터리는 lowercase kebab-case를 사용한다.
- 사람이 직접 관리하는 Markdown 파일은 lowercase kebab-case를 사용한다.
- `README.md`는 디렉터리 인덱스 예외로 유지한다.
- ADR은 기존 번호 체계를 유지한다.
- migration 파일은 기존 issue 번호 기반 형식을 유지한다.
- Evidence raw, log, json 등 자동 생성 산출물은 원본 이름을 우선 보존한다.

## Validation

문서 이동이나 이름 변경 뒤에는 Markdown 상대 링크를 검사한다.

```powershell
powershell.exe -ExecutionPolicy Bypass -File scripts\docs\check-markdown-links.ps1
```

검사 대상은 Markdown 본문 링크이며 fenced code block과 inline code 안의 예시는 제외한다.
