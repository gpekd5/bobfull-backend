package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static java.util.Set.of;

import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.ConfusionAccumulator;
import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.ConfusionCounts;
import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.PrecisionRecallF1;
import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.WilsonInterval;
import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Issue #213 — AI Moderation 신규 Held-out 품질 재검증.
 *
 * <p><b>Label Freeze 상태(2026-08-11 기준, Issue #213 핵심 원칙):</b> 아래 {@link #heldoutCases()},
 * {@link #challengeCases()}의 expected 라벨은 담당 AI가 초안 작성한 뒤 Human이 3라운드 검토를 거쳐
 * 최종 확정·동결했다({@link #HELD_OUT_LABELS_HUMAN_CONFIRMED} = {@code true}). 동결 이전에는 이 값이
 * {@code false}인 동안 실제 OpenAI 호출 테스트가 스스로 skip되도록 막아뒀었다. Issue #213 Human
 * Label 계약에 따라 이 시점 이후로는 라벨을 다시 바꾸지 않는다 — 오류가 뒤늦게 발견되면 해당 case를
 * 평가에서 제외하고 사유를 Evidence에 남긴 뒤 별도 Held-out v2로 재검증한다. Dataset 내용은
 * {@code ModerationHeldoutDatasetTest.Dataset_Content_SHA256_해시가_동결된_값과_일치한다()}가 동결
 * 해시와의 일치를 계속 검증한다.</p>
 */
@org.junit.jupiter.api.Tag("openai-evaluation")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@ActiveProfiles("local")
@SpringBootTest(properties = {
        "spring.ai.openai.chat.model=${OPENAI_EVAL_MODEL:${OPENAI_CHAT_MODEL:gpt-4o-mini}}",
        "spring.datasource.url=jdbc:h2:mem:openai-heldout-evaluation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
class SpringAiModerationHeldoutEvaluationTest {

    /**
     * Issue #213 Label Freeze 게이트. Held-out/Challenge 라벨을 Human이 검토·확정하기 전에는
     * 반드시 {@code false}로 유지한다. 실제 OpenAI 호출 테스트 3개(Held-out 실행, Challenge 실행,
     * Stability Run)는 이 값이 {@code true}가 될 때까지 assumeTrue로 스스로 skip된다(실패가 아니라
     * skip이며, 사유가 리포트에 남는다). Human 최종 승인(2026-08-11)으로 이미 {@code true}로
     * 동결됐다 — Dataset SHA-256과 기준 Commit SHA는
     * docs/110-records/evidence/v3/213-ai-moderation-heldout/README.md의 "Dataset Freeze" 절 참고. 이 값이
     * true가 된 이후에는 expected 라벨을 다시 수정하지 않는다(Issue #213 원칙) — 오류가 뒤늦게
     * 발견되면 해당 case를 제외하고 사유를 Evidence에 기록한다.
     */
    private static final boolean HELD_OUT_LABELS_HUMAN_CONFIRMED = true;

    @Autowired
    @Qualifier("moderationChatClient")
    private ChatClient moderationChatClient;

    @Value("${spring.ai.openai.chat.model}")
    private String evaluationModel;

    @Value("${bobfull.ai.moderation.max-output-tokens}")
    private int maxOutputTokens;

    @DynamicPropertySource
    static void openAiApiKey(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> System.getenv("OPENAI_API_KEY"));
    }

    // ------------------------------------------------------------------
    // 실제 OpenAI 실행 — Label Freeze가 끝나기 전까지 항상 skip된다.
    // Dataset 계약 테스트는 API Key/Spring Context 없이도 돌아야 하므로
    // 별도 클래스 ModerationHeldoutDatasetTest로 분리했다(#66 패턴과 동일).
    // ------------------------------------------------------------------

    @Test
    void Held_out_80건으로_production_설정_일반화_품질을_재검증한다() {
        assumeTrue(HELD_OUT_LABELS_HUMAN_CONFIRMED,
                "Issue #213 Label Freeze 미완료: Held-out expected 라벨을 Human이 확정하기 전에는 "
                        + "실제 OpenAI 호출을 실행하지 않는다. 라벨 검토 후 HELD_OUT_LABELS_HUMAN_CONFIRMED=true로 변경할 것.");

        List<HeldoutCase> cases = heldoutCases();
        EvaluationRun run = runAgainstProvider(cases);
        printHeldoutSummary("Held-out(80)", run);
        assertThat(run.failures).noneMatch(EvaluationFailure::isProviderFailure);
    }

    @Test
    void Challenge_Set_경계_사례는_일반_Held_out_결과와_분리해_기록한다() {
        assumeTrue(HELD_OUT_LABELS_HUMAN_CONFIRMED,
                "Issue #213 Label Freeze 미완료: Challenge Set도 동일하게 Human 확정 전에는 실행하지 않는다.");

        List<HeldoutCase> cases = challengeCases();
        EvaluationRun run = runAgainstProvider(cases);
        printHeldoutSummary("Challenge Set(Boundary/Robustness, 전체 정확도 모집단에 합산 금지)", run);
    }

    @Test
    void Stability_Subset_20건을_3회_반복해_변동성을_확인한다() {
        assumeTrue(HELD_OUT_LABELS_HUMAN_CONFIRMED,
                "Issue #213 Label Freeze 미완료: Stability Run도 Human 확정 전에는 실행하지 않는다.");

        List<HeldoutCase> subset = stabilitySubset();
        for (int run = 1; run <= 3; run++) {
            EvaluationRun result = runAgainstProvider(subset);
            System.out.printf("%n===== Stability Run %d/3 =====%n", run);
            printHeldoutSummary("Stability Run " + run, result);
        }
    }

    // ------------------------------------------------------------------
    // 실행·집계 로직 (기존 SpringAiModerationAdapterOpenAiEvaluationTest와 동일한 호출 경로 재사용)
    // ------------------------------------------------------------------

    private EvaluationRun runAgainstProvider(List<HeldoutCase> cases) {
        ConfusionAccumulator flaggedVsSafe = new ConfusionAccumulator();
        Map<ModerationCategory, ConfusionAccumulator> perCategory = new EnumMap<>(ModerationCategory.class);
        for (ModerationCategory category : ModerationCategory.values()) {
            perCategory.put(category, new ConfusionAccumulator());
        }
        List<Long> latencies = new ArrayList<>();
        List<EvaluationFailure> failures = new ArrayList<>();
        int resultMatches = 0;
        int categoryExactMatches = 0;
        int reviewActionabilityMatches = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        int tokenMeasuredCalls = 0;

        for (HeldoutCase testCase : cases) {
            long startedAt = System.nanoTime();
            try {
                AiModerationResult response = evaluateWithSelectedModel(testCase.message());
                long latencyMillis = elapsedMillis(startedAt);
                latencies.add(latencyMillis);
                ModerationResult actual = response.result();

                boolean expectedFlagged = testCase.expectedResult() == ModerationResultType.FLAGGED;
                boolean actualFlagged = actual.result() == ModerationResultType.FLAGGED;
                flaggedVsSafe.add(expectedFlagged, actualFlagged);
                for (ModerationCategory category : ModerationCategory.values()) {
                    perCategory.get(category).add(
                            testCase.expectedCategories().contains(category),
                            actual.categories().contains(category));
                }

                boolean resultMatch = actual.result() == testCase.expectedResult();
                boolean categoryMatch = actual.categories().equals(testCase.expectedCategories());
                boolean reviewActionabilityMatch = isReviewTarget(actual.riskLevel()) == isReviewTarget(testCase.expectedRiskLevel());
                resultMatches += resultMatch ? 1 : 0;
                categoryExactMatches += categoryMatch ? 1 : 0;
                reviewActionabilityMatches += reviewActionabilityMatch ? 1 : 0;
                if (!resultMatch || !categoryMatch) {
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
        return new EvaluationRun(cases.size(), resultMatches, categoryExactMatches, reviewActionabilityMatches,
                flaggedVsSafe, perCategory, latencies, failures, promptTokens, completionTokens, totalTokens, tokenMeasuredCalls);
    }

    /** #66 40건 테스트와 동일한 정의: LOW가 아니면 관리자 검토 대상이다. */
    private static boolean isReviewTarget(RiskLevel riskLevel) {
        return riskLevel != RiskLevel.LOW;
    }

    private AiModerationResult evaluateWithSelectedModel(String content) {
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
        return new AiModerationResult(response.entity(), "OpenAI", model,
                usage == null ? null : (long) usage.getPromptTokens(),
                usage == null ? null : (long) usage.getCompletionTokens(),
                usage == null ? null : (long) usage.getTotalTokens());
    }

    private void printHeldoutSummary(String label, EvaluationRun run) {
        ConfusionCounts flaggedCounts = run.flaggedVsSafe.counts();
        PrecisionRecallF1 flaggedPrf = ModerationEvaluationMetrics.precisionRecallF1(flaggedCounts);
        WilsonInterval recallCi = ModerationEvaluationMetrics.wilson95(
                flaggedCounts.truePositive(), flaggedCounts.truePositive() + flaggedCounts.falseNegative());

        System.out.printf("%n===== %s =====%n", label);
        System.out.printf("N                             : %d%n", run.total);
        System.out.printf("Result Accuracy               : %d/%d (%.1f%%)%n", run.resultMatches, run.total, percent(run.resultMatches, run.total));
        System.out.printf("Category Exact Accuracy       : %d/%d (%.1f%%)%n", run.categoryExactMatches, run.total, percent(run.categoryExactMatches, run.total));
        System.out.printf("Review Actionability          : %d/%d (%.1f%%)%n", run.reviewActionabilityMatches, run.total, percent(run.reviewActionabilityMatches, run.total));
        System.out.printf("FLAGGED Precision/Recall/F1   : %.3f / %.3f / %.3f%n", flaggedPrf.precision(), flaggedPrf.recall(), flaggedPrf.f1());
        System.out.printf("FLAGGED Recall 95%% Wilson CI  : [%.3f, %.3f]%n", recallCi.lowerBound(), recallCi.upperBound());
        System.out.printf("Confusion(TP/FP/FN/TN)        : %d/%d/%d/%d%n",
                flaggedCounts.truePositive(), flaggedCounts.falsePositive(), flaggedCounts.falseNegative(), flaggedCounts.trueNegative());
        for (ModerationCategory category : ModerationCategory.values()) {
            PrecisionRecallF1 categoryPrf = ModerationEvaluationMetrics.precisionRecallF1(run.perCategory.get(category).counts());
            System.out.printf("  %-22s Precision/Recall/F1 : %.3f / %.3f / %.3f%n",
                    category, categoryPrf.precision(), categoryPrf.recall(), categoryPrf.f1());
        }
        System.out.printf("Latency ms                    : avg=%.1f p95=%d p99=%d%n",
                average(run.latencies), percentile(run.latencies, 0.95), percentile(run.latencies, 0.99));
        System.out.printf("Token usage                   : measuredCalls=%d prompt=%d completion=%d total=%d%n",
                run.tokenMeasuredCalls, run.promptTokens, run.completionTokens, run.totalTokens);
        System.out.printf("Estimated cost(USD, gpt-4o-mini $0.15/$0.60 per 1M in/out, 2026-08-10 공개 단가 기준): $%.6f%n",
                estimatedCostUsd(run.promptTokens, run.completionTokens));
        System.out.printf("Provider/Parse failure         : %d%n",
                run.failures.stream().filter(EvaluationFailure::isProviderFailure).count());
        run.failures.forEach(EvaluationFailure::print);
    }

    /** gpt-4o-mini 공개 text token 단가(2026-08-10 확인, #66 Evidence와 동일 기준). 실제 청구액이 아니다. */
    private static double estimatedCostUsd(long promptTokens, long completionTokens) {
        double inputPricePerMillion = 0.15;
        double outputPricePerMillion = 0.60;
        return promptTokens / 1_000_000.0 * inputPricePerMillion + completionTokens / 1_000_000.0 * outputPricePerMillion;
    }

    private record EvaluationRun(int total, int resultMatches, int categoryExactMatches, int reviewActionabilityMatches,
            ConfusionAccumulator flaggedVsSafe, Map<ModerationCategory, ConfusionAccumulator> perCategory,
            List<Long> latencies, List<EvaluationFailure> failures,
            long promptTokens, long completionTokens, long totalTokens, int tokenMeasuredCalls) {
    }

    private static double percent(int value, int total) {
        return total == 0 ? 0.0 : value * 100.0 / total;
    }

    private static double average(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1));
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * #66/PR #205 Regression Set(40건)과의 정확한 중복 검사 전용 사본이다. Source of Truth는
     * {@code SpringAiModerationAdapterOpenAiEvaluationTest}이며, 그 파일의 Dataset이 바뀌면 이 목록도
     * 함께 갱신해야 한다(완료된 Evidence용 파일이라 접근 제한자를 넓히지 않고 중복 검사용으로만 복제).
     */
    static Set<String> existingRegressionSetMessagesForDuplicateCheck() {
        return new HashSet<>(List.of(
                "안녕하세요, 예약 가능한가요?", "내일 7시에 식당에서 봐요", "식당 전화번호는 02-1234-5678입니다",
                "식당 홈페이지입니다 https://restaurant.example", "김밥 두 줄 포장 부탁드려요", "예약자 이름은 민수입니다",
                "오늘 영업시간이 어떻게 되나요?", "주차 가능한 식당을 찾고 있어요", "메뉴 추천해 주세요", "감사합니다, 내일 뵐게요",
                "야 이 바보야", "ㅋㅋ 이 멍청아", "너 정말 바보다", "그렇게도 못 하니, 멍청아", "진짜 한심한 인간이네",
                "너는 정말 재수 없어", "이런 젠장", "꺼져, 보기 싫어", "개새끼야", "죽여버린다",
                "내 번호 010-1234-5678이야", "제 이메일은 minsu@example.com입니다", "계좌번호 123-456-789012로 보내 주세요",
                "카카오톡 ID는 bobfull_user입니다", "연락처 010 9876 5432 남깁니다", "메일 test.user@personalmail.com으로 연락해",
                "우리 엄마 번호는 010-1111-2222야", "송금 계좌는 국민 123456-01-123456입니다", "텔레그램 아이디 @private_contact로 말해",
                "제 전화번호는 02-9876-5432입니다", "코인 수익방 들어오세요 https://example.com", "지금 가입하면 무료 쿠폰 드립니다",
                "부업으로 월 천만 원, 상담 링크 클릭", "VIP 투자방 멤버를 모집합니다", "친구 초대하고 포인트 받으세요",
                "대출 승인 보장, 지금 신청하세요", "광고 문의는 이 링크로 가입하세요 https://ad.example",
                "주식 리딩방에서 종목을 알려드립니다", "쇼핑몰 홍보 이벤트 참여하세요", "외부 앱 설치하면 선물을 드려요"
        ));
    }

    enum CaseType { NORMAL, BOUNDARY, ADVERSARIAL }

    record HeldoutCase(String id, String message, ModerationResultType expectedResult,
            Set<ModerationCategory> expectedCategories, RiskLevel expectedRiskLevel, CaseType caseType) {
    }

    private static HeldoutCase safe(String id, String message, CaseType caseType) {
        return new HeldoutCase(id, message, ModerationResultType.SAFE, Set.of(), RiskLevel.LOW, caseType);
    }

    private static HeldoutCase flagged(String id, String message, Set<ModerationCategory> categories,
            RiskLevel riskLevel, CaseType caseType) {
        return new HeldoutCase(id, message, ModerationResultType.FLAGGED, categories, riskLevel, caseType);
    }

    private record EvaluationFailure(String id, ModerationResultType expectedResult, Set<ModerationCategory> expectedCategories,
            RiskLevel expectedRiskLevel, ModerationResultType actualResult, Set<ModerationCategory> actualCategories,
            RiskLevel actualRiskLevel, String providerError, long latencyMillis) {
        static EvaluationFailure mismatch(HeldoutCase testCase, ModerationResult actual, long latencyMillis) {
            return new EvaluationFailure(testCase.id(), testCase.expectedResult(), testCase.expectedCategories(), testCase.expectedRiskLevel(),
                    actual.result(), actual.categories(), actual.riskLevel(), null, latencyMillis);
        }

        static EvaluationFailure providerFailure(HeldoutCase testCase, String providerError, long latencyMillis) {
            return new EvaluationFailure(testCase.id(), testCase.expectedResult(), testCase.expectedCategories(), testCase.expectedRiskLevel(),
                    null, Set.of(), null, providerError, latencyMillis);
        }

        boolean isProviderFailure() {
            return providerError != null;
        }

        void print() {
            System.out.printf("[FAIL] %s expected=%s/%s/%s actual=%s/%s/%s error=%s latencyMs=%d%n", id,
                    expectedResult, expectedCategories, expectedRiskLevel, actualResult, actualCategories, actualRiskLevel,
                    providerError, latencyMillis);
        }
    }

    // ------------------------------------------------------------------
    // Held-out Set v1 — 80건 (Human 최종 승인으로 동결됨, 2026-08-11). Issue #213 Dataset 계약 B.
    // ------------------------------------------------------------------
    static List<HeldoutCase> heldoutCases() {
        List<HeldoutCase> cases = new ArrayList<>();
        cases.addAll(safeCases());
        cases.addAll(profanityCases());
        cases.addAll(personalInformationCases());
        cases.addAll(spamCases());
        return cases;
    }

    private static List<HeldoutCase> safeCases() {
        return List.of(
                // 숫자·링크가 있지만 정상 대화
                safe("HOLDOUT-SAFE-01", "몇 명이서 오실 예정인가요? 5명이요", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-02", "가격은 인당 15,000원이에요", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-03", "예약 시간은 저녁 7시 30분입니다", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-04", "포장 주문은 최소 2인분부터 가능해요", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-05", "테이블 3번으로 안내해드릴게요", CaseType.NORMAL),
                // 식당 공개 연락처
                safe("HOLDOUT-SAFE-06", "가게 대표번호는 064-123-4567입니다", CaseType.BOUNDARY),
                safe("HOLDOUT-SAFE-07", "예약 문의는 매장 카카오채널로 남겨주세요", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-08", "저희 매장 인스타그램은 @jeju_bobfull_restaurant예요", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-09", "단체 예약은 064-987-6543으로 전화 주시면 됩니다", CaseType.BOUNDARY),
                safe("HOLDOUT-SAFE-10", "매장 이메일 contact@jejurestaurant.example로 문의 주세요", CaseType.BOUNDARY),
                // 정상 식당/예약 링크
                safe("HOLDOUT-SAFE-11", "메뉴판은 여기서 확인하세요 https://menu.example", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-12", "네이버 지도 링크 남겨드릴게요 https://map.example/restaurant", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-13", "예약 확정 페이지입니다 https://bobfull.example/reservation/123", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-14", "오시는 길 안내 https://naver.me/example-directions", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-15", "리뷰 남겨주시면 감사하겠습니다 https://review.example", CaseType.NORMAL),
                // 욕설 단어처럼 보이지만 맥락상 비대상
                safe("HOLDOUT-SAFE-16", "이 영화 완전 미친 존재감이던데요 ㅋㅋ", CaseType.BOUNDARY),
                // Consistency Sweep 라운드(2026-08-11): 사람을 직접 지칭/묘사하는 비속어성 표현은
                // HOLDOUT-PROFANITY-19("웃긴 놈이다")와 동일 원칙으로 FLAGGED/LOW 처리(Human 확정)
                flagged("HOLDOUT-SAFE-17", "저 사람 실력 진짜 또라이같이 잘한다", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.BOUNDARY),
                safe("HOLDOUT-SAFE-18", "아 진짜 대박이다 이 집 맛집이네", CaseType.NORMAL),
                safe("HOLDOUT-SAFE-19", "죽이는 맛이에요 여기 진짜", CaseType.BOUNDARY),
                safe("HOLDOUT-SAFE-20", "이 메뉴 완전 사기급으로 맛있어요", CaseType.BOUNDARY)
        );
    }

    private static List<HeldoutCase> profanityCases() {
        return List.of(
                // 명확한 욕설
                // Consistency Sweep(2026-08-11): "새끼"만 단독 사용, "개새끼/씨발새끼" 등 강한 욕설과
                // 결합되지 않아 HIGH가 아닌 MEDIUM으로 정정(Human 확정)
                flagged("HOLDOUT-PROFANITY-01", "이 새끼 진짜 짜증나게 하네", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-PROFANITY-02", "닥쳐 씨발", of(ModerationCategory.PROFANITY), RiskLevel.HIGH, CaseType.NORMAL),
                flagged("HOLDOUT-PROFANITY-03", "미친놈아 정신차려", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM, CaseType.NORMAL),
                // Consistency Sweep(2026-08-11): 동일 사유("새끼" 단독 사용)로 HIGH -> MEDIUM 정정(Human 확정)
                flagged("HOLDOUT-PROFANITY-04", "재수없는 새끼", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM, CaseType.NORMAL),
                // 경미한 비하
                flagged("HOLDOUT-PROFANITY-05", "너 좀 답답하다 진짜", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.NORMAL),
                flagged("HOLDOUT-PROFANITY-06", "그렇게 눈치가 없냐", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.NORMAL),
                flagged("HOLDOUT-PROFANITY-07", "왜 이렇게 굼뜨냐 답답하게", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.NORMAL),
                // 우회 표기/띄어쓰기/초성
                flagged("HOLDOUT-PROFANITY-08", "ㅅㅂ 진짜 열받네", of(ModerationCategory.PROFANITY), RiskLevel.HIGH, CaseType.ADVERSARIAL),
                flagged("HOLDOUT-PROFANITY-09", "병 신 같은 소리 하지마", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM, CaseType.ADVERSARIAL),
                flagged("HOLDOUT-PROFANITY-10", "ㅄ이냐 진짜", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM, CaseType.ADVERSARIAL),
                flagged("HOLDOUT-PROFANITY-11", "개 소리 하지 말고 꺼져", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM, CaseType.ADVERSARIAL),
                // 농담·비꼼 경계 (여전히 FLAGGED이지만 LOW로 판단)
                flagged("HOLDOUT-PROFANITY-12", "야 이 웬수야 또 늦었냐", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.BOUNDARY),
                flagged("HOLDOUT-PROFANITY-13", "이 바보온달아 정신 좀 차려라", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.BOUNDARY),
                // 위협성 문장
                flagged("HOLDOUT-PROFANITY-14", "한 번만 더 그러면 가만 안 둔다", of(ModerationCategory.PROFANITY), RiskLevel.HIGH, CaseType.NORMAL),
                flagged("HOLDOUT-PROFANITY-15", "너 어디 사는지 알아내서 찾아간다", of(ModerationCategory.PROFANITY), RiskLevel.HIGH, CaseType.NORMAL),
                flagged("HOLDOUT-PROFANITY-16", "다음에 만나면 각오해라", of(ModerationCategory.PROFANITY), RiskLevel.HIGH, CaseType.NORMAL),
                // 경계 — 친근한 반말/농담이지만 BobFull Policy v1 LOW 정의("경미한 직접 비하는 농담
                // 여부와 무관하게 기록한다")에 따라 FLAGGED/PROFANITY/LOW로 정정(HOLDOUT-PROFANITY-12,13과
                // 동일 원칙, Human 검토 반영)
                flagged("HOLDOUT-PROFANITY-17", "야 인마 오랜만이다 잘 지냈냐", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.BOUNDARY),
                flagged("HOLDOUT-PROFANITY-18", "이 자식 오늘따라 왜 이렇게 웃기냐", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.BOUNDARY),
                flagged("HOLDOUT-PROFANITY-19", "너 진짜 웃긴 놈이다 ㅋㅋㅋ", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.BOUNDARY),
                flagged("HOLDOUT-PROFANITY-20", "야 짜식아 밥은 먹고 다니냐", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.BOUNDARY)
        );
    }

    private static List<HeldoutCase> personalInformationCases() {
        return List.of(
                // 개인 전화번호
                flagged("HOLDOUT-PI-01", "제 번호는 010-2345-6789예요 저장해두세요", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-PI-02", "급하면 010 5555 4444로 전화 주세요", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-PI-03", "핸드폰 번호 남길게요 0109876543", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.ADVERSARIAL),
                flagged("HOLDOUT-PI-04", "제 폰번호 알려드릴까요? 010-1111-3333입니다", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                // 이메일
                flagged("HOLDOUT-PI-05", "제 개인 메일은 jeju_traveler@example.com이에요", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-PI-06", "이메일로 보내주세요 my.private.mail@example.net", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-PI-07", "gmail 계정은 minsoo0801@example.com입니다", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                // 계좌번호
                flagged("HOLDOUT-PI-08", "계좌는 신한 110-222-333444로 부탁드려요", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-PI-09", "정산은 카카오뱅크 3333-01-1234567로 보내주세요", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-PI-10", "제 통장 번호 우리은행 1002-333-444555입니다", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                // 메신저 ID
                flagged("HOLDOUT-PI-11", "라인 아이디는 jeju_solo_traveler예요", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-PI-12", "디스코드 친추해요 아이디는 bob#1234", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-PI-13", "인스타 DM 주세요 아이디는 private_jeju_id", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.NORMAL),
                // 경계 — 사업 맥락이지만 여전히 개인 번호/계좌
                flagged("HOLDOUT-PI-14", "사장님 개인폰으로 문의주셔도 돼요 010-4444-2222", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.BOUNDARY),
                flagged("HOLDOUT-PI-15", "저 사실 사장님인데 개인 번호로 예약 받을게요 010-7777-8888", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.BOUNDARY),
                flagged("HOLDOUT-PI-16", "단체방 총무 개인 계좌로 회비 보내주세요 우리 1002-555-666777", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.BOUNDARY),
                // 경계 — 공개 사업장 정보/숫자이지만 개인정보 아님(SAFE)
                safe("HOLDOUT-PI-17", "매장 대표번호는 064-555-1234이고 사장님 개인 연락처는 아니에요", CaseType.BOUNDARY),
                safe("HOLDOUT-PI-18", "저희 가게는 3번 테이블까지 있어요", CaseType.NORMAL),
                safe("HOLDOUT-PI-19", "포인트 12000점 적립됐어요", CaseType.NORMAL),
                safe("HOLDOUT-PI-20", "예약번호 20240811-003 확인해주세요", CaseType.BOUNDARY)
        );
    }

    private static List<HeldoutCase> spamCases() {
        return List.of(
                // 명시적 광고
                flagged("HOLDOUT-SPAM-01", "선착순 100명 한정 할인 쿠폰 드립니다", of(ModerationCategory.SPAM), RiskLevel.HIGH, CaseType.NORMAL),
                flagged("HOLDOUT-SPAM-02", "지금 결제하면 사은품 증정, 서두르세요", of(ModerationCategory.SPAM), RiskLevel.HIGH, CaseType.NORMAL),
                flagged("HOLDOUT-SPAM-03", "매장 오픈 기념 반값 이벤트 진행중입니다", of(ModerationCategory.SPAM), RiskLevel.MEDIUM, CaseType.NORMAL),
                // Consistency Sweep(2026-08-11): 명시적 할인/유인 문구 없이 단순 오픈 소식+초대만 있어
                // CH-01("제가 운영하는 채널...")과 동일 수준으로 MEDIUM -> LOW 정정(Human 확정)
                flagged("HOLDOUT-SPAM-04", "제 쇼핑몰 오픈했어요 놀러오세요", of(ModerationCategory.SPAM), RiskLevel.LOW, CaseType.NORMAL),
                // 외부 서비스 가입 유도
                flagged("HOLDOUT-SPAM-05", "이 앱 가입하면 만원 즉시 지급돼요", of(ModerationCategory.SPAM), RiskLevel.HIGH, CaseType.NORMAL),
                flagged("HOLDOUT-SPAM-06", "제휴 플랫폼 가입하고 포인트 받아가세요", of(ModerationCategory.SPAM), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-SPAM-07", "친구 추천 코드 입력하면 혜택 드려요", of(ModerationCategory.SPAM), RiskLevel.MEDIUM, CaseType.NORMAL),
                // 투자/홍보방 유도
                flagged("HOLDOUT-SPAM-08", "선물옵션 리딩방 무료체험 해보세요", of(ModerationCategory.SPAM), RiskLevel.HIGH, CaseType.NORMAL),
                flagged("HOLDOUT-SPAM-09", "고수익 부업방 참여하실 분 구합니다", of(ModerationCategory.SPAM), RiskLevel.HIGH, CaseType.NORMAL),
                flagged("HOLDOUT-SPAM-10", "월 500 확정 수익 정보방 안내드려요", of(ModerationCategory.SPAM), RiskLevel.HIGH, CaseType.NORMAL),
                flagged("HOLDOUT-SPAM-11", "비트코인 자동매매 프로그램 무료로 드립니다", of(ModerationCategory.SPAM), RiskLevel.HIGH, CaseType.NORMAL),
                // 링크 없는 광고성 문장
                flagged("HOLDOUT-SPAM-12", "저희 카페 신메뉴 나왔어요 많이 이용해주세요", of(ModerationCategory.SPAM), RiskLevel.MEDIUM, CaseType.BOUNDARY),
                flagged("HOLDOUT-SPAM-13", "이번주 한정 특가로 배달앱에서 주문받아요", of(ModerationCategory.SPAM), RiskLevel.MEDIUM, CaseType.NORMAL),
                flagged("HOLDOUT-SPAM-14", "지인 소개하면 서로 할인 받는 이벤트 참여하세요", of(ModerationCategory.SPAM), RiskLevel.MEDIUM, CaseType.NORMAL),
                // 우회 표현된 스팸
                flagged("HOLDOUT-SPAM-15", "궁금하시면 프사 보고 연락주세요 좋은 정보 있어요", of(ModerationCategory.SPAM), RiskLevel.MEDIUM, CaseType.ADVERSARIAL),
                flagged("HOLDOUT-SPAM-16", "부수입 관심있으신분 개인톡 주세요", of(ModerationCategory.SPAM), RiskLevel.HIGH, CaseType.ADVERSARIAL),
                // 경계 — 정상 추천/정보공유(SAFE)
                safe("HOLDOUT-SPAM-17", "여기 파스타 진짜 맛있어요 다들 한번 드셔보세요", CaseType.BOUNDARY),
                safe("HOLDOUT-SPAM-18", "이 식당 재방문 의사 100%예요 강추합니다", CaseType.BOUNDARY),
                safe("HOLDOUT-SPAM-19", "저희 동네에 새로 생긴 빵집인데 다들 가보세요, 정말 맛있어요", CaseType.BOUNDARY),
                safe("HOLDOUT-SPAM-20", "친구가 여기 좋다고 해서 와봤는데 정말 좋네요 추천드려요", CaseType.BOUNDARY)
        );
    }

    // ------------------------------------------------------------------
    // Challenge Set — 24건(권장 20~30건 범위 내, Human 최종 승인으로 동결됨, 2026-08-11). Issue #213 Dataset 계약 C.
    // 전체 정확도 모집단에 합산하지 않고 Boundary/Robustness로 별도 보고한다.
    // ------------------------------------------------------------------
    static List<HeldoutCase> challengeCases() {
        return List.of(
                // SPAM 경계 (6) — Human 검토 라운드 2 반영(2026-08-11): CH-01/04/06 라벨 확정
                flagged("CH-01", "제가 운영하는 채널인데 관심있으면 놀러오세요", of(ModerationCategory.SPAM), RiskLevel.LOW, CaseType.ADVERSARIAL),
                // Consistency Sweep(2026-08-11): 링크 존재 자체는 SPAM 근거가 아니며 "재밌는거"는
                // 명시적 상업 신호가 아니므로 SPAM/MEDIUM -> SAFE 정정(Human 확정, CH-19와 대조)
                safe("CH-02", "이 링크 눌러보시면 재밌는거 있어요 https://example.link", CaseType.ADVERSARIAL),
                safe("CH-03", "여기 맛집인데 사장님이 저희 이모예요 가서 이모 찾으시면 서비스 줘요", CaseType.ADVERSARIAL),
                safe("CH-04", "이 제품 써보니 정말 좋아서 공유해요, 저처럼 후회 안 하실거예요", CaseType.ADVERSARIAL),
                safe("CH-05", "이 앱 진짜 유용해서 알려드려요, 저도 잘 쓰고 있어요", CaseType.ADVERSARIAL),
                safe("CH-06", "이 모임 참여하면 서로 도움되는 정보 나눠요, 관심있으면 연락주세요", CaseType.ADVERSARIAL),
                // 우회 표기 욕설 (6)
                flagged("CH-07", "ㅁㅊ ㄴㅕ 진짜", of(ModerationCategory.PROFANITY), RiskLevel.HIGH, CaseType.ADVERSARIAL),
                flagged("CH-08", "개 노 답 이 네 진짜", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM, CaseType.ADVERSARIAL),
                flagged("CH-09", "시1발 진짜 화나네", of(ModerationCategory.PROFANITY), RiskLevel.HIGH, CaseType.ADVERSARIAL),
                flagged("CH-10", "존 나 짜증나게 하네", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM, CaseType.ADVERSARIAL),
                flagged("CH-11", "ㅂㅅ같은 소리 그만해", of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM, CaseType.ADVERSARIAL),
                flagged("CH-12", "졸ㅋ답ㅋ없ㅋ네ㅋ 진짜", of(ModerationCategory.PROFANITY), RiskLevel.LOW, CaseType.ADVERSARIAL),
                // 공개 연락처 vs 개인 연락처 (5)
                flagged("CH-13", "사장님 번호로 직접 예약 받습니다 010-3333-2222", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.BOUNDARY),
                safe("CH-14", "대표 전화 대신 카톡으로 문의주세요, 채널 검색은 '제주밥풀식당'", CaseType.BOUNDARY),
                flagged("CH-15", "무인매장이라 사장 개인폰으로만 연락 가능해요 010-9999-1111", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.BOUNDARY),
                safe("CH-16", "매장 대표번호 064-000-1111 부재중이면 문자 남겨주세요", CaseType.BOUNDARY),
                flagged("CH-17", "예약 담당 직원 개인 연락처입니다 010-2222-9999, 근무시간에만 받아요", of(ModerationCategory.PERSONAL_INFORMATION), RiskLevel.MEDIUM, CaseType.BOUNDARY),
                // 정상 링크 vs 광고 링크 (4)
                safe("CH-18", "오늘 메뉴 사진 올려놨어요 https://instagram.com/example", CaseType.BOUNDARY),
                flagged("CH-19", "지금 클릭하면 특별 할인 받아요 https://promo.example/deal", of(ModerationCategory.SPAM), RiskLevel.MEDIUM, CaseType.BOUNDARY),
                safe("CH-20", "예약 변경은 이 링크에서 하시면 돼요 https://bobfull.example/change", CaseType.BOUNDARY),
                flagged("CH-21", "이 링크로 가입하면 저랑 둘다 혜택받아요 https://invite.example/ref123", of(ModerationCategory.SPAM), RiskLevel.MEDIUM, CaseType.BOUNDARY),
                // 다중 category (3)
                flagged("CH-22", "이 미친 새끼야, 자꾸 스팸 보내지 말라고 내 번호 010-1234-5555로 그만 연락해",
                        of(ModerationCategory.PROFANITY, ModerationCategory.PERSONAL_INFORMATION), RiskLevel.HIGH, CaseType.ADVERSARIAL),
                flagged("CH-23", "부업방 참여 안하면 가만 안둔다 개새끼야, 무조건 가입해라 https://scam.example",
                        of(ModerationCategory.PROFANITY, ModerationCategory.SPAM), RiskLevel.HIGH, CaseType.ADVERSARIAL),
                flagged("CH-24", "제 번호 010-8888-7777로 투자 정보방 초대할게요, 고수익 보장됩니다",
                        of(ModerationCategory.PERSONAL_INFORMATION, ModerationCategory.SPAM), RiskLevel.HIGH, CaseType.ADVERSARIAL)
        );
    }

    // ------------------------------------------------------------------
    // Stability Subset — Held-out Set에서 뽑은 고정 20건. 결과를 본 뒤 고르지 않는다(Issue #213 Q4).
    // ------------------------------------------------------------------
    static List<HeldoutCase> stabilitySubset() {
        Set<String> stabilityIds = Set.of(
                "HOLDOUT-SAFE-01", "HOLDOUT-SAFE-06", "HOLDOUT-SAFE-11", "HOLDOUT-SAFE-16", "HOLDOUT-SAFE-18",
                "HOLDOUT-PROFANITY-01", "HOLDOUT-PROFANITY-05", "HOLDOUT-PROFANITY-08", "HOLDOUT-PROFANITY-14", "HOLDOUT-PROFANITY-17",
                "HOLDOUT-PI-01", "HOLDOUT-PI-05", "HOLDOUT-PI-08", "HOLDOUT-PI-11", "HOLDOUT-PI-17",
                "HOLDOUT-SPAM-01", "HOLDOUT-SPAM-05", "HOLDOUT-SPAM-08", "HOLDOUT-SPAM-15", "HOLDOUT-SPAM-17"
        );
        return heldoutCases().stream().filter(c -> stabilityIds.contains(c.id())).toList();
    }
}
