package com.bobfull.chat.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Set.of;

import com.bobfull.chat.dto.AiModerationResponse;
import com.bobfull.chat.dto.ModerationResult;
import com.bobfull.chat.entity.ModerationCategory;
import com.bobfull.chat.entity.ModerationResultType;
import com.bobfull.chat.entity.RiskLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;

/** 실제 OpenAI 단건 연결 검증이다. 일반 build에서는 API Key가 없으면 실행하지 않는다. */
@Tag("openai-evaluation")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@ActiveProfiles("local")
@SpringBootTest(properties = {
        "spring.ai.openai.chat.model=${OPENAI_EVAL_MODEL:${OPENAI_CHAT_MODEL:gpt-4o-mini}}",
        "spring.datasource.url=jdbc:h2:mem:openai-evaluation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=openai-evaluation-only-secret-key-with-minimum-length",
        "portone.api-secret=test-api-secret",
        "portone.store-id=test-store-id",
        "portone.webhook-secret=d2hzZWNfZEdWemRDMXpkR055WlhRPQ==",
        "spring.mail.host=localhost",
        "spring.mail.port=1025",
        "payment.expiration.enabled=false",
        "payment.refund-reconciliation.enabled=false",
        "reservation.recruitment-deadline.enabled=false",
        "reservation.dining-end.enabled=false",
        "outbox.chat-room.enabled=false",
        "outbox.email.enabled=false"
})
class SpringAiModerationAdapterOpenAiEvaluationTest {
    @Autowired private ApplicationContext applicationContext;
    @Autowired @Qualifier("moderationChatClient") private ChatClient moderationChatClient;
    @Value("${spring.ai.openai.chat.model}") private String evaluationModel;
    @Value("${bobfull.ai.moderation.max-output-tokens}") private int maxOutputTokens;

    @DynamicPropertySource
    static void openAiApiKey(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> System.getenv("OPENAI_API_KEY"));
    }

    @Test
    void local_환경변수로_바인딩한_OpenAI에_SAFE_단건_분석을_요청한다() {
        // when
        AiModerationResponse response = evaluateWithSelectedModel("내일 7시에 식당에서 봐요.").response();

        // then
        assertThat(response.provider()).isEqualTo("OpenAI");
        assertThat(response.model()).isNotBlank();
        assertThat(response.result().result()).isEqualTo(ModerationResultType.SAFE);
    }

    @Test
    void 실제_OpenAI_RAW와_DTO를_콘솔에서_확인한다() {
        String input = "내 번호 010-1234-5678이야";
        System.out.printf("%n===== EVALUATION OPTIONS =====%nmodel=%s%n%s=%d%nreasoningEffort=%s%n%n",
                evaluationModel, OpenAiModerationEvaluationOptions.outputTokenOptionName(evaluationModel), maxOutputTokens,
                "gpt-5.4-nano".equals(evaluationModel) ? "none" : "not-set");
        EvaluationResponse response = evaluateWithSelectedModel(input);
        ChatResponseMetadata metadata = response.metadata();
        Usage usage = metadata == null ? null : metadata.getUsage();

        System.out.printf("%n===== INPUT =====%n%s%n%n", input);
        System.out.printf("===== OPENAI RAW =====%n%s%n%n", response.raw());
        System.out.printf("===== PARSED DTO =====%n%s%n%n", response.response().result());
        System.out.printf("===== METADATA =====%nprovider=OpenAI%nmodel=%s%npromptTokens=%s%ncompletionTokens=%s%ntotalTokens=%s%n",
                metadata == null ? null : metadata.getModel(), usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(), usage == null ? null : usage.getTotalTokens());

        assertThat(response.response().result().result()).isEqualTo(ModerationResultType.FLAGGED);
        assertThat(response.response().result().categories()).containsExactly(ModerationCategory.PERSONAL_INFORMATION);
        assertThat(response.response().result().riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(metadata).isNotNull();
        assertThat(metadata.getModel()).isNotBlank();
        assertThat(metadata.getModel()).startsWith(evaluationModel);
    }

    @Test
    void Prompt_v2_대표_회귀_6건을_실제_OpenAI로_검증한다() {
        assertAnalysis("진짜 한심한 인간이네", ModerationResultType.FLAGGED, of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM);
        assertAnalysis("꺼져, 보기 싫어", ModerationResultType.FLAGGED, of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM);
        assertAnalysis("내 번호 010-1234-5678이야", ModerationResultType.FLAGGED, of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM);
        assertAnalysis("제 이메일은 minsu@example.com입니다", ModerationResultType.FLAGGED, of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM);
        assertAnalysis("주식 리딩방에서 종목을 알려드립니다", ModerationResultType.FLAGGED, of(ModerationCategory.SPAM), RiskLevel.HIGH);
        assertAnalysis("내일 7시에 식당에서 봐요", ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);
    }

    @Test
    void Prompt_v2를_동일한_40건_Human_labeled_Dataset으로_측정한다() {
        // given
        List<ModerationTestCase> testCases = testCases();
        assertDatasetContract(testCases);
        List<Long> latencies = new ArrayList<>();
        List<EvaluationFailure> failures = new ArrayList<>();
        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        int tokenMeasuredCalls = 0;
        int resultMatches = 0;
        int categoryMatches = 0;
        int riskMatches = 0;
        int exactMatches = 0;
        int reviewActionabilityMatches = 0;
        long evaluationStartedAt = System.nanoTime();

        // when
        for (ModerationTestCase testCase : testCases) {
            long startedAt = System.nanoTime();
            try {
                AiModerationResponse response = evaluateWithSelectedModel(testCase.message()).response();
                long latencyMillis = elapsedMillis(startedAt);
                latencies.add(latencyMillis);
                ModerationResult actual = response.result();
                boolean resultMatch = actual.result() == testCase.expectedResult();
                boolean categoryMatch = actual.categories().equals(testCase.expectedCategories());
                boolean riskMatch = actual.riskLevel() == testCase.expectedRiskLevel();
                boolean reviewActionabilityMatch = isReviewTarget(actual.riskLevel())
                        == isReviewTarget(testCase.expectedRiskLevel());
                resultMatches += resultMatch ? 1 : 0;
                categoryMatches += categoryMatch ? 1 : 0;
                riskMatches += riskMatch ? 1 : 0;
                exactMatches += resultMatch && categoryMatch && riskMatch ? 1 : 0;
                reviewActionabilityMatches += reviewActionabilityMatch ? 1 : 0;
                if (!(resultMatch && categoryMatch && riskMatch)) {
                    failures.add(EvaluationFailure.mismatch(testCase, actual, latencyMillis));
                }
                if (response.promptTokens() != null && response.completionTokens() != null && response.totalTokens() != null) {
                    tokenMeasuredCalls++;
                    promptTokens += response.promptTokens();
                    completionTokens += response.completionTokens();
                    totalTokens += response.totalTokens();
                }
            } catch (RuntimeException exception) {
                long latencyMillis = elapsedMillis(startedAt);
                latencies.add(latencyMillis);
                failures.add(EvaluationFailure.providerFailure(testCase, exception.getClass().getSimpleName(), latencyMillis));
            }
        }

        // then
        long totalElapsedMillis = elapsedMillis(evaluationStartedAt);
        printSummary(testCases.size(), resultMatches, categoryMatches, riskMatches, exactMatches,
                reviewActionabilityMatches, failures, latencies, promptTokens, completionTokens, totalTokens,
                tokenMeasuredCalls, totalElapsedMillis);
        assertThat(failures).noneMatch(EvaluationFailure::isProviderFailure);
    }

    @Test
    void Evaluation_환경에서는_background_job_bean을_생성하지_않는다() {
        assertThat(applicationContext.getBeansOfType(com.bobfull.payment.infrastructure.scheduler.PaymentExpirationScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(com.bobfull.payment.infrastructure.scheduler.RefundReconciliationScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(com.bobfull.reservation.infrastructure.scheduler.RecruitmentDeadlineScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(com.bobfull.reservation.infrastructure.scheduler.ReservationClosingScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(com.bobfull.outbox.service.ChatRoomOutboxScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(com.bobfull.outbox.service.EmailOutboxScheduler.class)).isEmpty();
    }

    private static void assertDatasetContract(List<ModerationTestCase> testCases) {
        assertThat(testCases).hasSize(40);
        assertThat(testCases.stream().map(ModerationTestCase::id).distinct()).hasSize(40);
        assertThat(testCases.stream().filter(testCase -> testCase.id().startsWith("SAFE-")).count()).isEqualTo(10);
        assertThat(testCases.stream().filter(testCase -> testCase.id().startsWith("PROFANITY-")).count()).isEqualTo(10);
        assertThat(testCases.stream().filter(testCase -> testCase.id().startsWith("PI-")).count()).isEqualTo(10);
        assertThat(testCases.stream().filter(testCase -> testCase.id().startsWith("SPAM-")).count()).isEqualTo(10);
    }

    private void assertAnalysis(String message, ModerationResultType expectedResult,
            Set<ModerationCategory> expectedCategories, RiskLevel expectedRiskLevel) {
        ModerationResult actual = evaluateWithSelectedModel(message).response().result();
        assertThat(actual.result()).isEqualTo(expectedResult);
        assertThat(actual.categories()).isEqualTo(expectedCategories);
        assertThat(actual.riskLevel()).isEqualTo(expectedRiskLevel);
    }

    static void verifyDatasetContract() {
        assertDatasetContract(testCases());
    }

    private EvaluationResponse evaluateWithSelectedModel(String content) {
        ResponseEntity<ChatResponse, ModerationResult> response = moderationChatClient.prompt()
                .system(ModerationPrompt.SYSTEM_PROMPT)
                .user(content)
                .options(OpenAiModerationEvaluationOptions.forModel(evaluationModel, maxOutputTokens))
                .call()
                .responseEntity(ModerationResult.class, spec -> spec.useProviderStructuredOutput());
        ChatResponse chatResponse = response.response();
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        String model = metadata == null || metadata.getModel() == null ? evaluationModel : metadata.getModel();
        AiModerationResponse aiResponse = new AiModerationResponse(response.entity(), "OpenAI", model,
                usage == null ? null : asLong(usage.getPromptTokens()),
                usage == null ? null : asLong(usage.getCompletionTokens()),
                usage == null ? null : asLong(usage.getTotalTokens()));
        return new EvaluationResponse(aiResponse, chatResponse.getResult().getOutput().getText(), metadata);
    }

    private static Long asLong(Integer value) { return value == null ? null : value.longValue(); }

    private static void printSummary(int total, int resultMatches, int categoryMatches, int riskMatches,
            int exactMatches, int reviewActionabilityMatches, List<EvaluationFailure> failures, List<Long> latencies,
            long promptTokens, long completionTokens, long totalTokens, int tokenMeasuredCalls, long totalElapsedMillis) {
        System.out.printf("Result Accuracy              : %d/%d (%.1f%%)%n", resultMatches, total, percent(resultMatches, total));
        System.out.printf("Category Accuracy            : %d/%d (%.1f%%)%n", categoryMatches, total, percent(categoryMatches, total));
        System.out.printf("Risk Accuracy                : %d/%d (%.1f%%)%n", riskMatches, total, percent(riskMatches, total));
        System.out.printf("Exact Match                  : %d/%d (%.1f%%)%n", exactMatches, total, percent(exactMatches, total));
        System.out.printf("Review Actionability Accuracy: %d/%d (%.1f%%)%n", reviewActionabilityMatches, total,
                percent(reviewActionabilityMatches, total));
        System.out.printf("OpenAI Calls                 : %d%n", latencies.size());
        System.out.printf("Latency ms                   : avg=%.1f p95=%d p99=%d%n", average(latencies), percentile(latencies, 0.95), percentile(latencies, 0.99));
        System.out.printf("Token usage                  : measuredCalls=%d prompt=%d completion=%d total=%d%n",
                tokenMeasuredCalls, promptTokens, completionTokens, totalTokens);
        System.out.printf("Total elapsed ms (reference) : %d%n", totalElapsedMillis);
        failures.forEach(EvaluationFailure::print);
    }

    private static List<ModerationTestCase> testCases() {
        return List.of(
                safe("SAFE-01", "안녕하세요, 예약 가능한가요?"),
                safe("SAFE-02", "내일 7시에 식당에서 봐요"),
                safe("SAFE-03", "식당 전화번호는 02-1234-5678입니다"),
                safe("SAFE-04", "식당 홈페이지입니다 https://restaurant.example"),
                safe("SAFE-05", "김밥 두 줄 포장 부탁드려요"),
                safe("SAFE-06", "예약자 이름은 민수입니다"),
                safe("SAFE-07", "오늘 영업시간이 어떻게 되나요?"),
                safe("SAFE-08", "주차 가능한 식당을 찾고 있어요"),
                safe("SAFE-09", "메뉴 추천해 주세요"),
                safe("SAFE-10", "감사합니다, 내일 뵐게요"),
                flagged("PROFANITY-01", "야 이 바보야", of(ModerationCategory.PROFANITY), RiskLevel.LOW),
                flagged("PROFANITY-02", "ㅋㅋ 이 멍청아", of(ModerationCategory.PROFANITY), RiskLevel.LOW),
                flagged("PROFANITY-03", "너 정말 바보다", of(ModerationCategory.PROFANITY), RiskLevel.LOW),
                flagged("PROFANITY-04", "그렇게도 못 하니, 멍청아", of(ModerationCategory.PROFANITY), RiskLevel.LOW),
                flagged("PROFANITY-05", "진짜 한심한 인간이네", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM),
                flagged("PROFANITY-06", "너는 정말 재수 없어", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM),
                flagged("PROFANITY-07", "이런 젠장", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM),
                flagged("PROFANITY-08", "꺼져, 보기 싫어", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM),
                flagged("PROFANITY-09", "개새끼야", of(ModerationCategory.PROFANITY), RiskLevel.HIGH),
                flagged("PROFANITY-10", "죽여버린다", of(ModerationCategory.PROFANITY), RiskLevel.HIGH),
                flagged("PI-01", "내 번호 010-1234-5678이야", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM),
                flagged("PI-02", "제 이메일은 minsu@example.com입니다", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM),
                flagged("PI-03", "계좌번호 123-456-789012로 보내 주세요", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM),
                flagged("PI-04", "카카오톡 ID는 bobfull_user입니다", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM),
                flagged("PI-05", "연락처 010 9876 5432 남깁니다", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM),
                flagged("PI-06", "메일 test.user@personalmail.com으로 연락해", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM),
                flagged("PI-07", "우리 엄마 번호는 010-1111-2222야", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM),
                flagged("PI-08", "송금 계좌는 국민 123456-01-123456입니다", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM),
                flagged("PI-09", "텔레그램 아이디 @private_contact로 말해", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM),
                flagged("PI-10", "제 전화번호는 02-9876-5432입니다", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM),
                flagged("SPAM-01", "코인 수익방 들어오세요 https://example.com", of(ModerationCategory.SPAM), RiskLevel.HIGH),
                flagged("SPAM-02", "지금 가입하면 무료 쿠폰 드립니다", of(ModerationCategory.SPAM), RiskLevel.HIGH),
                flagged("SPAM-03", "부업으로 월 천만 원, 상담 링크 클릭", of(ModerationCategory.SPAM), RiskLevel.HIGH),
                flagged("SPAM-04", "VIP 투자방 멤버를 모집합니다", of(ModerationCategory.SPAM), RiskLevel.HIGH),
                flagged("SPAM-05", "친구 초대하고 포인트 받으세요", of(ModerationCategory.SPAM), RiskLevel.HIGH),
                flagged("SPAM-06", "대출 승인 보장, 지금 신청하세요", of(ModerationCategory.SPAM), RiskLevel.HIGH),
                flagged("SPAM-07", "광고 문의는 이 링크로 가입하세요 https://ad.example", of(ModerationCategory.SPAM), RiskLevel.HIGH),
                flagged("SPAM-08", "주식 리딩방에서 종목을 알려드립니다", of(ModerationCategory.SPAM), RiskLevel.HIGH),
                flagged("SPAM-09", "쇼핑몰 홍보 이벤트 참여하세요", of(ModerationCategory.SPAM), RiskLevel.HIGH),
                flagged("SPAM-10", "외부 앱 설치하면 선물을 드려요", of(ModerationCategory.SPAM), RiskLevel.HIGH)
        );
    }

    private static ModerationTestCase safe(String id, String message) {
        return new ModerationTestCase(id, message, ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);
    }

    private static ModerationTestCase flagged(String id, String message, Set<ModerationCategory> categories, RiskLevel riskLevel) {
        return new ModerationTestCase(id, message, ModerationResultType.FLAGGED, categories, riskLevel);
    }

    private static boolean isReviewTarget(RiskLevel riskLevel) { return riskLevel != RiskLevel.LOW; }
    private static long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }
    private static double percent(int value, int total) { return value * 100.0 / total; }
    private static double average(List<Long> values) { return values.stream().mapToLong(Long::longValue).average().orElse(0); }
    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1));
    }

    private record ModerationTestCase(String id, String message, ModerationResultType expectedResult,
                                      Set<ModerationCategory> expectedCategories, RiskLevel expectedRiskLevel) { }
    private record EvaluationResponse(AiModerationResponse response, String raw, ChatResponseMetadata metadata) { }
    private record EvaluationFailure(String id, ModerationResultType expectedResult, Set<ModerationCategory> expectedCategories,
                                     RiskLevel expectedRiskLevel, ModerationResultType actualResult,
                                     Set<ModerationCategory> actualCategories, RiskLevel actualRiskLevel,
                                     String providerError, long latencyMillis) {
        static EvaluationFailure mismatch(ModerationTestCase testCase, ModerationResult actual, long latencyMillis) {
            return new EvaluationFailure(testCase.id(), testCase.expectedResult(), testCase.expectedCategories(), testCase.expectedRiskLevel(),
                    actual.result(), actual.categories(), actual.riskLevel(), null, latencyMillis);
        }
        static EvaluationFailure providerFailure(ModerationTestCase testCase, String providerError, long latencyMillis) {
            return new EvaluationFailure(testCase.id(), testCase.expectedResult(), testCase.expectedCategories(), testCase.expectedRiskLevel(),
                    null, Set.of(), null, providerError, latencyMillis);
        }
        boolean isProviderFailure() { return providerError != null; }
        void print() {
            System.out.printf("[FAIL] %s expected=%s/%s/%s actual=%s/%s/%s error=%s latencyMs=%d%n", id,
                    expectedResult, expectedCategories, expectedRiskLevel, actualResult, actualCategories, actualRiskLevel,
                    providerError, latencyMillis);
        }
    }
}
