package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class Issue68DashboardContractTest {
    @Test
    void 기존_Dashboard의_UID와_패널원본을_바꾸지_않는다() throws Exception {
        // given
        String source = Files.readString(Path.of("ops/local-monitoring/grafana/provisioning/dashboards/bobfull-ai-moderation-observability.json"));
        // when
        var dashboard = new JsonMapper().readTree(source);
        // then
        assertThat(Issue68EvaluationContract.sha256(source)).isEqualTo("a7bd9062f4225cc831d82c244565db7942e3d1f450bf8d656181a24684b5239c");
        assertThat(dashboard.path("uid").asText()).isEqualTo("bobfull-ai-moderation");
        assertThat(dashboard.path("panels")).hasSize(11);
        for (var panel : dashboard.path("panels")) {
            if (List.of(3, 4, 5, 12).contains(panel.path("id").asInt())) {
                assertThat(panel.path("datasource").path("uid").asText()).isEqualTo("prometheus");
                assertThat(panel.path("targets").get(0).path("expr").asText())
                        .contains("gen_ai_client_").doesNotContain("issue68_test_");
            }
        }
    }

    @Test
    void 격리_수집기는_기존앱과_namedVolume을_참조하지_않는다() throws Exception {
        // given
        Path root = Path.of("ops/local-monitoring/issue68-dashboard");
        // when
        String scrape = Files.readString(root.resolve("prometheus.yml"));
        String compose = Files.readString(root.resolve("docker-compose.yml"));
        // then
        assertThat(scrape).contains("host.docker.internal:18068", "harness_scope", "issue68_fake")
                .doesNotContain("host.docker.internal:8080", "issue68_test_$1");
        assertThat(compose).contains("prom/prometheus:v3.14.0", "tmpfs:")
                .doesNotContain("bobfull-prometheus-data:/prometheus", ".env");
    }
}
