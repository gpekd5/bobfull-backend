package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Batch;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Target;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

// #55 진단 전용. Production 호출 경로와 기존 실험/Evidence는 변경하지 않는다.
final class Issue55MicroBatchCrossDiagnosis {
    static final List<String> FOCUS = List.of("HOLDOUT-PROFANITY-16", "HOLDOUT-PROFANITY-15", "HOLDOUT-SAFE-17");
    static final double PRIOR_ESTIMATED_USD = .11129570;
    enum Composition { C0, C1 }
    enum Order { O0, O1, O2 }
    record Cell(Composition composition, String focusId, Order order) { }
    record Step(int repeat, Cell cell) { }
    record Input(String caseId, String neutralId, String messageSha256, int position) { }
    record Request(Cell cell, String requestSpecSha256, List<Input> inputs, int focusPosition, int outputCap) { }
    record Call(Step step, String requestSpecSha256, String wireBodySha256, String startedAt,
                long processingNanos, int httpAttempts, int focusOutputPosition, BatchObservation response) { }

    static Batch batch(Cell cell) {
        if (!FOCUS.contains(cell.focusId())) {
            throw new IllegalArgumentException("Unknown focal case");
        }
        var originals = Issue55MicroBatchF0.batches(5).stream()
                .filter(b -> b.targets().stream().anyMatch(t -> t.testCase().id().equals(cell.focusId())))
                .findFirst().orElseThrow();
        var targets = new ArrayList<>(originals.targets());
        int focal = position(targets, cell.focusId());
        if (cell.composition() == Composition.C1) {
            String removed = switch (cell.focusId()) {
                case "HOLDOUT-PROFANITY-16" -> "HOLDOUT-PROFANITY-01";
                case "HOLDOUT-PROFANITY-15" -> "HOLDOUT-PROFANITY-17";
                default -> "HOLDOUT-PROFANITY-14";
            };
            String added = cell.focusId().equals("HOLDOUT-SAFE-17") ? "HOLDOUT-PROFANITY-13" : "HOLDOUT-PROFANITY-14";
            Target replacement = Issue55MicroBatchF0.targets().stream()
                    .filter(t -> t.testCase().id().equals(added)).findFirst().orElseThrow();
            targets.set(position(targets, removed), replacement);
        }
        if (cell.order() == Order.O1) {
            Target target = targets.remove(focal);
            Collections.reverse(targets);
            targets.add(focal, target);
        } else if (cell.order() == Order.O2) {
            Target target = targets.remove(focal);
            targets.add(focal == 0 ? targets.size() : 0, target);
        }
        return new Batch("cross-" + cell.composition() + "-" + cell.focusId() + "-" + cell.order(), List.copyOf(targets));
    }

    // Human 요청대로 C0를 먼저 소진하고 gate 후 C1 실행. 구성 간 시간 block 차이는 남는다.
    static List<Step> schedule(Composition composition) {
        var result = new ArrayList<Step>();
        for (int repeat = 1; repeat <= 3; repeat++) {
            var focuses = new ArrayList<>(FOCUS);
            Collections.shuffle(focuses, new Random(55L + repeat - 1));
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < focuses.size(); i++) {
                    Order order = Order.values()[(round + i + repeat - 1) % 3];
                    result.add(new Step(repeat, new Cell(composition, focuses.get(i), order)));
                }
            }
        }
        return List.copyOf(result);
    }

    static List<Request> requests(Composition composition) {
        return schedule(composition).stream().map(Step::cell).distinct().map(cell -> {
            var b = batch(cell);
            var inputs = new ArrayList<Input>();
            for (int i = 0; i < b.targets().size(); i++) {
                var t = b.targets().get(i);
                inputs.add(new Input(t.testCase().id(), t.neutralId(),
                        Issue55MicroBatchF0.sha256(t.testCase().message()), i + 1));
            }
            return new Request(cell, Issue55MicroBatchSizeComparison.requestHash(b), List.copyOf(inputs),
                    position(b.targets(), cell.focusId()) + 1, b.outputCap());
        }).toList();
    }

    static Issue55MicroBatchComparison.Forecast forecast(Composition composition) {
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        long input = schedule(composition).stream().map(s -> batch(s.cell())).mapToLong(b ->
                encoding.countTokens(Issue55MicroBatchEvaluation.SYSTEM_PROMPT)
                        + encoding.countTokens(Issue55MicroBatchEvaluation.JSON.writeValueAsString(b.input()))
                        + encoding.countTokens(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()) + 256).sum();
        long cap = 27L * 640;
        double cost = Issue55MicroBatchComparison.cost(input, cap, 0);
        return new Issue55MicroBatchComparison.Forecast(5, 27, input, cap, cost, 4 * cost);
    }

    static int position(List<Target> targets, String caseId) {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).testCase().id().equals(caseId)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Case missing from batch");
    }

    static String verifyWireBody(Cell cell, String body) {
        var tree = Issue55MicroBatchEvaluation.JSON.readTree(body);
        var batch = batch(cell);
        var messages = tree.path("messages");
        boolean valid = tree.path("model").asText().equals(Issue55MicroBatchEvaluation.MODEL)
                && tree.path("max_tokens").asInt() == 640 && messages.size() == 2
                && messages.get(0).path("role").asText().equals("system")
                && messages.get(0).path("content").asText().equals(Issue55MicroBatchEvaluation.SYSTEM_PROMPT)
                && messages.get(1).path("role").asText().equals("user")
                && Issue55MicroBatchEvaluation.JSON.readTree(messages.get(1).path("content").asText())
                        .equals(Issue55MicroBatchEvaluation.JSON.valueToTree(batch.input()))
                && tree.path("response_format").path("type").asText().equals("json_schema")
                && tree.path("response_format").path("json_schema").path("strict").asBoolean()
                && tree.path("response_format").path("json_schema").path("schema")
                        .equals(Issue55MicroBatchEvaluation.JSON.readTree(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()));
        for (String option : List.of("temperature", "top_p", "seed", "frequency_penalty", "presence_penalty", "n")) {
            valid &= tree.path(option).isMissingNode() || tree.path(option).isNull();
        }
        if (!valid) {
            throw new IllegalStateException("CROSS_REQUEST_CONTRACT_CHANGED");
        }
        return Issue55MicroBatchF0.sha256(body);
    }

    private Issue55MicroBatchCrossDiagnosis() { }
}
