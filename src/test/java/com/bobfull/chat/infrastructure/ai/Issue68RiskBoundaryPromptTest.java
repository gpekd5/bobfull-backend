package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class Issue68RiskBoundaryPromptTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String CONTROL_SHA = "7540e67d11d342e9b9618204baa8d5868ebfd21a15716a8f41dc5fbcb35249a4";
    private static final String CANDIDATE_SHA = "c4a3f361f7325ac811f9e2f0ce35b3cd1934b536910ac89fbd903ca35e1c7960";

    @TempDir
    Path temporary;

    @Test
    void 후보는_위험도_한블록만_추가하고_원문과_Fewshot15를_보존한다() {
        // given
        String candidate = Issue68RiskBoundaryPrompt.CANDIDATE;
        String fewshot = "[Few-shot boundary examples]";
        // when
        String restored = candidate.replace("\n" + Issue68RiskBoundaryPrompt.ADDITION, "");
        // then
        assertThat(restored).isEqualTo(ModerationPrompt.SYSTEM_PROMPT);
        assertThat(Issue68EvaluationContract.sha256(restored)).isEqualTo(CONTROL_SHA);
        assertThat(Issue68EvaluationContract.sha256(candidate)).isEqualTo(CANDIDATE_SHA);
        assertThat(candidate.substring(candidate.indexOf(fewshot)))
                .isEqualTo(restored.substring(restored.indexOf(fewshot)));
        assertThat(candidate.lines().filter(line -> line.contains("→")).count()).isEqualTo(15);
        assertThat(candidate).doesNotContain("HOLDOUT-", "한 번만 더 그러면 가만 안 둔다", "Item-wise policy checklist");
        for (var addition : Issue55CommonPromptEvaluation.ADDITIONS) {
            assertThat(candidate).doesNotContain(addition.sentence());
        }
        assertThat(Issue68RiskBoundaryPrompt.ADDITION).contains("조건문·경고·강한 어조만으로 위협을 추정하지 않는다",
                "기존 HIGH 사유가 없는", "기존대로 SAFE");
    }

    @Test
    void Native와_Batch는_공통후보가_같고_Batch계약과_Schema는_Production과_같다() {
        // given
        String productionBatch = Issue68EvaluationContract.batchSystemPrompt();
        String instructions = productionBatch.substring(ModerationPrompt.SYSTEM_PROMPT.length());
        // when & then
        assertThat(Issue68RiskBoundaryPrompt.system(Issue68RiskBoundaryPrompt.Arm.B))
                .isEqualTo(Issue68RiskBoundaryPrompt.CANDIDATE);
        assertThat(Issue68RiskBoundaryPrompt.system(Issue68RiskBoundaryPrompt.Arm.D))
                .isEqualTo(Issue68RiskBoundaryPrompt.CANDIDATE + instructions);
        assertThat(Issue55MicroBatchEvaluation.SYSTEM_PROMPT).isEqualTo(productionBatch);
        assertThat(JSON.readTree(Issue55MicroBatchComparison.SINGLE_CONVERTER.getJsonSchema()))
                .isEqualTo(JSON.readTree(Issue68EvaluationContract.schema(false)));
        assertThat(JSON.readTree(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()))
                .isEqualTo(JSON.readTree(Issue68EvaluationContract.schema(true)));
        assertThat(Issue68RiskBoundaryPrompt.batches().stream().flatMap(b -> b.targets().stream())
                .map(t -> t.testCase().id()).toList()).isEqualTo(Issue55MicroBatchF0.IDS);
        assertThat(Issue68RiskBoundaryPrompt.batches()).allSatisfy(b ->
                assertThat(b.targets()).extracting(Issue55MicroBatchEvaluation.Target::neutralId)
                        .containsExactly("m001", "m002", "m003", "m004", "m005", "m006", "m007", "m008", "m009", "m010"));
    }

    @ParameterizedTest
    @EnumSource(Issue68RiskBoundaryPrompt.Arm.class)
    void 네조건의_진단20은_Fake에서_ID와_Validator_요청별_Usage를_유지한다(Issue68RiskBoundaryPrompt.Arm arm)
            throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve(arm + ".jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = boundary(endpoint, budget, arm)) {
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), true).run(context -> {
                var client = client(context.getBean(OpenAiChatModel.class), arm);
                var observations = new ArrayList<Issue55MicroBatchEvaluation.BatchObservation>();
                // when
                for (int i = 0; i < (arm.batch ? 2 : 20); i++) {
                    observations.add(evaluate(client, boundary, arm, i));
                }
                // then: Fake GT echo는 모델 품질 PASS가 아니라 요청/변환 계약 검증이다.
                var cases = observations.stream().flatMap(o -> o.cases().stream()).toList();
                assertThat(cases).hasSize(20).allSatisfy(c -> assertThat(c.actual()).isEqualTo(c.expected()));
                assertThat(observations).allSatisfy(o -> assertThat(o.failure()).isNull());
                assertThat(endpoint.received()).isEqualTo(arm.batch ? 2 : 20);
                assertThat(boundary.attempts()).hasSize(endpoint.received()).allSatisfy(a -> {
                    assertThat(a.settlement()).isEqualTo("SETTLED");
                    assertThat(a.usage().input()).isEqualTo(900);
                });
                assertThat(observations.stream().mapToLong(o -> o.usage().prompt()).sum())
                        .isEqualTo(900L * endpoint.received());
                assertThat(cases.get(0).getClass().getRecordComponents())
                        .noneMatch(field -> field.getName().matches(".*(usage|[Tt]okens|[Cc]ost).*"));
                assertThat(budget.chargedNanos()).isEqualTo(endpoint.received()
                        * Issue68Budget.cost(900, arm.batch ? 200 : 20));
                assertThat(boundary.calls()).allSatisfy(c -> assertThat(c.cap).isEqualTo(arm.batch ? 1280 : 128));
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 실제_HTTP_요청은_Control대비_System내용만_달라진다(boolean batch) throws Exception {
        // given
        var control = batch ? Issue68RiskBoundaryPrompt.Arm.C : Issue68RiskBoundaryPrompt.Arm.A;
        var candidate = batch ? Issue68RiskBoundaryPrompt.Arm.D : Issue68RiskBoundaryPrompt.Arm.B;
        // when
        JsonNode original = oneRequest(control);
        JsonNode changed = oneRequest(candidate);
        assertThat(changed.path("messages").get(0).path("content").asText())
                .isEqualTo(Issue68RiskBoundaryPrompt.system(candidate));
        ((tools.jackson.databind.node.ObjectNode) changed.path("messages").get(0))
                .put("content", original.path("messages").get(0).path("content").asText());
        // then: Schema, User, model, cap, strict, 미지정 sampling 등 나머지 필드 전부 동일.
        assertThat(changed).isEqualTo(original);
    }

    @ParameterizedTest
    @EnumSource(value = Issue68FakeOpenAiEndpoint.Mode.class, names = {"PARSE", "VALIDATION", "TRUNCATED", "MAPPING"})
    void 후보_Batch의_계약실패는_UNRESOLVED이며_후속요청을_차단한다(Issue68FakeOpenAiEndpoint.Mode mode)
            throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve(mode + ".jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = boundary(endpoint, budget, Issue68RiskBoundaryPrompt.Arm.D)) {
            endpoint.enqueue(mode);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), true).run(context -> {
                var client = client(context.getBean(OpenAiChatModel.class), Issue68RiskBoundaryPrompt.Arm.D);
                // when
                var result = evaluate(client, boundary, Issue68RiskBoundaryPrompt.Arm.D, 0);
                // then
                assertThat(result.failure()).isNotNull();
                assertThat(result.cases()).anySatisfy(c -> assertThat(c.actual()).isNull());
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false))
                        .hasMessage("ISSUE68_EXECUTION_STOPPED");
                assertThat(endpoint.received()).isEqualTo(1);
            });
        }
    }

    @Test
    void 후보도_429_HTTP별_예약을_거치고_UNKNOWN은_반환하지_않는다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("retry.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = boundary(endpoint, budget, Issue68RiskBoundaryPrompt.Arm.B)) {
            endpoint.enqueue(Issue68FakeOpenAiEndpoint.Mode.RATE_LIMIT, Issue68FakeOpenAiEndpoint.Mode.OK);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                // when
                var result = evaluate(client(context.getBean(OpenAiChatModel.class), Issue68RiskBoundaryPrompt.Arm.B),
                        boundary, Issue68RiskBoundaryPrompt.Arm.B, 0);
                // then
                assertThat(result.cases()).hasSize(1).allSatisfy(c -> assertThat(c.actual()).isNotNull());
            });
            assertThat(boundary.calls()).hasSize(1);
            assertThat(boundary.attempts()).extracting(Issue68HttpBoundary.Attempt::settlement)
                    .containsExactly("UNKNOWN_KEEP", "SETTLED");
            assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(128_000, 128) + Issue68Budget.cost(900, 20));
            assertThat(budget.grossReservedNanos()).isEqualTo(2 * Issue68Budget.cost(128_000, 128));
            assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false))
                    .hasMessage("ISSUE68_EXECUTION_STOPPED");
        }
    }

    @Test
    void 후보도_예산부족이면_HTTP_전송전에_차단한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("limit.jsonl"), 1);
                var boundary = boundary(endpoint, budget, Issue68RiskBoundaryPrompt.Arm.B)) {
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                // when & then
                assertThatThrownBy(() -> evaluate(client(context.getBean(OpenAiChatModel.class),
                        Issue68RiskBoundaryPrompt.Arm.B), boundary, Issue68RiskBoundaryPrompt.Arm.B, 0))
                        .isInstanceOf(RuntimeException.class).hasStackTraceContaining("ISSUE68_BUDGET_LIMIT");
            });
            assertThat(endpoint.received()).isZero();
            assertThat(budget.grossReservedNanos()).isZero();
        }
    }

    @Test
    void 후보_Fake전용경계는_실제_HTTPS_목적지를_허용하지_않는다() throws Exception {
        // given
        try (var budget = new Issue68Budget(temporary.resolve("no-live.jsonl"), Issue68Budget.PLANNED_LIMIT)) {
            // when & then
            assertThatThrownBy(() -> Issue68HttpBoundary.riskBoundaryPreparation(URI.create("https://api.openai.com/v1"), budget))
                    .hasMessage("ISSUE68_LIVE_ENDPOINT_NOT_AUTHORIZED");
            assertThat(budget.grossReservedNanos()).isZero();
        }
    }

    @Test
    void 기존_전송경계는_후보_Prompt를_여전히_거부한다() throws Exception {
        // given
        var body = oneRequest(Issue68RiskBoundaryPrompt.Arm.D);
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("control-only.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            boundary.begin(Issue55MicroBatchF0.IDS.subList(0, 10), true);
            // when & then: 기존 Live/Fake 기본 factory의 승인 조건을 넓히지 않는다.
            assertThatThrownBy(() -> boundary.verifyRequest(request(endpoint, body)))
                    .hasMessage("ISSUE68_PROMPT_OR_SCHEMA_CHANGED");
            assertThat(endpoint.received()).isZero();
            assertThat(budget.grossReservedNanos()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "cap", "sampling", "schema", "id", "system"})
    void 후보_Fake경계도_허용하지않은_요청변경을_거부한다(String mutation) throws Exception {
        // given
        var body = (tools.jackson.databind.node.ObjectNode) oneRequest(Issue68RiskBoundaryPrompt.Arm.D);
        switch (mutation) {
            case "model" -> body.put("model", "unapproved-model");
            case "cap" -> body.put("max_tokens", 128);
            case "sampling" -> body.put("temperature", 0.0);
            case "schema" -> ((tools.jackson.databind.node.ObjectNode) body.path("response_format")
                    .path("json_schema")).set("schema", JSON.createObjectNode());
            case "id" -> {
                var items = JSON.readTree(body.path("messages").get(1).path("content").asText());
                ((tools.jackson.databind.node.ObjectNode) items.path("items").get(0)).put("id", "unapproved-id");
                ((tools.jackson.databind.node.ObjectNode) body.path("messages").get(1))
                        .put("content", JSON.writeValueAsString(items));
            }
            case "system" -> ((tools.jackson.databind.node.ObjectNode) body.path("messages").get(0))
                    .put("content", Issue55CommonPromptEvaluation.Q1);
            default -> throw new IllegalArgumentException("UNSUPPORTED_FIXTURE");
        }
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("mutated.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = boundary(endpoint, budget, Issue68RiskBoundaryPrompt.Arm.D)) {
            boundary.begin(Issue55MicroBatchF0.IDS.subList(0, 10), true);
            // when & then
            assertThatThrownBy(() -> boundary.verifyRequest(request(endpoint, body))).isInstanceOf(IllegalStateException.class);
            assertThat(endpoint.received()).isZero();
            assertThat(budget.grossReservedNanos()).isZero();
        }
    }

    @Test
    void 후보의_Usage가_불명이면_예약을_유지하고_다음요청을_차단한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("unknown.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = boundary(endpoint, budget, Issue68RiskBoundaryPrompt.Arm.B)) {
            endpoint.enqueue(Issue68FakeOpenAiEndpoint.Mode.NO_USAGE);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                // when
                evaluate(client(context.getBean(OpenAiChatModel.class), Issue68RiskBoundaryPrompt.Arm.B),
                        boundary, Issue68RiskBoundaryPrompt.Arm.B, 0);
                // then
                assertThat(boundary.attempts()).hasSize(1);
                assertThat(boundary.attempts().get(0).settlement()).isEqualTo("UNKNOWN_KEEP");
                assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(128_000, 128));
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false))
                        .hasMessage("ISSUE68_EXECUTION_STOPPED");
            });
        }
    }

    @ParameterizedTest
    @EnumSource(value = Issue68FakeOpenAiEndpoint.Mode.class, names = {"PARSE", "VALIDATION", "TRUNCATED"})
    void 후보_Native도_계약실패를_정상판정으로_처리하지_않는다(Issue68FakeOpenAiEndpoint.Mode mode) throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("native-failure.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = boundary(endpoint, budget, Issue68RiskBoundaryPrompt.Arm.B)) {
            endpoint.enqueue(mode);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                // when
                var result = evaluate(client(context.getBean(OpenAiChatModel.class), Issue68RiskBoundaryPrompt.Arm.B),
                        boundary, Issue68RiskBoundaryPrompt.Arm.B, 0);
                // then
                assertThat(result.failure()).isNotNull();
                assertThat(result.cases()).hasSize(1).allSatisfy(c -> assertThat(c.actual()).isNull());
            });
        }
    }

    @Test
    void 동결된_GT와_V1_V2는_FP_FN_Critical하향_검토누락을_계속_탐지한다() {
        // given: DB Commit 여부는 여기서는 enum 평가용 fixture이며 실제 DB 관측을 주장하지 않는다.
        Issue68GateV2.requireFrozen();
        for (var c : Issue68EvaluationContract.heldout()) {
            // when & then
            if (c.expected().result() == ModerationResultType.SAFE) {
                assertThat(Issue68GateV2.evaluate(c, new ModerationResult(ModerationResultType.FLAGGED,
                        Set.of(ModerationCategory.PROFANITY), RiskLevel.HIGH), true).v2Blockers())
                        .contains("FP_HUMAN_REVIEW");
            } else {
                var missing = Issue68GateV2.evaluate(c, new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW), true);
                assertThat(missing.v1Blockers()).contains("FN");
                assertThat(missing.v2Blockers()).contains("REVIEW_TARGET_MISS");
            }
        }
        var critical = Issue68EvaluationContract.heldout().stream().filter(c -> c.id().equals("HOLDOUT-PROFANITY-14"))
                .findFirst().orElseThrow();
        var result = Issue68GateV2.evaluate(critical,
                new ModerationResult(ModerationResultType.FLAGGED, critical.expected().categories(), RiskLevel.MEDIUM), true);
        assertThat(result.v1Blockers()).contains("GT_HIGH_DOWNGRADE");
        assertThat(result.v2Blockers()).contains("CRITICAL_RISK_DOWNGRADE");
        assertThat(result.exactAndReview().actualReviewTarget()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CRITICAL_RISK_DOWNGRADE", "CRITICAL_FN", "FP_HUMAN_REVIEW", "REVIEW_TARGET_MISS"})
    void 품질_Gate_HOLD도_Fake진단의_다음_HTTP를_차단한다(String reason) throws Exception {
        // given: committed=true는 평가 함수 입력 fixture다. Kafka/DB Commit 실측 결과가 아니다.
        int index = reason.equals("FP_HUMAN_REVIEW") ? 0 : 2;
        var c = Issue68EvaluationContract.diagnostic().get(index);
        ModerationResult output = switch (reason) {
            case "CRITICAL_RISK_DOWNGRADE" -> new ModerationResult(ModerationResultType.FLAGGED,
                    c.expected().categories(), RiskLevel.MEDIUM);
            case "CRITICAL_FN" -> new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);
            case "FP_HUMAN_REVIEW" -> new ModerationResult(ModerationResultType.FLAGGED,
                    Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM);
            case "REVIEW_TARGET_MISS" -> new ModerationResult(ModerationResultType.FLAGGED,
                    c.expected().categories(), RiskLevel.LOW);
            default -> throw new IllegalArgumentException("UNSUPPORTED_FIXTURE");
        };
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("quality.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = boundary(endpoint, budget, Issue68RiskBoundaryPrompt.Arm.B)) {
            endpoint.overrideLabel(c.id(), output);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                // when
                var response = evaluate(client(context.getBean(OpenAiChatModel.class), Issue68RiskBoundaryPrompt.Arm.B),
                        boundary, Issue68RiskBoundaryPrompt.Arm.B, index);
                var gate = Issue68GateV2.evaluate(c, response.cases().get(0).actual(), true);
                if (!gate.v2Blockers().isEmpty()) {
                    boundary.stop();
                }
                // then: 200/구조적 유효 응답도 의미상 Gate 실패와 별개다.
                assertThat(gate.v2Blockers()).contains(reason);
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false))
                        .hasMessage("ISSUE68_EXECUTION_STOPPED");
                assertThat(endpoint.received()).isEqualTo(1);
            });
        }
    }

    @Test
    void 동결문자열_해시_Token추정_유료실행이_없는_계획을_기록한다() throws Exception {
        // given
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        var report = new LinkedHashMap<String, Object>();
        report.put("scope", "FAKE_ONLY_NOT_MODEL_QUALITY_OR_PAID_APPROVAL");
        report.put("candidateVersion", Issue68RiskBoundaryPrompt.VERSION);
        report.put("controlVersion", ModerationPrompt.PROMPT_VERSION);
        report.put("policyVersion", ModerationPrompt.POLICY_VERSION);
        report.put("model", Issue68EvaluationContract.MODEL);
        report.put("control", Issue68RiskBoundaryPrompt.CONTROL);
        report.put("candidate", Issue68RiskBoundaryPrompt.CANDIDATE);
        report.put("addition", Issue68RiskBoundaryPrompt.ADDITION);
        report.put("controlSha256", CONTROL_SHA);
        report.put("candidateSha256", CANDIDATE_SHA);
        report.put("controlBatchSystemSha256", Issue68EvaluationContract.sha256(
                Issue68RiskBoundaryPrompt.system(Issue68RiskBoundaryPrompt.Arm.C)));
        report.put("candidateBatchSystemSha256", Issue68EvaluationContract.sha256(
                Issue68RiskBoundaryPrompt.system(Issue68RiskBoundaryPrompt.Arm.D)));
        report.put("controlEstimatedSystemTokens", encoding.countTokens(Issue68RiskBoundaryPrompt.CONTROL));
        report.put("candidateEstimatedSystemTokens", encoding.countTokens(Issue68RiskBoundaryPrompt.CANDIDATE));
        report.put("singleSchemaSha256", Issue68EvaluationContract.sha256(Issue68EvaluationContract.schema(false)));
        report.put("batchSchemaSha256", Issue68EvaluationContract.sha256(Issue68EvaluationContract.schema(true)));
        report.put("groundTruthSha256", Issue68EvaluationContract.groundTruthSha256());
        report.put("metadataSha256", Issue68GateV2.metadataSha256());
        report.put("oneRepeatForecast", Issue68RiskBoundaryPrompt.forecast(1));
        report.put("threeRepeatForecast", Issue68RiskBoundaryPrompt.forecast(3));
        report.put("newPaidRequests", 0);
        // when
        Files.createDirectories(Path.of("build/issue68"));
        Files.writeString(Path.of("build/issue68/risk-boundary-a1-freeze.json"), JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(report));
        // then
        assertThat(Issue68RiskBoundaryPrompt.forecast(1).stream().mapToInt(Issue68RiskBoundaryPrompt.Forecast::logicalRequests).sum())
                .isEqualTo(44);
        assertThat(Issue68RiskBoundaryPrompt.forecast(3).stream().mapToInt(Issue68RiskBoundaryPrompt.Forecast::logicalRequests).sum())
                .isEqualTo(132);
        assertThat(encoding.countTokens(Issue68RiskBoundaryPrompt.CANDIDATE)).isGreaterThan(720);
    }

    private static Issue68HttpBoundary boundary(Issue68FakeOpenAiEndpoint endpoint, Issue68Budget budget,
            Issue68RiskBoundaryPrompt.Arm arm) {
        return arm.candidate ? Issue68HttpBoundary.riskBoundaryPreparation(endpoint.uri(), budget)
                : new Issue68HttpBoundary(endpoint.uri(), budget);
    }

    private static ChatClient client(OpenAiChatModel delegate, Issue68RiskBoundaryPrompt.Arm arm) {
        ChatModel model = new ChatModel() {
            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return delegate.getOptions();
            }

            @Override
            public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                var messages = new ArrayList<>(prompt.getInstructions());
                String expected = arm.batch ? Issue68EvaluationContract.batchSystemPrompt() : ModerationPrompt.SYSTEM_PROMPT;
                if (!messages.get(0).getText().equals(expected)) {
                    throw new IllegalStateException("ISSUE68_RISK_CONTROL_CHANGED");
                }
                messages.set(0, new SystemMessage(Issue68RiskBoundaryPrompt.system(arm)));
                return delegate.call(new Prompt(messages, prompt.getOptions()));
            }
        };
        return ChatClient.builder(model).build();
    }

    private static Issue55MicroBatchEvaluation.BatchObservation evaluate(ChatClient client, Issue68HttpBoundary boundary,
            Issue68RiskBoundaryPrompt.Arm arm, int index) {
        var batch = arm.batch ? Issue68RiskBoundaryPrompt.batches().get(index) : null;
        var target = arm.batch ? null : Issue55MicroBatchF0.targets().get(index);
        var ids = arm.batch ? batch.targets().stream().map(t -> t.testCase().id()).toList() : List.of(target.testCase().id());
        var logical = boundary.begin(ids, arm.batch);
        boolean failed = true;
        try {
            var result = arm.batch ? Issue55MicroBatchEvaluation.evaluate(client, batch)
                    : Issue55MicroBatchComparison.single(client, target, index + 1);
            failed = result.failure() != null || boundary.attempts().stream()
                    .anyMatch(a -> a.logical() == logical.number && !a.settlement().equals("SETTLED"));
            return result;
        } finally {
            boundary.end(logical, failed);
            if (failed) {
                boundary.stop();
            }
        }
    }

    private JsonNode oneRequest(Issue68RiskBoundaryPrompt.Arm arm) throws Exception {
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("wire-" + arm + ".jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = boundary(endpoint, budget, arm)) {
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), true).run(context -> {
                var result = evaluate(client(context.getBean(OpenAiChatModel.class), arm), boundary, arm, 0);
                assertThat(result.failure()).isNull();
            });
            return JSON.readTree(endpoint.requests().get(0));
        }
    }

    private static Request request(Issue68FakeOpenAiEndpoint endpoint, JsonNode body) {
        return new Request.Builder().url(endpoint.uri() + "/chat/completions")
                .post(RequestBody.create(JSON.writeValueAsString(body), MediaType.get("application/json"))).build();
    }
}
