package com.bobfull.chat.infrastructure.ai;

import java.io.IOException;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSource;
import okio.GzipSource;
import okio.Okio;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// network 응답의 bounded 복사본만 해석한다. SDK가 읽을 원본 body와 HTTP 압축 계약은 바꾸지 않는다.
final class Issue68ResponseUsage {
    static final long MAX_BYTES = 1_048_576;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Issue68ResponseUsage() {
    }

    static String encoding(Response response) {
        var values = response.headers("Content-Encoding");
        if (values.isEmpty()) {
            return "identity";
        }
        if (values.size() != 1) {
            return "unsupported";
        }
        String value = values.get(0).trim().toLowerCase(java.util.Locale.ROOT);
        return value.equals("gzip") || value.equals("identity") ? value : "unsupported";
    }

    static Issue68Budget.Usage read(Response response, int cap) {
        String encoding = encoding(response);
        if (!response.isSuccessful() || response.body() == null || encoding.equals("unsupported")) {
            return null;
        }
        // wire/decoded 양쪽을 제한한다. gzip checksum/EOF까지 검증된 작은 JSON만 정산에 사용한다.
        try (var snapshot = response.peekBody(MAX_BYTES + 1)) {
            if (snapshot.contentLength() > MAX_BYTES) {
                return null;
            }
            if (encoding.equals("gzip")) {
                try (var decoded = Okio.buffer(new GzipSource(snapshot.source()))) {
                    return parse(boundedJson(decoded), cap);
                }
            }
            return parse(boundedJson(snapshot.source()), cap);
        } catch (IOException | RuntimeException unreadable) {
            // 오류 원문/본문을 로그나 결과로 내보내지 않는다. 불명 Usage는 전액 예약 유지다.
            return null;
        }
    }

    private static String boundedJson(BufferedSource source) throws IOException {
        Buffer bytes = new Buffer();
        while (bytes.size() <= MAX_BYTES) {
            long read = source.read(bytes, MAX_BYTES + 1 - bytes.size());
            if (read == -1) {
                return bytes.readUtf8();
            }
        }
        return null;
    }

    private static Issue68Budget.Usage parse(String body, int cap) {
        if (body == null) {
            return null;
        }
        JsonNode usage = JSON.readTree(body).path("usage");
        for (String name : java.util.List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
            if (!usage.path(name).isIntegralNumber() || !usage.path(name).canConvertToLong()) {
                return null;
            }
        }
        var cached = usage.path("prompt_tokens_details").path("cached_tokens");
        if (!cached.isMissingNode() && !cached.isNull() && (!cached.isIntegralNumber() || !cached.canConvertToLong())) {
            return null;
        }
        var result = new Issue68Budget.Usage(usage.path("prompt_tokens").asLong(),
                usage.path("completion_tokens").asLong(), usage.path("total_tokens").asLong(),
                cached.isIntegralNumber() ? cached.asLong() : null);
        return Issue68Budget.validUsage(result, cap) ? result : null;
    }
}
