package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.exception.ModerationAnalysisException;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ModerationResultValidationSupport;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchUsage;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.CaseObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Failure;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.MappingIssues;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Target;
import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.ConfusionAccumulator;
import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.ConfusionCounts;
import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.PrecisionRecallF1;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.openai.errors.OpenAIException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.core.JacksonException;

// 현재 실행의 단건 결과를 기준으로만 비교한다. 과거 Before를 사용하는 transition()은 호출하지 않는다.
final class Issue55MicroBatchComparison {
    static final double PRIOR_ESTIMATED_USD = 0.054155 + 0.00074655;
    static final double LIMIT_USD = 0.50;
    static final BeanOutputConverter<ModerationResult> SINGLE_CONVERTER =
            new BeanOutputConverter<>(ModerationResult.class, Issue55MicroBatchEvaluation.JSON);
    private static final Schema SINGLE_SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(Issue55MicroBatchEvaluation.JSON.readTree(SINGLE_CONVERTER.getJsonSchema()));

    record Forecast(int batchSize, int logicalCalls, long estimatedInputTokens, long outputCapSum,
                    double noCacheEstimateAtCapUsd, double allFourAttemptsEstimateUsd) { }
    record Quality(int count, int unresolved, int resultMatches, int categoryMatches, int riskMatches,
                   int actionabilityMatches, ConfusionCounts confusion, PrecisionRecallF1 flagged,
                   Map<ModerationCategory, PrecisionRecallF1> categories,
                   List<String> fpIds, List<String> fnIds, List<String> mismatchIds) { }
    record Latency(double avgMs, double p95Ms, double p99Ms, double maxMs) { }
    record Metrics(Quality all80, Quality llm79, int logicalCalls, int httpAttempts,
                   long inputTokens, long outputTokens, long cachedInputTokens, long totalTokens,
                   int usageUnknownCalls, int cacheUnknownCalls, double noCacheEstimatedUsd,
                   double cacheAwareEstimatedUsd, Latency providerLogicalLatency,
                   double datasetWallMs, Map<Failure, Long> failedBatches) { }
    record Change(String caseId, ModerationResult expected, ModerationResult single, ModerationResult batch,
                  boolean resultChanged, boolean categoryChanged, boolean riskChanged,
                  boolean newFp, boolean newFn, boolean resultRegression, boolean categoryRegression,
                  boolean riskRegression, boolean actionabilityRegression,
                  boolean resultImprovement, boolean categoryImprovement, boolean riskImprovement) { }

    static List<Forecast> forecast() {
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        var targets = Issue55MicroBatchEvaluation.plan(2).batches().stream().flatMap(b -> b.targets().stream()).toList();
        List<Forecast> forecasts = new ArrayList<>();
        long singleInput = targets.stream().mapToLong(t -> encoding.countTokens(ModerationPrompt.SYSTEM_PROMPT)
                + encoding.countTokens(t.testCase().message())
                + encoding.countTokens(SINGLE_CONVERTER.getJsonSchema()) + 128).sum();
        forecasts.add(forecast(1, 79, singleInput, 79L * 128));
        for (int size : List.of(2, 5, 10)) {
            var batches = Issue55MicroBatchEvaluation.plan(size).batches();
            long input = batches.stream().mapToLong(b -> encoding.countTokens(Issue55MicroBatchEvaluation.SYSTEM_PROMPT)
                    + encoding.countTokens(Issue55MicroBatchEvaluation.JSON.writeValueAsString(b.input()))
                    + encoding.countTokens(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()) + 128).sum();
            forecasts.add(forecast(size, batches.size(), input, 79L * 128));
        }
        return List.copyOf(forecasts);
    }

    private static Forecast forecast(int size, int calls, long input, long outputCap) {
        double cost = cost(input, outputCap, 0);
        return new Forecast(size, calls, input, outputCap, cost, cost * 4);
    }

    static double cost(long input, long output, long cached) {
        return ((input - cached) * 0.15 + cached * 0.075 + output * 0.60) / 1_000_000.0;
    }

    static BatchObservation single(ChatClient client, Target target, int index) {
        String id = "s%03d".formatted(index);
        var testCase = target.testCase();
        ModerationResult expected = Issue55MicroBatchEvaluation.expected(testCase);
        long started = System.nanoTime();
        ChatResponse response;
        try {
            // Production과 같은 System/User/ModerationResult Schema/cap을 사용한다.
            // raw response를 먼저 받아 Parse 실패에도 Usage를 잃지 않게 한다.
            response = client.prompt().system(ModerationPrompt.SYSTEM_PROMPT).user(testCase.message())
                    .options(OpenAiChatOptions.builder().model(Issue55MicroBatchEvaluation.MODEL).maxTokens(128)
                            .outputSchema(SINGLE_CONVERTER.getJsonSchema())).call().chatResponse();
        } catch (OpenAIException failure) {
            return singleResult(id, expected, testCase.id(), null, null, null,
                    System.nanoTime() - started, started, Failure.PROVIDER);
        }
        long callNanos = System.nanoTime() - started;
        BatchUsage usage = response == null ? null : BatchUsage.from(response.getMetadata().getUsage());
        String model = response == null ? null : response.getMetadata().getModel();
        Failure failure = null;
        ModerationResult actual = null;
        if (response == null || response.getResults().size() != 1) {
            failure = Failure.INCOMPLETE;
        } else {
            String finish = response.getResult().getMetadata().getFinishReason();
            String text = response.getResult().getOutput().getText();
            Object refusal = response.getResult().getOutput().getMetadata().get("refusal");
            if ("length".equalsIgnoreCase(finish)) {
                failure = Failure.TRUNCATED;
            } else if (!"stop".equalsIgnoreCase(finish) || text == null || text.isBlank()
                    || (refusal instanceof String value && !value.isBlank())) {
                failure = Failure.INCOMPLETE;
            } else {
                try {
                    var tree = Issue55MicroBatchEvaluation.JSON.readTree(text);
                    if (!SINGLE_SCHEMA.validate(tree).isEmpty()) {
                        failure = Failure.VALIDATION;
                    } else {
                        actual = Issue55MicroBatchEvaluation.JSON.treeToValue(tree, ModerationResult.class);
                        ModerationResultValidationSupport.validate(actual);
                    }
                } catch (JacksonException invalid) {
                    failure = Failure.PARSE;
                } catch (ModerationAnalysisException invalid) {
                    failure = Failure.VALIDATION;
                }
            }
        }
        return singleResult(id, expected, testCase.id(), failure == null ? actual : null, usage, model,
                callNanos, started, failure);
    }

    private static BatchObservation singleResult(String id, ModerationResult expected, String caseId,
                                                  ModerationResult actual, BatchUsage usage, String model,
                                                  long callNanos, long started, Failure failure) {
        return new BatchObservation(id, 1, 128, usage, model, callNanos, System.nanoTime() - started,
                failure, new MappingIssues(Set.of(), Set.of(), Set.of(), false),
                List.of(new CaseObservation(caseId, id, expected, actual, failure)));
    }

    static Quality quality(List<CaseObservation> cases) {
        ConfusionAccumulator flagged = new ConfusionAccumulator();
        Map<ModerationCategory, ConfusionAccumulator> categoryCounts = new EnumMap<>(ModerationCategory.class);
        for (var category : ModerationCategory.values()) {
            categoryCounts.put(category, new ConfusionAccumulator());
        }
        for (var c : cases) {
            if (c.actual() != null) {
                flagged.add(c.expected().result() == ModerationResultType.FLAGGED,
                        c.actual().result() == ModerationResultType.FLAGGED);
                categoryCounts.forEach((category, accumulator) -> accumulator.add(
                        c.expected().categories().contains(category), c.actual().categories().contains(category)));
            }
        }
        Map<ModerationCategory, PrecisionRecallF1> categories = new EnumMap<>(ModerationCategory.class);
        categoryCounts.forEach((category, accumulator) ->
                categories.put(category, ModerationEvaluationMetrics.precisionRecallF1(accumulator.counts())));
        return new Quality(cases.size(), count(cases, c -> c.actual() == null),
                count(cases, CaseObservation::resultMatches), count(cases, CaseObservation::categoryMatches),
                count(cases, c -> c.actual() != null && c.actual().riskLevel() == c.expected().riskLevel()),
                count(cases, CaseObservation::actionabilityMatches), flagged.counts(),
                ModerationEvaluationMetrics.precisionRecallF1(flagged.counts()), Map.copyOf(categories),
                ids(cases, c -> c.actual() != null && c.expected().result() == ModerationResultType.SAFE
                        && c.actual().result() == ModerationResultType.FLAGGED),
                ids(cases, c -> c.actual() != null && c.expected().result() == ModerationResultType.FLAGGED
                        && c.actual().result() == ModerationResultType.SAFE),
                ids(cases, c -> !c.resultMatches() || !c.categoryMatches()
                        || (c.actual() != null && c.actual().riskLevel() != c.expected().riskLevel())));
    }

    static Metrics metrics(List<BatchObservation> calls, long wallNanos, int attempts) {
        var llmCases = calls.stream().flatMap(c -> c.cases().stream()).toList();
        Set<String> expectedIds = new HashSet<>(Issue55MicroBatchEvaluation.plan(2).batches().stream()
                .flatMap(b -> b.targets().stream()).map(t -> t.testCase().id()).toList());
        if (llmCases.size() != 79 || !new HashSet<>(llmCases.stream().map(CaseObservation::caseId).toList()).equals(expectedIds)) {
            throw new IllegalArgumentException("79개 LLM 입력의 일대일 결과가 필요하다.");
        }
        List<CaseObservation> all = new ArrayList<>(llmCases);
        all.addAll(Issue55MicroBatchEvaluation.plan(2).ruleCases());
        var usages = calls.stream().map(BatchObservation::usage).filter(u -> u != null).toList();
        long input = usages.stream().mapToLong(BatchUsage::prompt).sum();
        long output = usages.stream().mapToLong(BatchUsage::completion).sum();
        long cached = usages.stream().mapToLong(u -> u.cachedInput() == null ? 0 : u.cachedInput()).sum();
        Map<Failure, Long> failures = new EnumMap<>(Failure.class);
        for (var failure : Failure.values()) {
            failures.put(failure, calls.stream().filter(c -> c.failure() == failure).count());
        }
        return new Metrics(quality(all), quality(llmCases), calls.size(), attempts, input, output, cached,
                usages.stream().mapToLong(BatchUsage::total).sum(), calls.size() - usages.size(),
                count(calls, c -> c.usage() != null && c.usage().cachedInput() == null), cost(input, output, 0),
                cost(input, output, cached), latency(calls.stream().map(BatchObservation::callNanos).toList()),
                wallNanos / 1_000_000.0, Map.copyOf(failures));
    }

    static List<Change> changes(List<CaseObservation> single, List<CaseObservation> batch) {
        Map<String, CaseObservation> before = new java.util.LinkedHashMap<>();
        single.forEach(c -> {
            if (before.putIfAbsent(c.caseId(), c) != null) {
                throw new IllegalArgumentException("단건 결과 ID 중복");
            }
        });
        if (batch.size() != before.size() || batch.stream().map(CaseObservation::caseId).distinct().count() != batch.size()
                || !before.keySet().equals(new HashSet<>(batch.stream().map(CaseObservation::caseId).toList()))) {
            throw new IllegalArgumentException("동일 입력 Case의 비교가 필요하다.");
        }
        List<Change> changes = new ArrayList<>();
        for (var c : batch) {
            var s = before.get(c.caseId());
            var a = s.actual();
            var b = c.actual();
            if (a == null || b == null || a.equals(b)) {
                continue; // UNRESOLVED는 quality에서 별도 집계한다.
            }
            boolean newFp = c.expected().result() == ModerationResultType.SAFE
                    && a.result() == ModerationResultType.SAFE && b.result() == ModerationResultType.FLAGGED;
            boolean newFn = c.expected().result() == ModerationResultType.FLAGGED
                    && a.result() == ModerationResultType.FLAGGED && b.result() == ModerationResultType.SAFE;
            changes.add(new Change(c.caseId(), c.expected(), a, b,
                    a.result() != b.result(), !a.categories().equals(b.categories()), a.riskLevel() != b.riskLevel(),
                    newFp, newFn, s.resultMatches() && !c.resultMatches(),
                    s.categoryMatches() && !c.categoryMatches(),
                    a.riskLevel() == c.expected().riskLevel() && b.riskLevel() != c.expected().riskLevel(),
                    s.actionabilityMatches() && !c.actionabilityMatches(),
                    !s.resultMatches() && c.resultMatches(), !s.categoryMatches() && c.categoryMatches(),
                    a.riskLevel() != c.expected().riskLevel() && b.riskLevel() == c.expected().riskLevel()));
        }
        return List.copyOf(changes);
    }

    private static Latency latency(List<Long> nanos) {
        var sorted = nanos.stream().sorted().toList();
        return new Latency(sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0,
                sorted.get((int) Math.ceil(sorted.size() * .95) - 1) / 1_000_000.0,
                sorted.get((int) Math.ceil(sorted.size() * .99) - 1) / 1_000_000.0,
                sorted.get(sorted.size() - 1) / 1_000_000.0);
    }

    private static <T> int count(List<T> values, Predicate<T> predicate) {
        return (int) values.stream().filter(predicate).count();
    }

    private static List<String> ids(List<CaseObservation> cases, Predicate<CaseObservation> predicate) {
        return cases.stream().filter(predicate).map(CaseObservation::caseId).toList();
    }
}
