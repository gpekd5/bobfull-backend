package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

class Issue68ResponseUsageTest {
    private static final String USAGE = "{\"usage\":{\"prompt_tokens\":824,\"completion_tokens\":13,"
            + "\"total_tokens\":837,\"prompt_tokens_details\":{\"cached_tokens\":100}}}";

    @Test
    void 압축과_비압축_응답은_같은_Usage이며_원본_body는_소비하지_않는다() throws Exception {
        // given
        for (boolean gzip : List.of(false, true)) {
            byte[] body = gzip ? gzip(USAGE) : USAGE.getBytes(StandardCharsets.UTF_8);
            try (var response = response(body, gzip ? "gzip" : null, 200)) {
                // when
                var usage = Issue68ResponseUsage.read(response, 128);
                // then: 두 번 peek해도 SDK가 읽을 원본은 동일하다. reader 자체는 장부를 정산하지 않는다.
                assertThat(usage).isEqualTo(new Issue68Budget.Usage(824, 13, 837, 100L));
                assertThat(Issue68ResponseUsage.read(response, 128)).isEqualTo(usage);
                assertThat(response.body().bytes()).containsExactly(body);
            }
        }
    }

    @Test
    void Usage_누락과_타입오류_음수_합계오류_상한초과는_정산하지_않는다() {
        // given
        for (String invalid : List.of("{}", "{\"usage\":null}", "{\"usage\":",
                USAGE.replace("824", "-1"), USAGE.replace("837", "838"), USAGE.replace("13", "129"),
                USAGE.replace("824", "128001"), USAGE.replace("824", "824.0"),
                USAGE.replace("100", "\"unknown\""), USAGE.replace("100", "825"),
                USAGE.replace("824", "999999999999999999999999999999999"))) {
            try (var response = response(invalid.getBytes(StandardCharsets.UTF_8), null, 200)) {
                // when & then
                assertThat(Issue68ResponseUsage.read(response, 128)).isNull();
            }
        }
    }

    @Test
    void 중복키와_후행_JSON은_신뢰할_Usage로_사용하지_않는다() {
        // given
        for (String invalid : List.of(USAGE.replace("\"prompt_tokens\":824", "\"prompt_tokens\":1,\"prompt_tokens\":824"),
                USAGE + " {}")) {
            try (var response = response(invalid.getBytes(StandardCharsets.UTF_8), null, 200)) {
                // when & then
                assertThat(Issue68ResponseUsage.read(response, 128)).isNull();
            }
        }
    }

    @Test
    void 손상되거나_잘린_gzip은_예약을_해제할_근거가_아니다() throws Exception {
        // given
        byte[] valid = gzip(USAGE);
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 8] ^= 1; // CRC 오류
        for (byte[] invalid : List.of(corrupt, java.util.Arrays.copyOf(valid, valid.length - 5),
                USAGE.getBytes(StandardCharsets.UTF_8))) {
            try (var response = response(invalid, "gzip", 200)) {
                // when & then
                assertThat(Issue68ResponseUsage.read(response, 128)).isNull();
                assertThat(response.body().bytes()).containsExactly(invalid);
            }
        }
    }

    @Test
    void 미지원_압축과_다중_encoding_및_실패_status는_보수적으로_처리한다() {
        // given
        for (String encoding : List.of("br", "deflate", "gzip, br")) {
            try (var response = response(USAGE.getBytes(StandardCharsets.UTF_8), encoding, 200)) {
                // when & then
                assertThat(Issue68ResponseUsage.read(response, 128)).isNull();
            }
        }
        try (var response = response(USAGE.getBytes(StandardCharsets.UTF_8), null, 500)) {
            assertThat(Issue68ResponseUsage.read(response, 128)).isNull();
        }
    }

    @Test
    void wire와_압축해제_크기_상한을_각각_지킨다() throws Exception {
        // given
        String oversized = " ".repeat((int) Issue68ResponseUsage.MAX_BYTES + 1) + USAGE;
        for (boolean compressed : List.of(false, true)) {
            byte[] body = compressed ? gzip(oversized) : oversized.getBytes(StandardCharsets.UTF_8);
            try (var response = response(body, compressed ? "gzip" : null, 200)) {
                // when & then
                assertThat(Issue68ResponseUsage.read(response, 128)).isNull();
            }
        }
    }

    @Test
    void Cache_미제공은_null이며_미할인_정산에_필요한_합계는_유지한다() {
        // given
        String noCache = "{\"usage\":{\"prompt_tokens\":824,\"completion_tokens\":13,\"total_tokens\":837}}";
        try (var response = response(noCache.getBytes(StandardCharsets.UTF_8), null, 200)) {
            // when & then
            assertThat(Issue68ResponseUsage.read(response, 128)).isEqualTo(new Issue68Budget.Usage(824, 13, 837, null));
        }
    }

    @Test
    void 압축_오류_원문을_redaction하면_압축_header도_제거한다() throws Exception {
        // given
        try (var original = response("secret-canary".getBytes(StandardCharsets.UTF_8), "gzip", 429);
                var sanitized = Issue68HttpBoundary.redactError(original)) {
            // when & then
            assertThat(sanitized.header("Content-Encoding")).isNull();
            assertThat(sanitized.header("Content-Length")).isNull();
            assertThat(sanitized.body().string()).contains("REDACTED").doesNotContain("secret-canary");
        }
    }

    private static byte[] gzip(String body) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var stream = new GZIPOutputStream(bytes)) {
            stream.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static Response response(byte[] body, String encoding, int status) {
        var builder = new Response.Builder().request(new Request.Builder().url("http://127.0.0.1/").build())
                .protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .header("Content-Length", Integer.toString(body.length))
                .body(ResponseBody.create(body, MediaType.get("application/json")));
        if (encoding != null) {
            builder.header("Content-Encoding", encoding);
        }
        return builder.build();
    }
}
