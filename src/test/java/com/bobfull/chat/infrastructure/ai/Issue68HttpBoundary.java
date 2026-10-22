package com.bobfull.chat.infrastructure.ai;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 무과금 전용. 실제 Provider URL/인증정보를 받지 않으며 loopback 외 목적지는 전송 전에 차단한다.
public final class Issue68HttpBoundary implements AutoCloseable {
    private static final JsonMapper JSON = new JsonMapper();
    private static final com.knuddels.jtokkit.api.Encoding ENCODING = Encodings.newLazyEncodingRegistry()
            .getEncoding(EncodingType.O200K_BASE);
    private final URI endpoint;
    private final Issue68Budget budget;
    private final OkHttpClient transport;
    private final boolean live;
    private final int maximumHttpAttempts;
    private final java.util.concurrent.atomic.AtomicInteger admittedExchanges = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean stopped;
    private final java.util.Set<String> liveModes = new java.util.HashSet<>();
    private java.util.function.BiConsumer<String, Object> checkpoint = (name, value) -> { };
    private final ThreadLocal<Logical> current = new ThreadLocal<>();
    private final AtomicLong sequence = new AtomicLong();
    private final List<Logical> calls = new CopyOnWriteArrayList<>();
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    private final List<Issue68SdkUsage.Entry> sdkEntries = new CopyOnWriteArrayList<>();
    private java.util.function.Supplier<io.micrometer.core.instrument.MeterRegistry> registryFactory =
            io.micrometer.core.instrument.simple.SimpleMeterRegistry::new;
    private java.util.function.Consumer<io.micrometer.core.instrument.MeterRegistry> metricSnapshot = registry -> { };
    private Issue68ExecutionGate o0Plan;
    private Runnable beforeExchange = () -> { };
    private Runnable authorization = () -> { };
    private boolean fakeRiskBoundaryCandidate;
    private boolean inspectionOnly;

    static Issue68HttpBoundary a1N1(URI endpoint, Issue68Budget budget, Issue68A1N1Plan plan,
            boolean live, Runnable authorization) {
        var boundary = new Issue68HttpBoundary(endpoint, budget, live, Issue68A1N1Plan.HTTP_LIMIT, plan);
        boundary.authorization(authorization);
        return boundary;
    }

    void inspectionOnly() { inspectionOnly = true; }
    public boolean a1N1() { return o0Plan instanceof Issue68A1N1Plan; }
    public boolean usageBenchmark() { return o0Plan instanceof Issue68UsageBenchmarkPlan; }

    static Issue68HttpBoundary usageBenchmark(URI endpoint, Issue68Budget budget, Issue68UsageBenchmarkPlan plan,
            boolean live, Runnable authorization) {
        var boundary = new Issue68HttpBoundary(endpoint, budget, live, plan.stage().httpLimit, plan);
        boundary.authorization(authorization);
        return boundary;
    }

    // SDK 밖의 하위 exchange 상한도 같은 send 경계로 검증한다. 실 Provider 목적지에는 사용할 수 없다.
    Response rehearsalExchange(Request request) throws IOException {
        if (live || !(a1N1() || usageBenchmark())) throw new IOException("ISSUE68_REHEARSAL_LOOPBACK_ONLY");
        return send(request);
    }

    // A1 후보는 loopback에서만 검증한다. 기존 Live factory/승인 Manifest는 변경하지 않는다.
    static Issue68HttpBoundary riskBoundaryPreparation(URI endpoint, Issue68Budget budget) {
        var boundary = new Issue68HttpBoundary(endpoint, budget);
        boundary.fakeRiskBoundaryCandidate = true;
        return boundary;
    }

    static Issue68HttpBoundary o0Live(Issue68Budget budget, Issue68O0Plan plan, Runnable authorization) {
        var boundary = new Issue68HttpBoundary(URI.create("https://api.openai.com/v1"), budget, true,
                Issue68Budget.O0_HTTP_LIMIT, java.util.Objects.requireNonNull(plan));
        boundary.authorization = java.util.Objects.requireNonNull(authorization);
        return boundary;
    }

    static Issue68HttpBoundary expandedLivePilot(Issue68Budget budget, Issue68ExpandedPlan plan, Runnable authorization) {
        if (plan.stage() != Issue68ExpandedPlan.Stage.PILOT) {
            throw new IllegalArgumentException("ISSUE68_PILOT_ONLY");
        }
        var boundary = new Issue68HttpBoundary(URI.create("https://api.openai.com/v1"), budget, true,
                Issue68ExpandedPlan.Stage.PILOT.httpLimit, plan);
        boundary.authorization = java.util.Objects.requireNonNull(authorization);
        return boundary;
    }

    public void requireExpandedUnit(Issue68ExpandedPlan.Unit unit) {
        requireCredentialApproval();
        if (o0Plan instanceof Issue68UsageBenchmarkPlan plan) {
            if (unit.equals(plan.active())) return;
            stop();
            throw new IllegalArgumentException("ISSUE68_BENCHMARK_UNIT_NOT_AUTHORIZED");
        }
        if (!(o0Plan instanceof Issue68ExpandedPlan plan) || !unit.equals(plan.active())
                || live && unit.stage() != Issue68ExpandedPlan.Stage.PILOT) {
            stop();
            throw new IllegalArgumentException("ISSUE68_EXPANDED_UNIT_NOT_AUTHORIZED");
        }
    }

    // loopback 리허설 전용이며 HTTPS/credential 허용을 넓히지 않는다.
    public static Issue68HttpBoundary o0Preparation(URI endpoint, Issue68Budget budget, Issue68O0Plan plan) {
        var boundary = new Issue68HttpBoundary(endpoint, budget, false, Issue68Budget.O0_HTTP_LIMIT);
        boundary.o0Plan = java.util.Objects.requireNonNull(plan);
        return boundary;
    }

    // 승인된 준비 범위는 Fake HTTP뿐이다. HTTPS 확장 factory나 key 읽기 경로를 추가하지 않는다.
    public static Issue68HttpBoundary expandedPreparation(URI endpoint, Issue68Budget budget, Issue68ExpandedPlan plan) {
        var boundary = new Issue68HttpBoundary(endpoint, budget, false, plan.stage().httpLimit);
        boundary.o0Plan = java.util.Objects.requireNonNull(plan);
        return boundary;
    }

    public void beforeExchange(Runnable check) {
        beforeExchange = java.util.Objects.requireNonNull(check);
    }

    public Issue68HttpBoundary(URI endpoint, Issue68Budget budget) {
        this(endpoint, budget, false);
    }

    Issue68HttpBoundary(URI endpoint, Issue68Budget budget, boolean live) {
        this(endpoint, budget, live, live ? Issue68LiveSession.RESUME_HTTP_LIMIT : Integer.MAX_VALUE);
    }

    Issue68HttpBoundary(URI endpoint, Issue68Budget budget, boolean live, int maximumHttpAttempts) {
        this(endpoint, budget, live, maximumHttpAttempts, null);
    }

    private Issue68HttpBoundary(URI endpoint, Issue68Budget budget, boolean live, int maximumHttpAttempts,
            Issue68ExecutionGate plan) {
        if (live ? !endpoint.toString().equals("https://api.openai.com/v1")
                : !"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1 || !"/v1".equals(endpoint.getPath())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("ISSUE68_LIVE_ENDPOINT_NOT_AUTHORIZED");
        }
        this.live = live;
        if (maximumHttpAttempts < 1 || (live && maximumHttpAttempts > (plan == null
                ? Issue68LiveSession.RESUME_HTTP_LIMIT : plan instanceof Issue68UsageBenchmarkPlan benchmark
                        ? benchmark.stage().httpLimit : plan instanceof Issue68ExpandedPlan
                        ? Issue68ExpandedPlan.Stage.PILOT.httpLimit : Issue68Budget.O0_HTTP_LIMIT))) {
            throw new IllegalArgumentException("ISSUE68_HTTP_LIMIT_INVALID");
        }
        this.maximumHttpAttempts = maximumHttpAttempts;
        this.endpoint = endpoint;
        this.budget = budget;
        this.o0Plan = plan;
        transport = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true)
                .callTimeout(Duration.ofSeconds(60)).addNetworkInterceptor(this::exchange).build();
    }

    public static final class Logical {
        public final long number;
        public final List<String> caseIds;
        public final boolean batch;
        public final long started = System.nanoTime();
        public volatile long ended;
        public volatile boolean failed;
        public volatile String requestSha256;
        public volatile int inputEstimate;
        public volatile int cap;

        Logical(long number, List<String> caseIds, boolean batch) {
            this.number = number;
            this.caseIds = List.copyOf(caseIds);
            this.batch = batch;
        }
    }

    public record Attempt(int number, long logical, int status, Issue68Budget.Usage usage,
                          long reservedNanos, long latencyNanos, String contentEncoding, String settlement) {
    }

    public synchronized Logical begin(List<String> caseIds, boolean batch) {
        if (stopped) {
            throw new IllegalStateException("ISSUE68_EXECUTION_STOPPED");
        }
        if (live && o0Plan == null) {
            var permitted = Issue68LiveSession.smokeCases().stream().map(Issue68EvaluationContract.Case::id).toList();
            if (!(batch ? caseIds.equals(permitted) : caseIds.equals(permitted.subList(0, 1)))
                    || !liveModes.add(batch ? "batch" : "native")) {
                stopped = true;
                throw new IllegalStateException("ISSUE68_LOGICAL_LIMIT");
            }
        }
        if (current.get() != null || caseIds.isEmpty() || caseIds.stream().distinct().count() != caseIds.size()) {
            throw new IllegalStateException("ISSUE68_LOGICAL_SCOPE_INVALID");
        }
        if (o0Plan != null) {
            o0Plan.begin(caseIds, batch);
        }
        Logical logical = new Logical(sequence.incrementAndGet(), caseIds, batch);
        current.set(logical);
        calls.add(logical);
        return logical;
    }

    public void end(Logical logical, boolean failed) {
        logical.failed = failed;
        logical.ended = System.nanoTime();
        current.remove();
        if (live && failed) {
            stopped = true;
        }
        if (o0Plan != null && (failed || attempts.stream()
                .anyMatch(a -> a.logical() == logical.number && !a.settlement().equals("SETTLED")))) {
            o0Plan.stop();
        }
    }

    public boolean live() {
        return live;
    }

    public void committedResult(Issue68EvaluationContract.Case c,
            com.bobfull.chat.application.result.ModerationResult actual, boolean committed) {
        if (o0Plan != null) {
            o0Plan.committed(c, actual, committed);
            var progress = new java.util.LinkedHashMap<String, Object>();
            progress.put("caseId", c.id());
            progress.put("actual", actual);
            progress.put("dbCommitted", committed);
            progress.put("v1v2", Issue68GateV2.evaluate(c, actual, committed));
            progress.put("executionHolds", o0Plan.holds());
            checkpoint("gate-progress", progress);
        }
    }

    public void requireCredentialApproval() {
        authorization.run();
        if (o0Plan != null) {
            o0Plan.requireActive();
        }
    }

    public boolean o0() {
        return o0Plan != null;
    }

    public boolean o0Held() {
        return o0Plan != null && o0Plan.stopped();
    }

    void authorization(Runnable check) {
        authorization = java.util.Objects.requireNonNull(check);
    }

    public void sdkUsage(Issue68SdkUsage.Entry entry) {
        sdkEntries.add(entry);
        checkpoint("sdk-usage", entry); // 정상 결과 조립 전에 HOLD되어도 SDK 모델/Usage를 남긴다.
        if (o0Plan != null) {
            var completed = attempts.stream().filter(a -> a.logical() == entry.logical() && a.status() == 200).toList();
            boolean usageMatches = completed.size() == 1 && entry.usage().equals(completed.get(0).usage());
            if (!usageMatches) {
                o0Plan.stop();
                for (var attempt : completed) {
                    if (attempt.settlement().equals("SETTLED")) {
                        budget.retainDisputed(attempt.number());
                        attempts.set(attempts.indexOf(attempt), new Attempt(attempt.number(), attempt.logical(),
                                attempt.status(), attempt.usage(), attempt.reservedNanos(), attempt.latencyNanos(),
                                attempt.contentEncoding(), "DISPUTED_KEEP"));
                    }
                }
                checkpoint("usage-conflict", attempts());
            }
            if (!"gpt-4o-mini-2024-07-18".equals(entry.model())) {
                o0Plan.stop();
            }
        }
    }

    public void checkpoints(java.util.function.BiConsumer<String, Object> sink) {
        checkpoint = sink;
    }

    public void checkpoint(String name, Object value) {
        try {
            checkpoint.accept(name, value);
        } catch (RuntimeException failure) {
            stopped = true;
            throw new IllegalStateException("ISSUE68_CHECKPOINT_FAILED");
        }
    }

    public void stop() {
        stopped = true;
        if (o0Plan != null) {
            o0Plan.stop();
        }
        transport.dispatcher().cancelAll();
    }

    public List<Logical> calls() {
        return List.copyOf(calls);
    }

    public List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    public List<Issue68SdkUsage.Entry> sdkEntries() {
        return List.copyOf(sdkEntries);
    }

    // 로컬 Fake 수집 검증만 Registry를 교체할 수 있다. Live 경로의 Registry/전송 계약은 넓히지 않는다.
    public void fakeMonitoring(java.util.function.Supplier<io.micrometer.core.instrument.MeterRegistry> factory,
            java.util.function.Consumer<io.micrometer.core.instrument.MeterRegistry> snapshot) {
        if (live || !calls.isEmpty()) throw new IllegalStateException("ISSUE68_FAKE_MONITORING_ONLY");
        registryFactory = java.util.Objects.requireNonNull(factory);
        metricSnapshot = java.util.Objects.requireNonNull(snapshot);
    }

    io.micrometer.core.instrument.MeterRegistry meterRegistry() {
        return registryFactory.get();
    }

    public void snapshotMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        metricSnapshot.accept(registry);
    }

    public long currentLogicalNumber() {
        Logical logical = current.get();
        if (logical == null) {
            throw new IllegalStateException("ISSUE68_UNTRACKED_SDK_USAGE");
        }
        return logical.number;
    }

    public OpenAiHttpClientBuilderCustomizer customizer() {
        return builder -> builder.interceptor(chain -> send(chain.request()));
    }

    private Response send(Request request) throws IOException {
        if (inspectionOnly) throw new IOException("ISSUE68_INSPECTION_NO_SEND");
        verifyForTransmission(request);
        if (live && o0Plan == null) {
            Issue68LiveSession.verifyFrozenRequest(current.get().batch, current.get().requestSha256);
        }
        return transport.newCall(request).execute();
    }

    private void verifyForTransmission(Request request) throws IOException {
        try {
            verifyRequest(request);
        } catch (IOException | RuntimeException failure) {
            if (o0Plan instanceof Issue68ExpandedPlan || a1N1() || usageBenchmark()) stop();
            throw failure;
        }
    }

    void verifyRequest(Request request) throws IOException {
        requireCredentialApproval(); // Fake에서는 key를 읽지 않는 no-op 승인 경계다.
        int port = live ? 443 : endpoint.getPort();
        if (stopped || !request.url().host().equals(endpoint.getHost()) || request.url().port() != port
                || !request.url().encodedPath().equals("/v1/chat/completions")
                || request.url().query() != null || !request.url().username().isEmpty() || !request.url().password().isEmpty()
                || !request.method().equals("POST") || !request.url().scheme().equals(live ? "https" : "http")) {
            throw new IllegalStateException("ISSUE68_HTTP_DESTINATION_BLOCKED");
        }
        Logical logical = current.get();
        if (logical == null) {
            throw new IllegalStateException("ISSUE68_UNTRACKED_HTTP");
        }
        Buffer buffer = new Buffer();
        if (request.body() == null) {
            throw new IllegalStateException("ISSUE68_MISSING_REQUEST_BODY");
        }
        request.body().writeTo(buffer);
        String body = buffer.readUtf8(); // 요청/키/원문은 메모리에서만 처리한다.
        JsonNode root = JSON.readTree(body);
        int cap = root.path("max_tokens").asInt(-1);
        var allowedFields = java.util.Set.of("messages", "model", "max_tokens", "response_format", "stream", "n");
        for (var property : root.properties()) {
            if (!allowedFields.contains(property.getKey())) {
                throw new IllegalStateException("ISSUE68_EXTRA_REQUEST_FIELD");
            }
        }
        if (!Issue68EvaluationContract.MODEL.equals(root.path("model").asText())
                || cap != 128 * logical.caseIds.size() || root.path("stream").asBoolean(false)
                || root.has("tools") || root.path("n").asInt(1) != 1) {
            throw new IllegalStateException("ISSUE68_REQUEST_CONTRACT_CHANGED");
        }
        var messages = root.path("messages");
        String expectedSystem = logical.batch ? Issue68EvaluationContract.batchSystemPrompt() : ModerationPrompt.SYSTEM_PROMPT;
        if (a1N1()) {
            if (logical.batch) throw new IllegalStateException("ISSUE68_A1_N1_NATIVE_ONLY");
            expectedSystem = Issue68RiskBoundaryPrompt.CANDIDATE;
        }
        if (fakeRiskBoundaryCandidate) {
            if (live) {
                throw new IllegalStateException("ISSUE68_RISK_CANDIDATE_FAKE_ONLY");
            }
            expectedSystem = Issue68RiskBoundaryPrompt.system(logical.batch
                    ? Issue68RiskBoundaryPrompt.Arm.D : Issue68RiskBoundaryPrompt.Arm.B);
        }
        if (messages.size() != 2 || !messages.get(0).path("content").asText().equals(expectedSystem)
                || !messages.get(0).path("role").asText().equals("system")
                || !messages.get(1).path("role").asText().equals("user")
                || !messages.get(1).path("content").isTextual()
                || !"json_schema".equals(root.path("response_format").path("type").asText())) {
            throw new IllegalStateException("ISSUE68_PROMPT_OR_SCHEMA_CHANGED");
        }
        if (!root.path("response_format").path("json_schema").path("schema")
                .equals(JSON.readTree(Issue68EvaluationContract.schema(logical.batch)))) {
            throw new IllegalStateException("ISSUE68_SCHEMA_CHANGED");
        }
        if (o0Plan != null && root.path("response_format").path("json_schema").has("strict")
                && !root.path("response_format").path("json_schema").path("strict").asBoolean()) {
            throw new IllegalStateException("ISSUE68_SCHEMA_STRICT_CHANGED");
        }
        var cases = Issue68EvaluationContract.heldout().stream().collect(java.util.stream.Collectors.toMap(
                Issue68EvaluationContract.Case::id, java.util.function.Function.identity()));
        String user = messages.get(1).path("content").asText();
        if (logical.batch) {
            var items = JSON.readTree(user).path("items");
            if (items.size() != logical.caseIds.size()) {
                throw new IllegalStateException("ISSUE68_INPUT_MAPPING_CHANGED");
            }
            for (int i = 0; i < items.size(); i++) {
                if (!items.get(i).path("id").asText().equals("m%03d".formatted(i + 1))
                        || !items.get(i).path("content").asText().equals(cases.get(logical.caseIds.get(i)).content())) {
                    throw new IllegalStateException("ISSUE68_INPUT_MAPPING_CHANGED");
                }
            }
        } else if (!user.equals(cases.get(logical.caseIds.get(0)).content())) {
            throw new IllegalStateException("ISSUE68_UNAPPROVED_CONTENT");
        }
        String hash = Issue68EvaluationContract.sha256(body);
        if (logical.requestSha256 != null && !logical.requestSha256.equals(hash)) {
            throw new IllegalStateException("ISSUE68_SDK_RETRY_REQUEST_CHANGED");
        }
        logical.requestSha256 = hash;
        logical.cap = cap;
        logical.inputEstimate = ENCODING.countTokens(root.path("messages").toString() + root.path("response_format")) + 128;
        // 하위 follow-up이 생겨도 예약을 공유하지 않는다. 실제 exchange마다 network interceptor가 예약한다.
        // 실제 전송은 검증이 모두 끝난 뒤에만 send에서 수행한다.
    }

    private Response exchange(Interceptor.Chain chain) throws IOException {
        verifyForTransmission(chain.request()); // 실제 전송 Payload를 각 HTTP exchange 직전에 재검사한다.
        Logical logical = current.get();
        if (stopped || logical == null || !chain.request().url().host().equals(endpoint.getHost())
                || chain.request().url().port() != (live ? 443 : endpoint.getPort())) {
            throw new IllegalStateException("ISSUE68_UNTRACKED_EXCHANGE");
        }
        int cap = logical.cap;
        if (o0Plan instanceof Issue68A1N1Plan plan) plan.beforeHttp(logical.number);
        if (o0Plan instanceof Issue68UsageBenchmarkPlan plan) plan.beforeHttp(logical.number);
        if (admittedExchanges.incrementAndGet() > maximumHttpAttempts) {
            stopped = true;
            throw new IOException("ISSUE68_HTTP_ATTEMPT_LIMIT");
        }
        try {
            beforeExchange.run(); // Consumer poll lease 등 실험의 추가 경계를 예약보다 먼저 검사한다.
        } catch (RuntimeException failure) {
            stop();
            throw new IOException("ISSUE68_EXCHANGE_PRECONDITION_HOLD");
        }
        Issue68Budget.Ticket ticket = budget.reserve(cap);
        checkpoint("http-reserved", java.util.Map.of("attempt", ticket.number(), "logical", logical.number));
        long started = System.nanoTime();
        // Spring AI customizer는 application interceptor다. 테스트 transport의 network interceptor에서
        // 실제 exchange별로 예약한다. SDK Retry=3과 기존 연결 재시도는 유지하고 redirect만 차단한다.
        Response response;
        try {
            response = chain.proceed(chain.request());
        } catch (IOException failure) {
            attempts.add(new Attempt(ticket.number(), logical.number, 0, null, ticket.reserved(),
                    System.nanoTime() - started, "UNAVAILABLE", "UNKNOWN_KEEP"));
            budget.settle(ticket, null);
            checkpoint("http-failed", attempts());
            throw live ? new IOException("ISSUE68_TRANSPORT_FAILED_REDACTED") : failure;
        }
        Issue68Budget.Usage usage = Issue68ResponseUsage.read(response, cap);
        String encoding = Issue68ResponseUsage.encoding(response);
        try {
            // Body 해석과 장부 정산을 분리한다. 장부 오류를 파싱 오류로 삼켜 이중 정산하지 않는다.
            budget.settle(ticket, usage);
        } catch (RuntimeException failure) {
            stopped = true;
            attempts.add(new Attempt(ticket.number(), logical.number, response.code(), usage,
                    ticket.reserved(), System.nanoTime() - started, encoding, "SETTLEMENT_FAILED"));
            response.close();
            throw failure;
        }
        attempts.add(new Attempt(ticket.number(), logical.number, response.code(), usage,
                ticket.reserved(), System.nanoTime() - started, encoding, usage == null ? "UNKNOWN_KEEP" : "SETTLED"));
        if ((o0Plan instanceof Issue68ExpandedPlan || a1N1() || usageBenchmark()) && usage == null) {
            // O0 과거 동작은 유지한다. 새 확장 계약은 불명 Usage 다음 SDK/transport Retry도 전송하지 않는다.
            stop();
        }
        try {
            checkpoint("http-completed", attempts());
        } catch (RuntimeException failure) {
            response.close();
            throw failure;
        }
        if (live && !response.isSuccessful()) {
            // 오류 원문은 SDK 예외/로그에 전달하지 않는다. status와 Retry 헤더만 유지한다.
            return redactError(response);
        }
        return response;
    }

    static Response redactError(Response response) {
        response.close();
        return response.newBuilder().removeHeader("Content-Encoding").removeHeader("Content-Length")
                .body(okhttp3.ResponseBody.create(
                "{\"error\":{\"message\":\"ISSUE68_PROVIDER_ERROR_REDACTED\",\"type\":\"provider_error\"}}",
                okhttp3.MediaType.get("application/json"))).build();
    }

    @Override
    public void close() {
        transport.dispatcher().cancelAll();
        transport.connectionPool().evictAll();
        transport.dispatcher().executorService().shutdownNow();
    }
}
