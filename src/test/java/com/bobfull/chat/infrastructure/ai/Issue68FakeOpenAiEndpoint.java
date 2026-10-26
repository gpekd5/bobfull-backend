package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// GT를 echo하는 계약 fixture이며 AI 품질/실제 Usage를 재현하는 모델이 아니다.
public final class Issue68FakeOpenAiEndpoint implements AutoCloseable {
    private static final JsonMapper JSON = new JsonMapper();
    public enum Mode { OK, RATE_LIMIT, SERVER_ERROR, RETRY_503, MAPPING, VALIDATION, PARSE, NO_USAGE, TRUNCATED,
        REDIRECT, SEMANTIC_SAFE, BROKEN_JSON, DROP_RESPONSE, RETRY_KNOWN_USAGE }
    private final HttpServer server;
    private final Queue<Mode> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger received = new AtomicInteger();
    private final List<Integer> sizes = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, ModerationResult> labels = new LinkedHashMap<>();
    private volatile Mode defaultMode = Mode.OK;
    private volatile boolean gzip;
    private volatile Runnable beforeResponse = () -> { };

    public Issue68FakeOpenAiEndpoint() throws IOException {
        Issue68EvaluationContract.heldout().forEach(c -> labels.put(c.content(), c.expected()));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    public void enqueue(Mode... modes) {
        queue.addAll(List.of(modes));
    }

    public void defaultMode(Mode mode) {
        defaultMode = mode;
    }

    public void gzip(boolean enabled) {
        gzip = enabled;
    }

    // 회계/실행 Gate 결함을 재현하기 위한 Fake 출력만 바꾼다. GT는 변경하지 않는다.
    public void overrideLabel(String caseId, ModerationResult result) {
        var c = Issue68EvaluationContract.heldout().stream().filter(item -> item.id().equals(caseId)).findFirst().orElseThrow();
        labels.put(c.content(), result);
    }

    public void beforeResponse(Runnable action) {
        beforeResponse = action;
    }

    public int received() {
        return received.get();
    }

    public List<Integer> sizes() {
        return List.copyOf(sizes);
    }

    // 합성 요청 본문만 메모리에 보존한다. 인증 header/Key는 수집하거나 내보내지 않는다.
    public List<String> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        received.incrementAndGet();
        Mode mode = queue.poll();
        if (mode == null) {
            mode = defaultMode;
        }
        if (mode == Mode.DROP_RESPONSE) {
            exchange.close();
            return;
        }
        if (mode == Mode.BROKEN_JSON) {
            send(exchange, 200, "{\"usage\":");
            return;
        }
        if (mode == Mode.RATE_LIMIT || mode == Mode.SERVER_ERROR) {
            exchange.getResponseHeaders().add("retry-after-ms", "1");
            send(exchange, mode == Mode.RATE_LIMIT ? 429 : 500, "{\"error\":{\"message\":\"fixture\",\"type\":\"fixture\"}}");
            return;
        }
        if (mode == Mode.RETRY_503) {
            exchange.getResponseHeaders().add("Retry-After", "0");
            send(exchange, 503, "{\"error\":{\"message\":\"fixture\",\"type\":\"fixture\"}}");
            return;
        }
        if (mode == Mode.RETRY_KNOWN_USAGE) {
            // 예약/정산과 실제 SDK Retry를 분리 검증하는 fixture. 실 Provider의 오류 Usage를 가정하지 않는다.
            exchange.getResponseHeaders().add("Retry-After", "0");
            send(exchange, 503, "{\"error\":{\"message\":\"fixture\"},\"usage\":{\"prompt_tokens\":900,"
                    + "\"completion_tokens\":0,\"total_tokens\":900,\"prompt_tokens_details\":{\"cached_tokens\":0}}}");
            return;
        }
        if (mode == Mode.REDIRECT) {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/not-authorized");
            send(exchange, 302, "{}");
            return;
        }
        String bodyText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(bodyText);
        JsonNode request = JSON.readTree(bodyText);
        String user = request.path("messages").get(1).path("content").asText();
        boolean batch = user.startsWith("{\"items\":");
        Object result;
        int size = 1;
        if (batch) {
            var items = JSON.readTree(user).path("items");
            size = items.size();
            List<Map<String, Object>> results = new ArrayList<>();
            for (var item : items) {
                var label = label(item.path("content").asText(), mode);
                results.add(Map.of("id", item.path("id").asText(), "moderation", label));
            }
            if (mode == Mode.MAPPING) {
                results.remove(results.size() - 1);
            }
            result = Map.of("results", results);
        } else {
            result = label(user, mode);
        }
        sizes.add(size);
        String text = mode == Mode.PARSE ? "not-json" : JSON.writeValueAsString(result);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "fixture-completion");
        body.put("object", "chat.completion");
        body.put("created", 1_700_000_000L);
        body.put("model", "gpt-4o-mini-2024-07-18");
        body.put("choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", text),
                "finish_reason", mode == Mode.TRUNCATED ? "length" : "stop")));
        if (mode != Mode.NO_USAGE) {
            body.put("usage", Map.of("prompt_tokens", 900, "completion_tokens", 20 * size,
                    "total_tokens", 900 + 20 * size, "prompt_tokens_details", Map.of("cached_tokens", 0)));
        }
        send(exchange, 200, JSON.writeValueAsString(body));
    }

    private ModerationResult label(String content, Mode mode) {
        if (mode == Mode.SEMANTIC_SAFE) {
            return new ModerationResult(ModerationResultType.SAFE, java.util.Set.of(), RiskLevel.LOW);
        }
        if (mode == Mode.VALIDATION) {
            return new ModerationResult(ModerationResultType.SAFE, java.util.Set.of(ModerationCategory.SPAM), RiskLevel.HIGH);
        }
        ModerationResult result = labels.get(content);
        if (result == null) {
            throw new IllegalStateException("ISSUE68_UNAPPROVED_CONTENT");
        }
        return result;
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        beforeResponse.run();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            var compressed = new java.io.ByteArrayOutputStream();
            try (var stream = new java.util.zip.GZIPOutputStream(compressed)) {
                stream.write(bytes);
            }
            bytes = compressed.toByteArray();
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
