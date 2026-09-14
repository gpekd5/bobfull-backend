# Issue #37 Repository Root 구조 및 문서 배치 정리 Evidence

## 1. 검증 목적

- 운영 자산을 `ops` 아래로 모으고 AI 문서를 역할별 경로로 재배치한다.
- 파일 이동 뒤 문서, Workflow, 스크립트와 설정의 참조 경로가 유효한지 확인한다.
- Java package, API, DB, Transaction, Security와 운영 기능 자체가 바뀌지 않았음을 확인한다.
- 동일한 SonarQube 환경에서 파일 이동 전후 신규 정적 분석 회귀가 없는지 확인한다.

검증 대상은 [Issue #37](https://github.com/gpekd5/bobfull-backend/issues/37)이며 실제 AWS 배포, 외부 부하 테스트와 운영 Monitoring 기동은 포함하지 않는다.

## 2. 비교 기준

| 구분 | Git SHA | 설명 |
|---|---|---|
| Before | `fca74392d4003d55bf8f9d7ff971e29e1614c82e` | 최신 `develop`, PR #36 merge 상태 |
| After | `21eaae098711f17140f41bb3c36d7da985516d08` | 운영 자산과 AI 문서 이동, 참조 경로 정리 |

## 3. 구조 Before / After

### Before

```text
repository-root/
├─ k6/                         22 files
├─ monitoring/                 10 files
├─ scripts/
│  ├─ aws/                      6 files
│  └─ docs/                     1 file
├─ skills/                      3 files
├─ docs/060-ai/                 3 flat files
├─ lambda/
├─ AGENTS.md
└─ CLAUDE.md
```

### After

```text
repository-root/
├─ ops/
│  ├─ load-test/               22 files
│  ├─ monitoring/              10 files
│  ├─ deployment/aws/           6 files
│  └─ tools/                    1 file
├─ docs/060-ai/
│  ├─ workflow/                 1 file
│  ├─ development/              1 file
│  ├─ review/                   1 file
│  └─ tasks/                    3 files
├─ lambda/
├─ AGENTS.md
└─ CLAUDE.md
```

`git ls-tree -d --name-only <SHA>` 기준 추적 최상위 디렉터리는 9개에서 6개로 줄었다. 이동 대상 45개 파일의 개수와 내용은 유지했고, `k6`, `monitoring`, `scripts`, `skills` 빈 잔재 디렉터리는 제거했다.

## 4. 이동 및 참조 변경

| Before | After | 파일 수 |
|---|---|---:|
| `k6/` | `ops/load-test/` | 22 |
| `monitoring/` | `ops/monitoring/` | 10 |
| `scripts/aws/` | `ops/deployment/aws/` | 6 |
| `scripts/docs/check-markdown-links.ps1` | `ops/tools/check-markdown-links.ps1` | 1 |
| `docs/060-ai/ai-workflow.md` | `docs/060-ai/workflow/ai-workflow.md` | 1 |
| `docs/060-ai/ai-implementation-guide.md` | `docs/060-ai/development/ai-implementation-guide.md` | 1 |
| `docs/060-ai/ai-review-guide.md` | `docs/060-ai/review/ai-review-guide.md` | 1 |
| `skills/*/SKILL.md` | `docs/060-ai/tasks/*/TASK.md` | 3 |

Before에서 기존 저장소 경로 또는 flat AI 문서 경로를 참조한 파일은 45개였다. AGENTS, README, GitHub Issue/PR template, CI/CD Workflow, 배포·운영·테스트 문서, 기존 Evidence, AWS script와 k6 실행 예시를 새 경로로 변경했다.

### Codex Task Guide 판단

2026-09-15 확인한 [공식 Codex Agent Skills 문서](https://developers.openai.com/codex/skills/)는 저장소 Skill 자동 탐색 위치를 `.agents/skills/*/SKILL.md`로 안내한다. 기존 BobFull의 root `skills/*/SKILL.md`는 현재 Codex 자동 탐색 목록에 없었고 `AGENTS.md`가 명시적으로 읽도록 라우팅하던 저장소 작업 문서였다.

따라서 세 문서는 자동 Skill로 가장하지 않고 `docs/060-ai/tasks/*/TASK.md`로 이동했다. `AGENTS.md`와 관련 가이드의 라우팅도 `Task Guide`로 명시했다. 향후 자동 발견 Skill이 필요하면 `.agents/skills` 도입을 별도 Issue에서 결정해야 한다.

### 유지한 root 파일

- `AGENTS.md`: BobFull AI 작업의 공통 진입점이므로 root 유지
- `CLAUDE.md`: `AGENTS.md`를 참조하는 5줄짜리 Claude Code 호환 진입점이므로 축소 또는 제거 없이 유지
- `lambda/`: 독립 배포 코드이므로 root 유지

## 5. 경로 정합성 검증

| 항목 | 명령 또는 측정 방법 | 결과 |
|---|---|---|
| old directory | `Test-Path k6, monitoring, scripts, skills` | 모두 `False` |
| 목표 경로 | 이동 대상 10개 경로 `Test-Path` | 모두 `True` |
| old path 참조 | `rg`로 기존 skills, flat AI, scripts, k6 자산, monitoring 자산 경로 검색 | 실행 경로 참조 0 |
| Diff 공백 오류 | `git diff --check` | PASS |
| Markdown 상대 링크 | `powershell.exe -ExecutionPolicy Bypass -File ops/tools/check-markdown-links.ps1` | 124 files, 189 links, broken 0 |
| AWS shell 문법 | Git Bash `bash -n ops/deployment/aws/*.sh` | 6/6 PASS |
| AWS 내부 경로 | Workflow와 AWS script의 `ops/deployment/aws/*.sh` 참조를 `Test-Path`로 확인 | 4/4 존재 |
| Monitoring Compose | `docker compose --env-file ops/monitoring/.env.example -f ops/monitoring/docker-compose.yml config --quiet` | PASS |
| Monitoring bind source | Compose의 상대 bind source를 compose 파일 위치 기준으로 확인 | 3/3 존재 |
| k6 scenario import/config | `k6 inspect` 실행, `restaurant-view-hotpath.js`는 필수 `TARGET` 적용 | 13/13 PASS |
| k6 hotpath 분기 | `TARGET=detail`, `TARGET=sessions` 각각 inspect | 2/2 PASS |

`k6/http`, `k6/execution`, `k6/metrics`, `k6/crypto`, `k6/encoding`은 저장소 경로가 아니라 k6 런타임 모듈명이므로 변경하지 않았다. `/opt/bobfull-monitoring/.env`도 Monitoring EC2의 외부 절대경로이므로 유지했다.

## 6. Build 및 동작 보존 Guardrail

| 항목 | Before | After | 결과 |
|---|---|---|---|
| Java production diff | 없음 | 없음 | 유지 |
| Java package 변경 | 없음 | 없음 | 유지 |
| Lambda diff | 없음 | 없음 | 유지 |
| `compileJava` | 기준 코드 compile 가능 | `BUILD SUCCESSFUL` | PASS |
| `compileTestJava` | 기준 코드 compile 가능 | `BUILD SUCCESSFUL` | PASS |
| `clean build` | 기준 develop build 가능 | `BUILD SUCCESSFUL in 3m 51s`, 14 tasks | PASS |
| Lambda build/test | 기존 Gradle 하위 프로젝트 | clean build에 포함해 PASS | PASS |

`src/main/java`와 `lambda`에는 Diff가 없다. Java 변경은 `PortOnePerformanceWebhookSigningContractTest`의 k6 파일 경로 주석 2곳뿐이며 테스트 로직은 바뀌지 않았다. 따라서 API, DB schema, Transaction, Security, CI/CD 방식, monitoring 구성 내용, k6 시나리오 내용과 Lambda 기능을 변경하지 않았다.

## 7. SonarQube Before / After

동일한 로컬 SonarQube 프로젝트 `bobfull-backend`, Gradle Sonar plugin과 현재 `SONAR_TOKEN`/`SONAR_HOST_URL`을 사용했다. 토큰 값은 출력하거나 Evidence에 기록하지 않았다.

```powershell
.\gradlew.bat clean classes testClasses sonar `
  "-Dsonar.host.url=$env:SONAR_HOST_URL" `
  "-Dsonar.token=$env:SONAR_TOKEN" `
  "-Dsonar.scm.revision=<검증 SHA>" `
  --no-daemon
```

| 유형 | Before | After | 변화 |
|---|---:|---:|---:|
| 전체 | 326 | 326 | 0 |
| Bug | 14 | 14 | 0 |
| Vulnerability | 1 | 1 | 0 |
| Code Smell | 311 | 311 | 0 |

- Before/After 모두 Sonar task `SUCCESS`
- Issue key 추가 0, 제거 0
- rule, severity, type, component, line, message 조합 추가 0, 제거 0
- 파일 이동으로 인한 재키잉 0
- Issue #37로 발생한 신규 Sonar 회귀 0

## 8. 검증 한계

- 실제 AWS 배포와 SSM 실행은 수행하지 않았다. shell 문법과 저장소 내부 경로만 검증했다.
- Monitoring EC2에서 Prometheus/Grafana를 실제 기동하거나 Slack 알림을 전송하지 않았다. Compose 해석과 bind source만 검증했다.
- k6 시나리오는 `inspect`로 import와 구성 로딩만 확인했다. 실제 API 호출 또는 성능 측정은 수행하지 않았다.
- GitHub Actions는 로컬에서 실행하지 않았다. Workflow의 path filter와 실행 경로를 정적으로 확인했다.
- SonarQube는 로컬 환경에서 분석했으며 기존 326건을 이번 구조 정리에서 수정하지 않았다.

## 9. Issue #38 전달 사항

이번 Issue는 repository root와 문서/운영 자산 소유권만 다뤘다. Java package 분석이나 이동은 수행하지 않았으며 새 package 관련 판단을 만들지 않았다. 기존 package 후속 범위는 Issue #38에서 최신 `develop` 기준으로 다시 확인한다.
