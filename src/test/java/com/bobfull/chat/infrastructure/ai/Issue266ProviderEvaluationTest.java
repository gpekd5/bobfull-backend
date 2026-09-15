package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@Tag("openai-evaluation")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ISSUE266_PROVIDER", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:issue266;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "jwt.secret=openai-evaluation-only-secret-key-with-minimum-length", "portone.api-secret=test-api-secret", "portone.store-id=test-store-id", "portone.webhook-secret=d2hzZWNfZEdWemRDMXpkR055WlhRPQ==", "payment.expiration.enabled=false", "outbox.chat-room.enabled=false", "outbox.email.enabled=false"})
class Issue266ProviderEvaluationTest {
    @Autowired @Qualifier("moderationChatClient") ChatClient client;

    @Test void 핵심_Context_Case를_실제_Provider로_기록한다() {
        for (String fragment : List.of("죽", "010", "시", "간")) {
            ModerationResult result = client.prompt().system(ModerationPrompt.SYSTEM_PROMPT).user(fragment).call().entity(ModerationResult.class);
            System.out.printf("[266-SINGLE] input=%s result=%s categories=%s risk=%s%n", fragment, result.result(), result.categories(), result.riskLevel());
        }
        for (Case c : List.of(new Case("SPLIT-SIBAL", "아", List.of("시", "발", "아")), new Case("SPLIT-BYEONGSIN", "신", List.of("병", "신")),
                new Case("SAFE-SIGAN", "간", List.of("시", "간")), new Case("SAFE-JUK", "싶다", List.of("죽", "먹고", "싶다")),
                new Case("PERSONAL-010", "5678", List.of("내 번호", "010", "1234", "5678")), new Case("PUBLIC-010", "5678", List.of("식당 전화번호", "010", "1234", "5678")))) {
            String joined = String.join("", c.fragments());
            ModerationResult result = client.prompt().system(ModerationPrompt.SYSTEM_PROMPT)
                    .user(ModerationPrompt.withSplitContext(c.current(), c.fragments(), joined)).call()
                    .entity(ModerationResult.class);
            System.out.printf("[266] case=%s result=%s categories=%s risk=%s%n", c.id(), result.result(), result.categories(), result.riskLevel());
        }
    }
    private record Case(String id, String current, List<String> fragments) { }
}
