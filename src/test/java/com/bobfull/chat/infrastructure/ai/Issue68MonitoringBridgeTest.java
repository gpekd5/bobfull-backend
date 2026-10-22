package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class Issue68MonitoringBridgeTest {
    @Test
    void Context종료전_동결한_히스토그램과_계수는_종료후에도_스크랩할수있다() throws Exception {
        try (var bridge = new Issue68MonitoringBridge(0)) {
            Timer.builder("gen_ai.client.operation").register(bridge.registry()).record(Duration.ofMillis(60));
            bridge.registry().counter("bobfull.ai.moderation.route", "route", "llm").increment();
            bridge.freeze(bridge.registry());
            String before = bridge.scrape();
            bridge.registry().close();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + bridge.port() + "/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(before).contains("gen_ai_client_operation_seconds_bucket",
                    "bobfull_ai_moderation_route_total", "harness_scope=\"issue68_fake\"");
        }
    }
}
