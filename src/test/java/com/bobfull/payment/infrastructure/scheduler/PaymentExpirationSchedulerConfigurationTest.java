package com.bobfull.payment.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.payment.infrastructure.config.PaymentExpirationSchedulingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-expiration-scheduler-config-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payment.expiration.enabled=false",
        "jwt.secret=payment-expiration-scheduler-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-payment-expiration-scheduler-test-api-secret",
        "portone.store-id=portone-payment-expiration-scheduler-test-store-id",
        "portone.webhook-secret=d2hzZWNfc2NoZWR1bGVyLXRlc3Q="
})
class PaymentExpirationSchedulerConfigurationTest {

    @Autowired private ApplicationContext applicationContext;

    @Test
    void 스케줄링은_활성화하고_test_환경에서는_만료Scheduler_Bean을_만들지_않는다() {
        assertThat(PaymentExpirationSchedulingConfig.class.getAnnotation(EnableScheduling.class)).isNotNull();
        assertThat(applicationContext.getBeansOfType(PaymentExpirationScheduler.class)).isEmpty();
    }
}
