package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import com.bobfull.chat.infrastructure.metrics.ChatModerationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// 실제 auto-configuration/Adapter를 사용하지만 URL과 credential은 loopback fixture로 고정한다.
public final class Issue68OpenAiContext {
    private Issue68OpenAiContext() {
    }

    public static ApplicationContextRunner runner(Issue68HttpBoundary boundary, String baseUrl, boolean batch) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class,
                        OpenAiChatAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withBean(MeterRegistry.class, boundary::meterRegistry)
                .withBean(ObservationRegistry.class, () -> {
                    // Registry 연결은 실제 Context에서 같은 MeterRegistry를 사용하도록 아래 initializer가 설정한다.
                    return ObservationRegistry.create();
                })
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton(
                        "issue68ObservationBinding", new org.springframework.beans.factory.config.BeanPostProcessor() {
                            @Override
                            public Object postProcessAfterInitialization(Object bean, String name) {
                                if (boundary.a1N1() && bean instanceof org.springframework.ai.openai.OpenAiChatModel) {
                                    // 실제 Adapter/ChatClient/옵션은 유지하고, 이 승인 경로의 System만 교체한다.
                                    var proxy = new org.springframework.aop.framework.ProxyFactory(bean);
                                    proxy.setInterfaces(org.springframework.ai.chat.model.ChatModel.class);
                                    proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) call -> {
                                        if (call.getMethod().getName().equals("call") && call.getArguments().length == 1
                                                && call.getArguments()[0] instanceof org.springframework.ai.chat.prompt.Prompt prompt) {
                                            var messages = new java.util.ArrayList<>(prompt.getInstructions());
                                            if (messages.size() != 2 || !messages.get(0).getText().equals(ModerationPrompt.SYSTEM_PROMPT)) {
                                                boundary.stop();
                                                throw new IllegalStateException("ISSUE68_A1_N1_CONTROL_REQUEST_CHANGED");
                                            }
                                            messages.set(0, new org.springframework.ai.chat.messages.SystemMessage(Issue68RiskBoundaryPrompt.CANDIDATE));
                                            call.getArguments()[0] = new org.springframework.ai.chat.prompt.Prompt(messages, prompt.getOptions());
                                        }
                                        return call.proceed();
                                    });
                                    return proxy.getProxy();
                                }
                                if (bean instanceof ObservationRegistry observations) {
                                    var meters = factory.getBean(MeterRegistry.class);
                                    // Spring AI Handler는 Usage Counter만, 기본 Handler는 Timer를 기록한다.
                                    observations.observationConfig()
                                            .observationHandler(new org.springframework.ai.chat.observation.ChatModelMeterObservationHandler(meters))
                                            .observationHandler(new DefaultMeterObservationHandler(meters));
                                }
                                return bean;
                            }
                        })))
                .withBean(OpenAiHttpClientBuilderCustomizer.class, boundary::customizer)
                .withUserConfiguration(SpringAiModerationConfig.class, SpringAiModerationAdapter.class,
                        SpringAiModerationBatchAdapter.class, ChatModerationMetrics.class, ChatModerationBatchMetrics.class)
                .withPropertyValues("spring.ai.openai.api-key=" + (boundary.live() ? liveCredential(boundary) : "issue68-fake-only"),
                        "spring.ai.openai.base-url=" + baseUrl,
                        "spring.ai.openai.chat.model=gpt-4o-mini", "bobfull.ai.moderation.max-output-tokens=128",
                        "bobfull.ai.moderation.fake-enabled=false", "bobfull.kafka.chat-message.batch-enabled=" + batch);
    }

    private static String liveCredential(Issue68HttpBoundary boundary) {
        boundary.requireCredentialApproval();
        if (!boundary.o0() && !(Issue68LiveSession.APPROVAL.equals(System.getenv("ISSUE68_LIVE_APPROVAL"))
                || Issue68LiveSession.RESUME_APPROVAL.equals(System.getenv("ISSUE68_LIVE_APPROVAL")))
                ) {
            throw new IllegalStateException("ISSUE68_LIVE_NOT_APPROVED");
        }
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank() || key.contains("\n") || key.contains("\r")) {
            throw new IllegalStateException("ISSUE68_LIVE_CREDENTIAL_UNAVAILABLE");
        }
        return key;
    }
}
