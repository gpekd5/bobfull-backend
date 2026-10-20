package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.exception.BatchModerationException;
import com.bobfull.chat.application.port.AiModerationBatchPort;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import com.bobfull.chat.application.service.SplitMessageCandidateGate;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.kafka.ChatModerationBatchConfig;
import com.bobfull.chat.infrastructure.kafka.ChatModerationBatchConsumer;
import com.bobfull.chat.infrastructure.kafka.ChatModerationConsumer;
import com.bobfull.chat.infrastructure.kafka.ChatModerationConsumerErrorHandlingConfig;
import com.bobfull.chat.infrastructure.kafka.ChatModerationDltRecoverer;
import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import com.bobfull.chat.infrastructure.metrics.ChatModerationMetrics;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import com.bobfull.common.config.JpaAuditingConfig;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

// Port/Service/Repository를 Mock으로 공급하지 않는다. 실제 JPA/H2 + production Bean graph 검증이다.
// Kafka Listener는 등록하되 시작하지 않는다. Broker/Offset 장애 경계는 기존 Testcontainers suite가 담당한다.
class ChatModerationProviderContextTest {
    private HttpServer server;
    private OpenAIClient sdk;
    private OpenAIClientAsync async;
    private ChatClient.Builder clientBuilder;
    private final AtomicInteger httpAttempts = new AtomicInteger();

    @BeforeEach
    void prepareLoopbackOnlyClient() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            httpAttempts.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        var observations = ObservationRegistry.create();
        sdk = OpenAiSetup.setupSyncClient(url, "fake-test-key", null, null, null, null, false, false,
                "gpt-4o-mini", Duration.ofSeconds(5), 3, null, Map.of(), observations, null, List.of());
        async = OpenAiSetup.setupAsyncClient(url, "fake-test-key", null, null, null, null, false, false,
                "gpt-4o-mini", Duration.ofSeconds(5), 3, null, Map.of(), observations, null, List.of());
        clientBuilder = ChatClient.builder(OpenAiChatModel.builder().openAiClient(sdk).openAiClientAsync(async)
                .options(OpenAiChatOptions.builder().model("gpt-4o-mini").build()).build());
    }

    @AfterEach
    void verifyNoHttpAndClose() {
        try {
            assertThat(httpAttempts.get()).as("Fake 처리가 Spring AI/HTTP로 우회하면 안 된다").isZero();
        } finally {
            sdk.close();
            async.close();
            server.stop(0);
        }
    }

    @ParameterizedTest(name = "batch={0}, fake={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void 실제_설정과_Adapter로_네_조합을_기동하고_Listener와_Port를_배타적으로_등록한다(
            boolean batch, boolean fake) {
        // given / when
        runner(batch, fake).run(context -> {
            // then
            assertThat(context).hasNotFailed().hasSingleBean(ChatModerationService.class)
                    .hasSingleBean(AiModerationPort.class).hasSingleBean(SpringAiModerationConfig.class);
            assertThat(context.getBean(AiModerationPort.class)).isInstanceOf(fake
                    ? FakeAiModerationAdapter.class : SpringAiModerationAdapter.class);
            assertThat(context.getBeansOfType(ChatModerationConsumer.class)).hasSize(batch ? 0 : 1);
            assertThat(context.getBeansOfType(ChatModerationBatchConsumer.class)).hasSize(batch ? 1 : 0);
            assertThat(context.getBeansOfType(ChatModerationBatchService.class)).hasSize(batch ? 1 : 0);
            assertThat(context.getBeansOfType(AiModerationBatchPort.class)).hasSize(batch ? 1 : 0);
            assertThat(context.getBeansOfType(SpringAiModerationBatchAdapter.class)).hasSize(batch && !fake ? 1 : 0);
            assertThat(context.getBeansOfType(FakeAiModerationBatchAdapter.class)).hasSize(batch && fake ? 1 : 0);
            var listeners = context.getBean(KafkaListenerEndpointRegistry.class);
            assertThat(listeners.getListenerContainerIds()).hasSize(1);
            if (batch) {
                assertThat(listeners.getListenerContainerIds()).containsExactly("chatModerationBatch");
            } else {
                // Native Listener는 명시적 id 없이 Spring이 생성한 이름을 유지한다.
                assertThat(listeners.getListenerContainerIds()).doesNotContain("chatModerationBatch");
            }
            assertThat(listeners.getListenerContainers()).allMatch(container -> !container.isRunning());
            if (batch) {
                assertThat(context.getBean(AiModerationBatchPort.class)).isInstanceOf(fake
                        ? FakeAiModerationBatchAdapter.class : SpringAiModerationBatchAdapter.class);
            }
            if (fake) {
                var response = context.getBean(AiModerationPort.class).analyze("식사 일정 확인해요");
                assertThat(response.provider()).isEqualTo("Fake");
                assertThat(response.result().result()).isEqualTo(ModerationResultType.SAFE);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 10})
    void Batch_Fake는_ID를_보존하고_실제_Service에서_메시지별로_저장한다(int size) {
        // given
        runner(true, true).withPropertyValues("bobfull.ai.moderation.fake-result-type=FLAGGED").run(context -> {
            assertThat(context).hasNotFailed();
            var items = IntStream.range(0, size).mapToObj(index -> new AiModerationBatchPort.Item(
                    "m%03d".formatted(index + 1), "식사 일정 확인해요")).toList();
            var messages = context.getBean(ChatMessageRepository.class);
            var results = context.getBean(ChatModerationRepository.class);
            var ids = IntStream.range(0, size).mapToObj(index -> messages.saveAndFlush(
                    ChatMessage.create(1L, (long) index + 1, (long) index + 1, "식사 일정 확인해요")).getId()).toList();
            // when
            var response = context.getBean(AiModerationBatchPort.class).analyze(items);
            context.getBean(ChatModerationBatchService.class).analyze(ids);
            // then
            assertThat(response.results()).extracting(AiModerationBatchPort.Output::id)
                    .containsExactlyElementsOf(items.stream().map(AiModerationBatchPort.Item::id).toList());
            assertThat(results.findAll()).hasSize(size).allSatisfy(result -> {
                assertThat(result.getResult()).isEqualTo(ModerationResultType.FLAGGED);
                assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
                assertThat(result.getProvider()).isEqualTo("Fake");
                assertThat(result.getPromptTokens()).isNull();
                assertThat(result.getCompletionTokens()).isNull();
                assertThat(result.getTotalTokens()).isNull();
            });
            var registry = context.getBean(MeterRegistry.class);
            assertThat(registry.get("bobfull.ai.moderation.batch.size").summary().count()).isEqualTo(1);
            assertThat(registry.find("gen_ai.client.operation").timer()).isNull();
            assertThat(registry.find("bobfull.ai.moderation.batch.estimated.cost.usd").counter()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 10})
    void Batch_Fake_강제실패는_실제_Service로_전파되고_결과를_저장하지_않는다(int size) {
        // given
        runner(true, true).run(context -> {
            assertThat(context).hasNotFailed();
            var messages = context.getBean(ChatMessageRepository.class);
            var ids = IntStream.range(0, size).mapToObj(index -> messages.saveAndFlush(ChatMessage.create(
                    1L, (long) index + 1, (long) index + 1, index == size - 1
                            ? FakeAiModerationAdapter.FORCE_FAIL_MARKER : "식사 일정 확인해요")).getId()).toList();
            // when / then
            assertThatThrownBy(() -> context.getBean(ChatModerationBatchService.class).analyze(ids))
                    .isInstanceOf(BatchModerationException.class).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(context.getBean(ChatModerationRepository.class).count()).isZero();
        });
    }

    private ApplicationContextRunner runner(boolean batch, boolean fake) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class, KafkaAutoConfiguration.class))
                .withBean(ChatClient.Builder.class, () -> clientBuilder)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(PersistenceConfiguration.class, JpaAuditingConfig.class,
                        SpringAiModerationConfig.class, ChatModerationService.class, ChatModerationBatchService.class,
                        ModerationRulePolicy.class, SplitMessageCandidateGate.class,
                        ChatModerationMetrics.class, ChatModerationBatchMetrics.class, BusinessMetricRecorder.class,
                        FakeAiModerationAdapter.class, FakeAiModerationBatchAdapter.class,
                        SpringAiModerationAdapter.class, SpringAiModerationBatchAdapter.class,
                        ChatModerationConsumerErrorHandlingConfig.class, ChatModerationBatchConfig.class,
                        ChatModerationDltRecoverer.class, ChatModerationConsumer.class, ChatModerationBatchConsumer.class)
                .withPropertyValues("spring.datasource.generate-unique-name=true", "spring.jpa.hibernate.ddl-auto=create-drop",
                        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false",
                        "spring.kafka.listener.ack-mode=record", "spring.kafka.consumer.enable-auto-commit=false",
                        "spring.kafka.consumer.group-id=moderation-context-test",
                        "bobfull.kafka.chat-message.consumer-enabled=true",
                        "bobfull.kafka.chat-message.batch-enabled=" + batch, "bobfull.ai.moderation.fake-enabled=" + fake);
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = ChatMessage.class)
    @EnableJpaRepositories(basePackageClasses = ChatMessageRepository.class)
    static class PersistenceConfiguration {
    }
}
