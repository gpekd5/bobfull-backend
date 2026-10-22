package com.bobfull.chat.infrastructure.ai;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.ArrayList;
import java.util.List;

// 위험도 경계 한 블록만 추가하는 무과금 후보. Live 실행 진입점이나 Production Bean은 없다.
final class Issue68RiskBoundaryPrompt {
    static final String VERSION = "issue68-risk-boundary-a1-test-only";
    static final String CONTROL = ModerationPrompt.SYSTEM_PROMPT;
    static final String ANCHOR = "HIGH: 매우 강한 욕설, 심각한 모욕, 협박, 위협 또는 위해를 가하겠다는 표현이다.";
    static final String ADDITION = """
            [PROFANITY 위험도 경계]
            협박·위협은 상대에게 위해를 가하거나 보복하겠다는 의미가 성립하는지로 판단하며, 단순한 불만·모욕·적대감과 구분한다.
            그 의미가 성립하면 욕설 부재, 조건부·간접 표현, 구체적 위해 수단 생략만으로 HIGH를 낮추지 않는다.
            조건문·경고·강한 어조만으로 위협을 추정하지 않는다. 기존 HIGH 사유가 없는 직접 모욕·강한 적대감은 MEDIUM이며, 애매한 표현·경미한 농담·친근한 호칭·문맥 의존 표현은 기존대로 SAFE다.
            """.stripTrailing();
    static final String CANDIDATE = candidate();

    enum Arm {
        A(false, false), B(false, true), C(true, false), D(true, true);

        final boolean batch;
        final boolean candidate;

        Arm(boolean batch, boolean candidate) {
            this.batch = batch;
            this.candidate = candidate;
        }
    }

    record Forecast(Arm arm, int repeats, int logicalRequests, long estimatedInputTokens,
                    long outputCapTokens, double estimatedUsdAtOutputCap,
                    double fourAttemptsEstimateUsd, double unknownUsageAllAttemptsReservationUsd) {
    }

    private Issue68RiskBoundaryPrompt() {
    }

    private static String candidate() {
        if (CONTROL.indexOf(ANCHOR) < 0 || CONTROL.indexOf(ANCHOR) != CONTROL.lastIndexOf(ANCHOR)) {
            throw new IllegalStateException("ISSUE68_RISK_ANCHOR_CHANGED");
        }
        return CONTROL.replace(ANCHOR, ANCHOR + "\n" + ADDITION);
    }

    static String system(Arm arm) {
        String common = arm.candidate ? CANDIDATE : CONTROL;
        // Production의 Batch 지시문 원문을 그대로 재사용한다.
        return common + (arm.batch ? Issue68EvaluationContract.batchSystemPrompt().substring(CONTROL.length()) : "");
    }

    static List<Issue55MicroBatchEvaluation.Batch> batches() {
        var targets = Issue55MicroBatchF0.targets();
        var batches = new ArrayList<Issue55MicroBatchEvaluation.Batch>();
        for (int group = 0; group < 2; group++) {
            var items = new ArrayList<Issue55MicroBatchEvaluation.Target>();
            for (int i = 0; i < 10; i++) {
                // 실제 Kafka Batch와 같이 요청마다 중립 ID를 다시 부여한다. Case ID/GT/순서는 보존한다.
                items.add(new Issue55MicroBatchEvaluation.Target("m%03d".formatted(i + 1),
                        targets.get(group * 10 + i).testCase()));
            }
            batches.add(new Issue55MicroBatchEvaluation.Batch("risk-a1-%02d".formatted(group + 1), items));
        }
        return List.copyOf(batches);
    }

    // Tokenizer + framing 여유256, 전체 출력 cap. Provider Usage/엄밀한 비용 상한이 아니다.
    static List<Forecast> forecast(int repeats) {
        if (repeats < 1 || repeats > 3) {
            throw new IllegalArgumentException("ISSUE68_RISK_REPEATS_INVALID");
        }
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        var forecasts = new ArrayList<Forecast>();
        for (var arm : Arm.values()) {
            long input = 0;
            int requests = arm.batch ? 2 : 20;
            var users = arm.batch
                    ? batches().stream().map(b -> Issue55MicroBatchEvaluation.JSON.writeValueAsString(b.input())).toList()
                    : Issue55MicroBatchF0.targets().stream().map(t -> t.testCase().message()).toList();
            for (var user : users) {
                input += encoding.countTokens(system(arm)) + encoding.countTokens(user)
                        + encoding.countTokens(Issue68EvaluationContract.schema(arm.batch)) + 256;
            }
            long output = repeats * 20L * Issue68EvaluationContract.OUTPUT_PER_ITEM;
            long repeatedInput = repeats * input;
            double estimate = Issue68Budget.cost(repeatedInput, output) / 1_000_000_000.0;
            long reserve = Issue68Budget.cost(128_000, arm.batch ? 1280 : 128);
            forecasts.add(new Forecast(arm, repeats, repeats * requests, repeatedInput, output,
                    estimate, estimate * 4, repeats * requests * 4.0 * reserve / 1_000_000_000.0));
        }
        return List.copyOf(forecasts);
    }
}
