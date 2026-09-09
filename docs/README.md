# BobFull docs

이 디렉터리는 BobFull Backend의 기준 문서, 운영 기록, Evidence, 학습 산출물을 역할별로 나누어 관리한다.

## Structure

| Directory | Role |
|---|---|
| [`10-product`](10-product/project-context.md) | 제품 방향, 프로젝트 정책, 역할, 상태 기준 |
| [`20-api`](20-api/README.md) | HTTP API, WebSocket, Actuator, Webhook 계약 |
| [`30-data`](30-data/erd.md) | 관계형 데이터 모델과 migration 기록 |
| [`40-architecture`](40-architecture/architecture.md) | 논리 아키텍처, 도메인 의존성, ADR |
| [`50-engineering`](50-engineering/code-convention.md) | 코드, 테스트, GitHub, Issue 규칙 |
| [`60-ai`](60-ai/ai-workflow.md) | AI 협업 절차, 구현/리뷰 가이드 |
| [`70-deployment`](70-deployment/aws-v1-backend.md) | AWS 배포, CI/CD, Blue-Green, 배포 설정 기준 |
| [`80-operations`](80-operations/monitoring-runbook.md) | 배포 이후 모니터링, 환불 정합성 등 운영 대응 절차 |
| [`90-testing`](90-testing/performance/k6-aws-test-environment.md) | 성능 테스트 환경과 k6 실행 기준 |
| [`100-learning`](100-learning/system-flow/v3/operations-system-flow/README.md) | 실제 코드와 Evidence 기반 System Flow 학습 산출물 |
| [`110-records`](110-records/evidence/v3/README.md) | Evidence, troubleshooting, TIL, Human 검토 기록 |
| [`120-templates`](120-templates/troubleshooting-template.md) | 문서 작성 양식 |

## Naming

- 최상위 `docs` 디렉터리는 번호 체계를 사용한다.
- 내부 디렉터리는 lowercase kebab-case를 사용한다.
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
