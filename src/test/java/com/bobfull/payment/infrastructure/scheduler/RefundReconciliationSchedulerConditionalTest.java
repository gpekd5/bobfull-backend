package com.bobfull.payment.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bobfull.payment.application.service.RefundReconciliationProcessor;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;

/**
 * Issue #272 PR 리뷰(hyeonseung-dev)에서 지적된 회귀: {@code application-local.yml.example}만 고쳐서는
 * Spring Boot가 그 파일을 자동으로 읽지 않고, {@link RefundReconciliationScheduler}의
 * {@code matchIfMissing = true}가 그대로면 실제 로컬 환경에는 아무 효과가 없다. property 값 자체가
 * 없을 때 빈이 생성되지 않는지를 직접 검증해 이 회귀를 방지한다.
 */
class RefundReconciliationSchedulerConditionalTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Dependencies.class, RefundReconciliationScheduler.class);

    @Test
    void enabled_설정이_없으면_스케줄러_빈을_생성하지_않는다() {
        runner.run(context -> assertThat(context).doesNotHaveBean(RefundReconciliationScheduler.class));
    }

    @Test
    void enabled_false로_명시하면_스케줄러_빈을_생성하지_않는다() {
        runner.withPropertyValues("payment.refund-reconciliation.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RefundReconciliationScheduler.class));
    }

    @Test
    void enabled_true로_명시해야만_스케줄러_빈을_생성한다() {
        runner.withPropertyValues("payment.refund-reconciliation.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RefundReconciliationScheduler.class));
    }

    @Configuration
    static class Dependencies {
        @Bean
        RefundRepository refundRepository() {
            return mock(RefundRepository.class);
        }

        @Bean
        RefundReconciliationProcessor refundReconciliationProcessor() {
            return mock(RefundReconciliationProcessor.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        /** {@code @Value}로 "10m"/"24h" 같은 Duration 문자열을 변환하려면 Spring Boot가 제공하는
         * 이 변환 서비스가 빈 이름 {@code conversionService}로 등록돼 있어야 한다 — 순수
         * {@link ApplicationContextRunner}는 {@code @SpringBootTest}와 달리 이를 자동으로 등록하지
         * 않는다. */
        @Bean("conversionService")
        ConversionService conversionService() {
            return ApplicationConversionService.getSharedInstance();
        }
    }
}
