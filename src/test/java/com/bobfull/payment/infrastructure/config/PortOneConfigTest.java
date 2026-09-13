package com.bobfull.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.portone.sdk.server.PortOneClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;

@SpringJUnitConfig(PortOneConfig.class)
@TestPropertySource(properties = {
        "portone.api-secret=test-api-secret",
        "portone.store-id=test-store-id",
        "portone.webhook-secret=d2hzZWNfZEdWemRDMXpkR055WlhRPQ=="
})
class PortOneConfigTest {
    @Autowired private PortOneProperties properties;
    @Autowired private PortOneClient portOneClient;

    @Test
    void 환경변수_형식의_설정을_바인딩하고_PortOne_Client를_구성한다() {
        assertThat(properties.apiSecret()).isEqualTo("test-api-secret");
        assertThat(properties.storeId()).isEqualTo("test-store-id");
        assertThat(portOneClient).isNotNull();
    }
}
