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

// 승인된 지시문만 비교하는 독립 실험. 이전 실험 파일/Production 코드는 변경하지 않는다.
final class Issue55MicroBatchChecklist {
    enum Arm { CONTROL, CANDIDATE }
    static final String CANDIDATE_NAME = "Item-wise Policy Checklist v1";
    static final String CHECKLIST = """

            [Item-wise policy checklist]
            각 id의 moderation을 결정할 때 다음을 확인한다.

            1. 각 category의 포함 조건과 제외 조건을 함께 확인한다.
               제외 조건에 해당하는 내용만을 근거로 그 category를 부여하지 않는다.
               한 category의 제외 조건을 다른 category의 판단에 대신 적용하지 않는다.

            2. 어떤 category에도 명확히 해당하지 않으면 기존의 "애매하면 SAFE"와 SAFE 출력 규칙을 적용한다.
               명확히 해당하는 category가 있으면 그 category에 근거해 FLAGGED 결과를 구성한다.

            3. FLAGGED의 riskLevel은 해당 항목에 적용되는 기존 Policy의 위험도 기준으로 정한다.
               다른 항목보다 상대적으로 약하다는 이유나 Batch 안의 SAFE/FLAGGED 개수에 맞추어 판정 또는 위험도를 조정하지 않는다.

            4. 반환 전에 각 id의 result, categories, riskLevel 조합을 기존 Output rules와 대조한다.
               PROFANITY에는 LOW를 사용하지 않는다.

            설명이나 중간 판단을 출력하지 말고 기존 results JSON만 반환한다.
            """;
    private static final Schema OUTPUT_SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(JSON.readTree(CONVERTER.getJsonSchema()));
    record Step(int repeat, int batchIndex, Arm arm) { }
    record Call(Step step, String wireBodySha256, String startedAt, long processingNanos,
                int httpAttempts, BatchObservation response) { }
    static String system(Arm arm) {
        return SYSTEM_PROMPT + (arm == Arm.CANDIDATE ? CHECKLIST : "");
    }
    static List<Step> schedule() {
        var steps = new ArrayList<Step>();
        for (int repeat = 1; repeat <= 3; repeat++) {
            for (int batch = 0; batch < 2; batch++) {
                var order = (repeat + batch) % 2 == 1
                        ? List.of(Arm.CONTROL, Arm.CANDIDATE) : List.of(Arm.CANDIDATE, Arm.CONTROL);
                for (Arm arm : order) steps.add(new Step(repeat, batch, arm));
            }
        }
        return List.copyOf(steps);
    }
    static List<Issue55MicroBatchComparison.Forecast> forecast() {
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        var result = new ArrayList<Issue55MicroBatchComparison.Forecast>();
        for (var arm : Arm.values()) {
            long input = 3 * Issue55MicroBatchF0.batches(10).stream().mapToLong(b ->
                    encoding.countTokens(system(arm)) + encoding.countTokens(JSON.writeValueAsString(b.input()))
                    + encoding.countTokens(CONVERTER.getJsonSchema()) + 256).sum();
            long cap = 6L * 1280;
            double cost = Issue55MicroBatchComparison.cost(input, cap, 0);
            result.add(new Issue55MicroBatchComparison.Forecast(10, 6, input, cap, cost, 4 * cost));
        }
        return List.copyOf(result);
    }
    static String verifyWire(Step step, String body) {
        var tree = JSON.readTree(body);
        var batch = Issue55MicroBatchF0.batches(10).get(step.batchIndex());
        var messages = tree.path("messages");
        boolean valid = tree.path("model").asText().equals(MODEL)
                && tree.path("max_tokens").asInt() == 1280 && messages.size() == 2
                && messages.path(0).path("role").asText().equals("system")
                && messages.path(0).path("content").asText().equals(system(step.arm()))
                && messages.path(1).path("role").asText().equals("user")
                && JSON.readTree(messages.path(1).path("content").asText()).equals(JSON.valueToTree(batch.input()))
                && tree.path("response_format").path("type").asText().equals("json_schema")
                && tree.path("response_format").path("json_schema").path("strict").asBoolean()
                && tree.path("response_format").path("json_schema").path("schema")
                        .equals(JSON.readTree(CONVERTER.getJsonSchema()));
        for (String option : List.of("temperature", "top_p", "seed", "frequency_penalty", "presence_penalty", "n")) {
            valid &= tree.path(option).isMissingNode() || tree.path(option).isNull();
        }
        if (!valid) throw new IllegalStateException("CHECKLIST_REQUEST_CONTRACT_CHANGED");
        return Issue55MicroBatchF0.sha256(body);
    }

    // 기존 실험의 strict 파서/ID 매핑/Validator를 그대로 유지한 복사본.
    // 이전 Evidence의 소스 해시를 보존하기 위해 기존 evaluate 메서드는 수정하지 않는다.
    static BatchObservation evaluate(ChatClient client, Batch batch, Arm arm) {
        long start = System.nanoTime();
        // Schema는 native option으로 보낸다. 응답 변환 전에 Usage와 finish reason을 보존한다.
        ChatResponse response;
        try {
            response = client.prompt().system(system(arm)).user(JSON.writeValueAsString(batch.input()))
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
