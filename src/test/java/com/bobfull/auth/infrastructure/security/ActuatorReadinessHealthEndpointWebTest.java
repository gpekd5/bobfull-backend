package com.bobfull.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "OPENAI_API_KEY=readiness-health-test-openai-key",
        "KAFKA_BOOTSTRAP_SERVERS=localhost:9092",
        "DB_URL=jdbc:h2:mem:actuator-readiness-health-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "REDIS_HOST=localhost",
        "REDIS_PORT=6379",
        "JWT_SECRET=readiness-health-test-secret-key-please-keep-long",
        "JWT_ACCESS_TOKEN_EXPIRATION_SECONDS=1800",
        "PORTONE_API_SECRET=portone-readiness-health-test-api-secret",
        "PORTONE_STORE_ID=portone-readiness-health-test-store-id",
        "PORTONE_WEBHOOK_SECRET=cmVhZGluZXNzLWhlYWx0aC10ZXN0",
        "S3_IMAGE_BUCKET=bobfull-test-image-bucket",
        "MAIL_HOST=localhost",
        "MAIL_PORT=1",
        "MAIL_USERNAME=readiness-health-test",
        "MAIL_PASSWORD=readiness-health-test",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "management.endpoint.health.show-components=always",
        "management.endpoints.web.exposure.include=health",
        "management.health.redis.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=false",
        "outbox.chat-message.enabled=false",
        "payment.expiration.enabled=false",
        "payment.refund-reconciliation.enabled=false"
})
@ContextConfiguration(classes = ActuatorReadinessHealthEndpointWebTest.Configuration.class)
class ActuatorReadinessHealthEndpointWebTest {

    @LocalServerPort
    private int port;

    @Test
    void prod_readiness는_readinessState_db_redis만_포함하고_mail은_제외한다() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/readiness"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(response.body()).contains("\"readinessState\"");
        assertThat(response.body()).contains("\"db\"");
        assertThat(response.body()).contains("\"redis\"");
        assertThat(response.body()).doesNotContain("\"mail\"");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        HealthIndicator redisHealthIndicator() {
            return () -> Health.up().build();
        }
    }
}
