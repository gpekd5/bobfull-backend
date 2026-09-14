package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.application.port.AiModerationPort;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * #192 {@code bobfull.ai.moderation.fake-enabled} 값에 따라 {@link FakeAiModerationAdapter}와
 * {@link SpringAiModerationAdapter}가 상호 배타적으로 등록되는지 최소 구성 ApplicationContext로 검증한다.
 * 전체 앱 컨텍스트(DB/JPA/Kafka 등)를 띄우지 않고 두 Adapter Bean의 ConditionalOnProperty 조건만 검증한다.
 * SpringAiModerationAdapter가 활성화될 때 필요한 ChatClient는 실제 호출 없이 Mock으로만 대체한다.
 */
class AiModerationPortSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean("moderationChatClient", ChatClient.class, () -> Mockito.mock(ChatClient.class))
            .withUserConfiguration(SpringAiModerationAdapter.class, FakeAiModerationAdapter.class);

    @Test
    void fake_enabled를_true로_설정하면_FakeAiModerationAdapter만_등록된다() {
        contextRunner.withPropertyValues("bobfull.ai.moderation.fake-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AiModerationPort.class);
                    assertThat(context.getBean(AiModerationPort.class)).isInstanceOf(FakeAiModerationAdapter.class);
                    assertThat(context).doesNotHaveBean(SpringAiModerationAdapter.class);
                });
    }

    @Test
    void fake_enabled를_설정하지_않으면_SpringAiModerationAdapter만_등록된다() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiModerationPort.class);
            assertThat(context.getBean(AiModerationPort.class)).isInstanceOf(SpringAiModerationAdapter.class);
            assertThat(context).doesNotHaveBean(FakeAiModerationAdapter.class);
        });
    }

    @Test
    void fake_enabled를_false로_명시해도_SpringAiModerationAdapter만_등록된다() {
        contextRunner.withPropertyValues("bobfull.ai.moderation.fake-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AiModerationPort.class);
                    assertThat(context.getBean(AiModerationPort.class)).isInstanceOf(SpringAiModerationAdapter.class);
                    assertThat(context).doesNotHaveBean(FakeAiModerationAdapter.class);
                });
    }
}
