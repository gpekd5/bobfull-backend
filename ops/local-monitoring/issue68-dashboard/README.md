# #68 기존 Dashboard의 Fake 호출 표시 검증

기존 Grafana Dashboard의 패널/PromQL/UID는 변경하지 않는다. 검증 중에만 기존 datasource UID `prometheus`의 URL을 이 폴더의 격리 Prometheus로 전환하고, 확인 후 **반드시 복원**한다. 로컬 Grafana의 다른 Prometheus 패널에도 일시 영향을 주므로 다른 로컬 측정과 동시에 실행하지 않는다.

## 격리 방식

- 기존 Prometheus: `http://prometheus:9090`, 호스트 9090, 기존 앱 8080 수집과 named volume 유지.
- 검증 Prometheus: `http://host.docker.internal:19068`, 호스트 127.0.0.1:19068. 기존과 같은 고정 image `prom/prometheus:v3.14.0`.
- 별도 tmpfs TSDB, Fake exporter 18068 한 target만 수집. `harness_scope=issue68_fake` 이외 sample 차단.
- **원래 metric 이름을 유지**한다. 기존 패널의 `gen_ai_*` 쿼리에 `issue68_test_*`는 맞지 않는다.
- 기존 MySQL datasource는 전환하지 않는다. 이 DB 패널은 Testcontainers DB를 조회하지 않는다.

## 재현

1. API Key/Live 승인을 사용하지 않는 Fake 검증이다. 기존 `.env`나 Live journal을 읽거나 초기화하지 않는다.

```sh
docker compose --env-file /dev/null -f ops/local-monitoring/issue68-dashboard/docker-compose.yml config --quiet
docker compose --env-file /dev/null -f ops/local-monitoring/issue68-dashboard/docker-compose.yml up -d
docker exec bobfull-issue68-dashboard-prometheus promtool check config /etc/prometheus/prometheus.yml
```

2. `http://localhost:3000/connections/datasources/edit/prometheus`에서 기존 URL `http://prometheus:9090`을 확인한다. **URL만** `http://host.docker.internal:19068`로 변경하고 Save & test. Name/UID/authentication/interval/기존 패널은 바꾸지 않는다.

3. 아래 테스트를 실행한다. 임의 외부 주소나 실제 key를 받지 않고 기존 Fake 전송 경계를 재사용한다.

```sh
env -u OPENAI_API_KEY \
  -u ISSUE68_LIVE_APPROVAL \
  -u ISSUE68_O0_LIVE_APPROVAL -u ISSUE68_O0_MANIFEST_SHA256 \
  -u ISSUE68_PILOT_LIVE_APPROVAL -u ISSUE68_PILOT_MANIFEST_SHA256 \
  ISSUE68_DASHBOARD_CAPTURE=true ISSUE68_DASHBOARD_HOLD_SECONDS=180 \
  ./gradlew :test --tests '*Issue68HoldObservabilityTest.기존_Dashboard*' \
  -PshowTestOutput --no-daemon --console=plain
```

4. `ISSUE68_DASHBOARD_READY` 로그 이후 **기존** Dashboard `http://localhost:3000/d/bobfull-ai-moderation/bobfull-ai-moderation-observability`를 연다. Last15m/refresh5s에서 표시를 확인한다. 쿼리 성공만으로 완료하지 않고 Dashboard 패널을 직접 확인한다.

   - LLM Call Count: 2
   - Estimated Cost: $0.000294 (Fake Usage 단가식; 실제 청구가 아님)
   - Token: input1800/output40/total1840. 기존 단위/반올림 때문에 input/total은 화면에서 `2 K`로 표시된다.
   - p95: 유한한 값. Fake 응답은 첫 scrape 확보를 위해 기다리므로 성능 측정에 사용할 수 없다.

5. 첫 완료값 count1을 수집한 뒤 두 번째 응답을 진행하고, Context 종료 전 Registry를 동결한다. 이후 180초(허용30~300초)는 추가 Provider 호출 없이 exporter만 유지한다. 테스트가 끝난 후에는 `build/issue68/dashboard-monitoring-*.json`의 시각을 사용해 **종료 이전의 절대 시간 구간**을 선택한다. 현재 시각으로 계속 조회하면 target 종료 후 stale/No data가 정상적으로 발생한다.

6. **성공/실패/중단과 관계없이 datasource URL을 `http://prometheus:9090`으로 복원하고 Save & test한다.** 이 UI 설정은 테스트가 자동 복원하지 않으므로 반드시 확인한다. 원래 앱 call count19가 다시 보이는 것은 복원 결과이지 Fake 검증 실패가 아니다.

7. Raw를 보존한 뒤 격리 컨테이너만 종료할 수 있다. `down`은 이 일시 TSDB를 제거하므로 필요한 관측 결과를 먼저 보존한다. 기존 monitoring project/volume 및 `.issue68-live`에는 적용하지 않는다.

```sh
docker compose --env-file /dev/null -f ops/local-monitoring/issue68-dashboard/docker-compose.yml down
```

같은 검증 TSDB에 이전 호출이 남아 있으면 테스트는 시작 시 거부한다. 재실행을 위해 기존 앱 데이터나 실제 journal을 지우지 않는다. 이 검증의 Fake Raw를 보존하고 **위의 격리 컨테이너만** 새로 기동한다.

## 검증 의미

이 테스트는 Fake Native2건, 실제 Spring AI/Adapter/Consumer/JPA 경로의 관측 연결을 확인한다. 실제 OpenAI 품질/비용/운영 성능, Batch 채택, Live 재개 승인에 해당하지 않는다. PROFANITY-14의 MEDIUM Fake 출력으로 Gate V1/V2 HOLD 보존도 확인하며 실패를 PASS로 바꾸지 않는다.
