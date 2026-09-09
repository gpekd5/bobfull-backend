# Issue #198 DB Schema Migration Evidence

## Scope

Issue #198 verifies DB Schema Migration reproducibility and Blue-Green DB compatibility for the current BobFull backend schema management flow.

This issue does not introduce Flyway or Liquibase. The verified scope is the current Hibernate ddl-auto based schema management plus explicitly recorded SQL/procedures where needed.

| Item | Value |
|---|---|
| Base branch | `origin/develop` |
| Before SHA | `1fa1027cdc96e9543efa4b32abaf6f6f88f19975` |
| After SHA | `5552cbf2db341204ec3df782bd10cc6a4b0f5f83` |
| Production DB | `bobfull` |
| Migration tool | Not introduced |

## Environment ddl-auto

| Environment | File / Source | Effective ddl-auto |
|---|---|---|
| common | `src/main/resources/application.yml` | Not specified |
| local example | `src/main/resources/application-local.yml.example` | `${JPA_DDL_AUTO:update}` |
| test common | `src/test/resources/application.yml` | Not specified |
| performance | `src/test/resources/application-performance.yml` | `create-drop` |
| prod | `src/main/resources/application-prod.yml` | `${JPA_DDL_AUTO:validate}` |
| local docker compose app | `docker-compose.yml` | `${JPA_DDL_AUTO:-update}` |
| AWS deploy 대체값 | `scripts/aws/deploy-backend-v1.sh` | Parameter Store에 `jpa-ddl-auto`가 없으면 `validate` 사용 |

The production default was changed from `update` to `validate` after the operating RDS schema was verified against the current Entity mapping. Local and performance profiles were not changed by this issue.

## Current Schema Management

The repository does not contain `schema.sql`, `data.sql`, `import.sql`, or a `db/migration` directory.

The existing explicit SQL file is:

| File | Purpose |
|---|---|
| `docs/migrations/issue-138-shared-table-display-number.sql` | `shared_table.display_number` 기존 데이터 채움과 UNIQUE 적용을 위한 MySQL 8 1회성 migration |

Current policy:

- During development, Entity changes may use `ddl-auto=update` in local/test-like environments.
- In production, after schema changes are completed and verified, the application runs with `ddl-auto=validate`.
- Production application startup validates Entity mapping against the actual DB schema and does not automatically change the schema.
- Future schema changes should prefer additive changes during the Blue-Green coexistence window.
- Required SQL and application order must be recorded explicitly when schema changes are needed.
- Flyway/Liquibase remain out of scope for this issue.

## Production RDS Schema Snapshot

The production RDS schema snapshot was verified against DB `bobfull`.

Checked tables:

- `shared_table`
- `time_slot`
- `payment`
- `refund`
- `outbox_event`

### Key Schema Findings

| Table | Verified schema elements |
|---|---|
| `shared_table` | `display_number int NOT NULL`; `idx_shared_table_restaurant_id(restaurant_id)` exists |
| `time_slot` | `active_start_at` generated column exists; uses `start_at` when `deleted_at IS NULL`; generated column is `STORED`; `uk_time_slot_active_start(shared_table_id, active_start_at)` exists |
| `payment` | `portone_payment_id` unique; `reservation_participant_id` unique; `idx_payment_status_expires_at_id(payment_status, expires_at, payment_id)` exists |
| `refund` | `payment_id` unique; `idempotency_key` unique; `cancellation_id` unique; physical FK `refund.payment_id -> payment.payment_id` exists |
| `outbox_event` | `event_id` unique; `(event_type, aggregate_type, aggregate_id)` unique; `idx_outbox_event_status_next_attempt(status, next_attempt_at, outbox_event_id)` exists |

`information_schema.statistics` confirmed that the major Entity-defined indexes and unique constraints are reflected in production RDS.

Physical FKs confirmed:

| From | To |
|---|---|
| `chat_moderation_category.chat_moderation_id` | `chat_moderation.chat_moderation_id` |
| `refund.payment_id` | `payment.payment_id` |

## Issue #61 Index Verification

Target index:

```text
shared_table.idx_shared_table_restaurant_id(restaurant_id)
```

SQL:

```sql
EXPLAIN
SELECT *
FROM shared_table
WHERE restaurant_id = 1;
```

Observed plan:

| Field | Value |
|---|---|
| `type` | `ref` |
| `possible_keys` | `idx_shared_table_restaurant_id` |
| `key` | `idx_shared_table_restaurant_id` |
| `rows` | `2` |

Result: the #61 index exists in production RDS and is used by the actual execution plan.

## Empty DB Reproducibility

Temporary DB:

```text
bobfull_schema_test
```

Procedure:

1. Copied the existing production `backend.env`.
2. Changed only `DB_URL` to point to `bobfull_schema_test`.
3. Ran a temporary container separated from the production backend container.
4. Used the same Docker image as production.
5. Did not attach the temporary container to ALB.

Temporary runtime:

| Item | Value |
|---|---|
| Container | `bobfull-backend-schema-test` |
| Port | `18080` |
| DB | `bobfull_schema_test` |
| ALB connection | none |
| `JPA_DDL_AUTO` | `update` |
| Startup result | success |
| Startup time | `27.476s` |

Result:

- Application started successfully.
- 16 tables were created in the empty DB.
- Core DDL comparison matched production for `shared_table`, `time_slot`, `payment`, `refund`, and `outbox_event`.
- Indexes, unique constraints, FKs, and generated column definitions were reproduced.

The production DB `AUTO_INCREMENT` current values differ from the empty DB due to accumulated data and were not considered schema mismatch.

Cleanup:

- Temporary container removed.
- Temporary env file removed.
- `bobfull_schema_test` DB dropped.

## Existing DB Upgrade And Data Preservation

Temporary DB data:

- Inserted 4 existing rows into `shared_table`.

Additive column SQL:

```sql
ALTER TABLE shared_table
ADD COLUMN test_note VARCHAR(100) NULL;
```

Result:

- Column added successfully.
- Existing 4 rows remained.

Additive index SQL:

```sql
ALTER TABLE shared_table
ADD INDEX idx_test_display_number (display_number);
```

Result:

- Index added successfully.
- Existing `idx_shared_table_restaurant_id` remained.
- Existing row count remained `4`.

Conclusion: additive schema changes for nullable columns and indexes were applied without data loss in the verified temporary DB.

## Blue / Green Same RDS Compatibility

Images:

| Environment | Image SHA |
|---|---|
| Blue | `643685ecc4b4d30d6791051a795c6d6f4a5ff558` |
| Green | `93efc7e7592c96d0d3b76703f15e13b5e66dcfd3` |

Both versions used the same production RDS DB `bobfull`.

Read verification from each EC2:

```bash
curl -i http://localhost:8080/api/restaurants
```

| Environment | Result |
|---|---|
| Blue | HTTP 200 |
| Green | HTTP 200 |

Write verification:

Created a restaurant from the frontend.

| Field | Value |
|---|---|
| `restaurantId` | `5` |
| `name` | `테스트용식당` |
| `address` | `제주특별자치시 테스트구` |
| `category` | `카페` |
| `keyword` | `카페도예약해` |
| `depositPerPerson` | `5000` |

After the write, both Blue and Green localhost API calls returned `restaurantId=5` successfully.

Conclusion:

- Active environment write succeeded.
- Shared RDS stored the data.
- Blue old version read succeeded.
- Green new version read succeeded.

S3 presigned `imageUrl` values may differ by request time because `X-Amz-Date` and signature values are generated per request. The actual restaurant data was compatible.

## Additive Schema Rollback Compatibility

Status: PASS for nullable-column additive change.

Verification environment:

| Item | Value |
|---|---|
| Production DB | Not changed |
| Temporary DB | `bobfull_schema_rollback_test` |
| Blue old image | `643685ecc4b4d30d6791051a795c6d6f4a5ff558` |
| Green new image | `1fa1027cdc96e9543efa4b32abaf6f6f881f9975` |
| Production traffic state | Green 100 / Blue 0 |
| Production ALB / containers | Not changed |

1. Blue old image reproduced the base schema on the temporary DB with `JPA_DDL_AUTO=update`.

   | Check | Result |
   |---|---|
   | Temp container | `bobfull-schema-blue-update` |
   | Port | `18080` |
   | Startup | `Started BobfullBackendApplication in 28.35 seconds` |
   | Readiness | HTTP 200, `UP` |
   | `/api/restaurants` | HTTP 200, 0 rows on the empty temp DB |

2. A nullable test column was added to the same temporary DB in DBeaver.

   ```sql
   ALTER TABLE restaurant
   ADD COLUMN rollback_test_note VARCHAR(100) NULL;
   ```

   Verified column state:

   | Column | Type | Nullable |
   |---|---|---|
   | `rollback_test_note` | `varchar(100)` | `YES` |

3. Green new image started against the changed temporary DB with `JPA_DDL_AUTO=validate`.

   | Check | Result |
   |---|---|
   | Temp container | `bobfull-schema-green-validate` |
   | Port | `18081` |
   | Startup | `Started BobfullBackendApplication in 21.221 seconds` |
   | Hibernate validation | No schema validation failure |
   | Readiness | HTTP 200, `UP` |
   | `/api/restaurants` | HTTP 200 |

4. The additional column was kept, and Blue old image was restarted against the changed temporary DB with `JPA_DDL_AUTO=validate`.

   | Check | Result |
   |---|---|
   | Temp container | `bobfull-schema-blue-validate` |
   | Port | `18080` |
   | Startup | `Started BobfullBackendApplication in 23.261 seconds` |
   | Hibernate validation | No schema validation failure |
   | Readiness | HTTP 200, `UP` |
   | `/api/restaurants` | HTTP 200 |

Conclusion:

- nullable column 추가와 같은 하위 호환 Additive Schema 변경에 대해 구버전 Application이 변경된 DB Schema에서 `validate`로 정상 기동하고 대표 API를 정상 처리함을 확인했다.

Limit:

- 본 검증은 nullable column 추가와 같은 additive schema 변경에 대한 호환성 검증이며, 기존 column `MODIFY / RENAME / DROP`처럼 되돌리기 어려운 migration의 Rollback 안전성까지 확인한 것은 아니다.
- Not verified: existing column `MODIFY`, `RENAME`, `DROP`, type change, `NOT NULL` enforcement, and other destructive migration patterns.

Cleanup:

- Pending. Cleanup result will be recorded only after manual cleanup evidence is provided.

## Production ddl-auto update To validate

Previous Parameter Store value:

```text
/bobfull/prod/jpa-ddl-auto = update
```

Changed to:

```text
/bobfull/prod/jpa-ddl-auto = validate
```

State during change:

```text
Blue 100 / Green 0
```

Then the existing Blue-Green deployment automation was executed.

Observed environment values:

| Environment | Observed `JPA_DDL_AUTO` |
|---|---|
| Existing Blue | `update` because it was not restarted |
| Newly deployed Green | `validate` |

Green verification:

| Check | Result |
|---|---|
| `/actuator/health/readiness` | `UP` |
| `/api/restaurants` | normal |

Conclusion: the current production RDS schema matches the current Entity mapping well enough for Hibernate `validate`, and the application starts without automatically changing the DB schema.

## Schema SQL Failure / Fix / Re-run

Temporary DB:

```text
bobfull_schema_fail_test
```

Initial test table:

```sql
CREATE TABLE test_schema (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL
);
```

Failing SQL:

```sql
ALTER TABLE test_schema
ADD COLUMN name VARCHAR(100);
```

Result:

- Failed with a duplicate column error because `name` already existed.

Fixed SQL:

```sql
ALTER TABLE test_schema
ADD COLUMN description VARCHAR(100);
```

Result:

- Re-run succeeded.

Final columns:

| Column | Definition |
|---|---|
| `id` | `bigint NOT NULL PRIMARY KEY AUTO_INCREMENT` |
| `name` | `varchar(50) NOT NULL` |
| `description` | `varchar(100) NULL` |

Conclusion: after a schema SQL failure, the current DB state was inspected, the SQL was corrected, and re-run succeeded.

Cleanup:

- `bobfull_schema_fail_test` DB dropped.

## Final Status

Completed:

- Production schema snapshot
- Index / UNIQUE / FK verification
- #61 index existence and EXPLAIN usage
- Empty DB schema reproducibility
- Existing DB additive upgrade
- Existing data preservation
- Different Blue / Green versions sharing the same RDS
- Actual write followed by Blue and Green read
- Additive Schema rollback compatibility for a nullable column
- Production `ddl-auto` transition from `update` to `validate`
- Schema SQL failure / fix / re-run

Scope limits:

| Item | Reason |
|---|---|
| Existing column `MODIFY` / `RENAME` / `DROP` rollback compatibility | Not verified; destructive migration rollback safety is outside this verification |
| Type change / `NOT NULL` enforcement rollback compatibility | Not verified; only nullable-column additive change was tested |
| Rollback test cleanup | Pending until manual cleanup evidence is provided |

## Final Schema Management Policy

- Development phase: use `ddl-auto=update` while adding features or changing Entities in local/test-like environments.
- Final production phase: use `ddl-auto=validate` after schema changes are completed and verified.
- Production applications must not automatically mutate DB schema during startup.
- Production startup validates that Entity mapping and actual DB schema match.
- Future schema changes should prefer additive changes while Blue and Green versions coexist.
- Destructive changes such as immediate drop/rename must not be applied in one step during the rollback window.
- Required SQL and application procedures must be documented separately when schema changes are needed.
- Flyway/Liquibase are not introduced in Issue #198.
