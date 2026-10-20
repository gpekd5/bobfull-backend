package com.bobfull.chat.infrastructure.ai;

import static com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.*;
import com.bobfull.chat.application.exception.ModerationAnalysisException;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ModerationResultValidationSupport;
import com.bobfull.chat.infrastructure.ai.SpringAiModerationHeldoutEvaluationTest.HeldoutCase;
import com.openai.errors.OpenAIException;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.core.JacksonException;

// #55 Q1 진단만 수행한다. 기존 실험 파일의 bytes와 Production을 변경하지 않는다.
final class Issue55CommonPromptEvaluation {
    enum Condition {
        A(false, false), B(false, true), C(true, false), D(true, true);
        final boolean batch;
        final boolean q1;
        Condition(boolean batch, boolean q1) { this.batch = batch; this.q1 = q1; }
    }
    record Addition(String anchor, String sentence) { }
    static final List<Addition> ADDITIONS = List.of(
            new Addition("LOW: 사용하지 않는다. 경미한 농담, 친근한 호칭, 문맥 의존 표현은 SAFE다.",
                    "경미한 표현과 직접 모욕은 표면 단어가 아니라 문장 전체의 의미로 구분한다."),
            new Addition("HIGH: 매우 강한 욕설, 심각한 모욕, 협박, 위협 또는 위해를 가하겠다는 표현이다.",
                    "협박·위협에 해당하면 욕설 단어 유무와 무관하게 HIGH다."),
            new Addition("제외: 공개 사업장 연락처와 일반 숫자.",
                    "연락처 형식뿐 아니라 메시지에 나타난 개인·공개 사업장 용도와 숫자의 역할을 구분한다."),
            new Addition("제외: 정상 추천·후기·메뉴·예약·지도 링크이며 링크 자체만으로 SPAM 처리하지 않는다.",
                    "상업적 유인은 내용에 명시되어야 하며, 정상 안내만으로 추정하지 않는다."));
    static final String Q1 = q1Prompt();
    private static final org.springframework.ai.converter.BeanOutputConverter<ModerationResult> SINGLE_CONVERTER =
            Issue55MicroBatchComparison.SINGLE_CONVERTER;
    private static final Schema OUTPUT_SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(JSON.readTree(CONVERTER.getJsonSchema()));
    private static final Schema SINGLE_SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(JSON.readTree(Issue55MicroBatchComparison.SINGLE_CONVERTER.getJsonSchema()));
    record Step(int repeat, int groupIndex, Condition condition, int targetIndex) { }
    record Call(Step step, String wireBodySha256, String startedAt, long processingNanos,
                int httpAttempts, BatchObservation response) { }
    record Forecast(Condition condition, int logicalCalls, long estimatedInputTokens, long outputCapSum,
                    double noCacheAtCapUsd, double fourAttemptsUsd) { }

    private static String q1Prompt() {
        String prompt = ModerationPrompt.SYSTEM_PROMPT;
        for (var addition : ADDITIONS) {
            if (prompt.indexOf(addition.anchor()) < 0 || prompt.indexOf(addition.anchor()) != prompt.lastIndexOf(addition.anchor())) {
                throw new IllegalStateException("Q1_ANCHOR_CHANGED");
            }
            prompt = prompt.replace(addition.anchor(), addition.anchor() + "\n" + addition.sentence());
        }
        return prompt;
    }

    static String system(Condition condition) {
        return (condition.q1 ? Q1 : ModerationPrompt.SYSTEM_PROMPT) + (condition.batch ? BATCH_INSTRUCTIONS : "");
    }

    static List<Step> schedule() {
        var result = new ArrayList<Step>();
        for (int repeat = 1; repeat <= 3; repeat++) {
            for (int group = 0; group < 2; group++) {
                int rotation = ((repeat - 1) * 2 + group) % 4;
                for (int i = 0; i < 4; i++) {
                    var condition = Condition.values()[(rotation + i) % 4];
                    if (condition.batch) {
                        result.add(new Step(repeat, group, condition, -1));
                    } else {
                        for (int target = group * 10; target < group * 10 + 10; target++) {
                            result.add(new Step(repeat, group, condition, target));
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    static BatchObservation evaluate(ChatClient client, Step step) {
        return step.condition().batch
                ? batchCall(client, Issue55MicroBatchF0.batches(10).get(step.groupIndex()), step.condition())
                : nativeCall(client, Issue55MicroBatchF0.targets().get(step.targetIndex()), step.targetIndex() + 1, step.condition());
    }

    static int cap(Step step) { return step.condition().batch ? 1280 : 128; }
    static String cell(Step step) {
        return step.condition() + ":" + (step.condition().batch ? step.groupIndex() : step.targetIndex());
    }

    static List<Forecast> forecast() {
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        var forecasts = new ArrayList<Forecast>();
        for (var condition : Condition.values()) {
            var steps = schedule().stream().filter(s -> s.condition() == condition).toList();
            long input = steps.stream().mapToLong(step -> encoding.countTokens(system(condition))
                    + encoding.countTokens(user(step)) + encoding.countTokens(schema(condition)) + 256).sum();
            long cap = steps.stream().mapToLong(Issue55CommonPromptEvaluation::cap).sum();
            double cost = Issue55MicroBatchComparison.cost(input, cap, 0);
            forecasts.add(new Forecast(condition, steps.size(), input, cap, cost, cost * 4));
        }
        return List.copyOf(forecasts);
    }

    static String user(Step step) {
        return step.condition().batch ? JSON.writeValueAsString(Issue55MicroBatchF0.batches(10).get(step.groupIndex()).input())
                : Issue55MicroBatchF0.targets().get(step.targetIndex()).testCase().message();
    }
    static String schema(Condition condition) {
        return condition.batch ? CONVERTER.getJsonSchema() : Issue55MicroBatchComparison.SINGLE_CONVERTER.getJsonSchema();
    }

    static String verifyWire(Step step, String body) {
        var tree = JSON.readTree(body);
        var messages = tree.path("messages");
        boolean valid = tree.path("model").asText().equals(MODEL) && tree.path("max_tokens").asInt() == cap(step)
                && messages.size() == 2 && messages.path(0).path("role").asText().equals("system")
                && messages.path(0).path("content").asText().equals(system(step.condition()))
                && messages.path(1).path("role").asText().equals("user")
                && messages.path(1).path("content").asText().equals(user(step))
                && tree.path("response_format").path("type").asText().equals("json_schema")
                && tree.path("response_format").path("json_schema").path("strict").asBoolean()
                && tree.path("response_format").path("json_schema").path("schema").equals(JSON.readTree(schema(step.condition())));
        for (String option : List.of("temperature", "top_p", "seed", "frequency_penalty", "presence_penalty", "n")) {
            valid &= tree.path(option).isMissingNode() || tree.path(option).isNull();
        }
        if (!valid) throw new IllegalStateException("Q1_REQUEST_CONTRACT_CHANGED");
        return Issue55MicroBatchF0.sha256(body);
    }

    // 원래 평가기의 strict 파서/Validator를 복사하여 기존 실험의 소스 해시를 보존한다.
    static BatchObservation nativeCall(ChatClient client, Target target, int index, Condition condition) {
        String id = "s%03d".formatted(index);
        var testCase = target.testCase();
        ModerationResult expected = Issue55MicroBatchEvaluation.expected(testCase);
        long started = System.nanoTime();
        ChatResponse response;
        try {
            // Production과 같은 System/User/ModerationResult Schema/cap을 사용한다.
            // raw response를 먼저 받아 Parse 실패에도 Usage를 잃지 않게 한다.
            response = client.prompt().system(system(condition)).user(testCase.message())
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

    static BatchObservation batchCall(ChatClient client, Batch batch, Condition condition) {
        long start = System.nanoTime();
        // Schema는 native option으로 보낸다. 응답 변환 전에 Usage와 finish reason을 보존한다.
        ChatResponse response;
        try {
            response = client.prompt().system(system(condition)).user(JSON.writeValueAsString(batch.input()))
                    .options(OpenAiChatOptions.builder().model(MODEL).maxTokens(batch.outputCap())
                            .outputSchema(CONVERTER.getJsonSchema()))
                    .call().chatResponse();
        } catch (OpenAIException exception) {
            return failed(batch, null, null, System.nanoTime() - start, start, Failure.PROVIDER, null);
        }
        long callNanos = System.nanoTime() - start;
        BatchUsage usage = response == null ? null : BatchUsage.from(response.getMetadata().getUsage());
        String model = response == null ? null : response.getMetadata().getModel();
        if (response == null || response.getResults().size() != 1) {
            return failed(batch, usage, model, callNanos, start, Failure.INCOMPLETE, null);
        }
        String finish = response.getResult().getMetadata().getFinishReason();
        if ("length".equalsIgnoreCase(finish)) {
            return failed(batch, usage, model, callNanos, start, Failure.TRUNCATED, null);
        }
        String content = response.getResult().getOutput().getText();
        Object refusal = response.getResult().getOutput().getMetadata().get("refusal");
        if (!"stop".equalsIgnoreCase(finish) || content == null || content.isBlank()
                || (refusal instanceof String text && !text.isBlank())) {
            return failed(batch, usage, model, callNanos, start, Failure.INCOMPLETE, null);
        }
        BatchOutput output;
        try {
            // JSONのみを許容し、markdownの補正などで不正なStructured Outputを成功扱いしない。
            var tree = JSON.readTree(content);
            if (!OUTPUT_SCHEMA.validate(tree).isEmpty()) {
                return failed(batch, usage, model, callNanos, start, Failure.VALIDATION, null);
            }
            output = JSON.treeToValue(tree, BatchOutput.class);
        } catch (JacksonException exception) {
            return failed(batch, usage, model, callNanos, start, Failure.PARSE, null);
        }
        if (output == null || output.results() == null
                || output.results().stream().anyMatch(r -> r == null || r.id() == null)) {
            return failed(batch, usage, model, callNanos, start, Failure.VALIDATION, null);
        }
        MappingIssues issues = mapping(batch, output);
        if (issues.invalid()) {
            return failed(batch, usage, model, callNanos, start, Failure.MAPPING, issues);
        }
        Map<String, ModerationResult> byId = new LinkedHashMap<>();
        output.results().forEach(r -> byId.put(r.id(), r.moderation()));
        List<CaseObservation> cases = new ArrayList<>();
        Failure failure = null;
        for (Target target : batch.targets()) {
            ModerationResult actual = byId.get(target.neutralId());
            try {
                ModerationResultValidationSupport.validate(actual);
                cases.add(observation(target.testCase(), batch.id(), actual, null));
            } catch (ModerationAnalysisException exception) {
                failure = Failure.VALIDATION;
                cases.add(observation(target.testCase(), batch.id(), null, failure));
            }
        }
        return new BatchObservation(batch.id(), batch.targets().size(), batch.outputCap(), usage, model,
                callNanos, System.nanoTime() - start, failure, issues, List.copyOf(cases));
    }

    private static MappingIssues mapping(Batch batch, BatchOutput output) {
        Set<String> expected = new HashSet<>();
        batch.targets().forEach(t -> expected.add(t.neutralId()));
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        Set<String> unexpected = new HashSet<>();
        for (OutputItem result : output.results()) {
            if (!seen.add(result.id())) {
                duplicates.add(result.id());
            }
            if (!expected.contains(result.id())) {
                unexpected.add(result.id());
            }
        }
        expected.removeAll(seen);
        return new MappingIssues(Set.copyOf(expected), Set.copyOf(duplicates), Set.copyOf(unexpected),
                output.results().size() != batch.targets().size());
    }

    private static BatchObservation failed(Batch batch, BatchUsage usage, String model, long callNanos,
                                            long start, Failure failure, MappingIssues issues) {
        return new BatchObservation(batch.id(), batch.targets().size(), batch.outputCap(), usage, model,
                callNanos, System.nanoTime() - start, failure, issues,
                batch.targets().stream().map(t -> observation(t.testCase(), batch.id(), null, failure)).toList());
    }

    private static CaseObservation observation(HeldoutCase testCase, String batchId,
                                                ModerationResult actual, Failure failure) {
        return new CaseObservation(testCase.id(), batchId, expected(testCase), actual, failure);
    }

}
