package com.bobfull.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "PORTONE_WEBHOOK_SECRET", matches = ".+")
@ActiveProfiles("local")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:portone-local-binding-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payment.expiration.enabled=false"
})
class PortOneLocalConfigurationBindingTest {

    @Autowired private PortOneProperties properties;

    @Test
    void local_환경변수로_PortOne_필수설정이_바인딩된다() {
        assertThat(properties.apiSecret()).isNotBlank();
        assertThat(properties.storeId()).isNotBlank();
        assertThat(properties.webhookSecret()).isNotBlank();
    }
}
