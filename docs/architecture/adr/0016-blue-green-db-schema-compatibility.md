# ADR 0016: Blue-Green 공존 기간의 DB Schema 호환 전략

- 상태: `Accepted`
- 작성일: `2026-08-18`
- 관련 Issue: #198
- 주요 Evidence: `docs/evidence/v3/db-schema-migration/README.md`

## 배경

BobFull의 Blue-Green 배포는 Blue와 Green 애플리케이션이 같은 RDS MySQL을 공유한다. 애플리케이션 Traffic은 ALB Listener weight로 되돌릴 수 있지만, DB Schema 변경은 Traffic Rollback만으로 자동 복구되지 않는다.

예를 들어 Green 배포 과정에서 Blue가 사용하는 컬럼을 바로 drop/rename하면, Green 문제 발견 후 Traffic을 Blue로 되돌려도 Blue가 현재 DB Schema와 호환되지 않아 실제 서비스 복구에 실패할 수 있다.

## 문제

다음 요구를 함께 만족할 Schema 관리 전략이 필요했다.

- Blue/Green 구·신 버전이 같은 RDS에서 일정 기간 공존
- Green 검증 실패 시 Blue Traffic Rollback 가능
- 기존 데이터 보존
- 운영 애플리케이션 기동 시 자동 Schema 변경 방지
- 프로젝트 일정과 현재 운영 규모에서 Migration Tool 신규 도입 비용 제한

## 고려한 대안

1. **Production `ddl-auto=update` 지속** — 편하지만 애플리케이션 기동이 운영 Schema를 자동 변경해 변경 시점과 Rollback 경계가 불명확하다.
2. **Flyway/Liquibase 즉시 도입** — 명시적 versioned migration 장점이 있지만 프로젝트 최종 단계에서 신규 운영 도구·migration baseline을 추가해야 한다.
3. **Production `ddl-auto=validate` + 필요한 SQL 명시 + Blue-Green 기간 additive change 우선** — 현재 구조를 유지하면서 App startup의 자동 Schema mutation을 막고 Rollback 호환 원칙을 명시한다.

## 결정

대안 3을 채택한다.

### 환경별 기준

- local/test-like 개발 환경: 필요 시 `ddl-auto=update`
- performance: `create-drop`
- production: **`ddl-auto=validate`**

운영 애플리케이션은 기동 시 Entity mapping과 실제 DB Schema의 일치 여부만 검증하며 자동으로 Schema를 변경하지 않는다.

### Blue-Green Schema 변경 원칙

Rollback 가능 기간에는 다음 순서를 기본으로 한다.

```text
Additive Schema 변경
→ 기존 Blue 호환 확인
→ Green 배포 / validate / 대표 API 검증
→ Blue/Green 같은 RDS에서 공존 확인
→ Traffic Switch
→ Rollback 가능 기간 유지
→ 안정화 이후 필요 시 후속 정리
```

다음과 같은 파괴적 변경은 Rollback Window에서 한 번에 수행하지 않는다.

- 기존 컬럼 즉시 `DROP`
- 기존 컬럼 즉시 `RENAME`
- 구버전이 사용하는 table/column 제거
- 호환성을 깨는 type/constraint 변경
- 기존 버전이 읽지 못하는 `NOT NULL` 강제 등

Schema 변경이 필요하면 필요한 SQL과 적용 순서를 별도 문서/절차로 명시한다.

Flyway/Liquibase는 #198 범위에서 도입하지 않는다. 이는 영구적으로 배제한다는 뜻이 아니라 현재 프로젝트에서 검증한 운영 기준이다.

## 선택 이유

#198에서 현재 RDS Schema와 Entity mapping을 확인한 뒤 production `ddl-auto`를 `update`에서 `validate`로 전환했고, Green이 `validate` 상태에서 정상 기동하는 것을 확인했다.

또한 임시 DB에서 nullable column 추가와 index 추가 같은 additive change의 데이터 보존을 확인하고, 추가 nullable column이 있는 동일 DB에 대해 Green 신버전과 Blue 구버전이 모두 `validate`로 정상 기동·대표 API 처리하는 것을 검증했다.

## 검증 근거

- production RDS의 주요 Index/UNIQUE/FK/생성 컬럼 확인
- 빈 DB에서 같은 production image와 `ddl-auto=update`로 16개 table 재현
- 기존 DB에 nullable column/index additive change 후 기존 데이터 보존
- Blue/Green 서로 다른 이미지가 동일 production RDS에서 `/api/restaurants` HTTP 200
- 실제 식당 write 이후 Blue/Green 양쪽에서 동일 데이터 read
- nullable column 추가 후 Green `validate` 기동 성공
- 같은 변경 Schema에서 Blue 구버전 `validate` 재기동 성공
- production Parameter Store `jpa-ddl-auto=update → validate` 전환 후 Green readiness/API 정상
- 실패 SQL 원인 확인 → 수정 → 재실행 성공

## 장점

- Production App startup이 Schema를 자동 변경하지 않는다.
- App Traffic Rollback과 DB 호환성을 함께 고려한다.
- 기존 프로젝트 구조를 크게 바꾸지 않고 Schema 변경 절차를 명시적으로 통제한다.
- additive change를 통해 구/신 버전 공존 가능성을 높인다.

## 단점과 위험

- Flyway/Liquibase 같은 versioned migration history가 없어 Schema 변경 순서와 적용 이력을 문서·운영 절차로 직접 관리해야 한다.
- destructive migration의 자동 안전장치가 없다.
- #198에서 검증한 Rollback 호환성은 nullable additive column 중심이며 `MODIFY/RENAME/DROP/type change/NOT NULL` 안전성을 검증하지 않았다.
- 수동 SQL 적용 실수 가능성이 남는다.

## 검증 방법

- production Schema snapshot과 Entity mapping 대조
- 빈 DB 재현
- 기존 DB additive upgrade 및 데이터 보존
- Blue/Green 동일 RDS 공존 검증
- Green→Blue rollback을 가정한 구버전 `validate` 호환 검증
- Schema SQL 실패/수정/재실행 검증

상세 조건과 한계는 #198 Evidence를 따른다.

## 재검토 조건

- Schema 변경 빈도가 늘어나 수동 SQL 순서 관리가 반복적으로 문제가 될 때
- 여러 개발자가 독립적으로 migration을 추가해 version 관리가 필요할 때
- destructive migration을 안전하게 단계화해야 하는 요구가 커질 때
- 다수 환경 간 Schema drift 관리 비용이 증가할 때

이 경우 Flyway/Liquibase 등 versioned migration 도구를 새 ADR에서 비교한다.
