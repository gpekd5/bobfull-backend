package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.application.service.ModerationRuleFilter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Issue #251 STEP 3 production 적용 전용, Frozen Dataset Rule routing simulation이다. */
class Issue251RuleRoutingSimulationTest {
    private final ModerationRuleFilter ruleFilter = new ModerationRuleFilter();

    @Test
    void Frozen_Dataset_66건에서_CLEAR_FLAGGED_Rule의_false_positive가_없어야_한다() {
        SimulationMetrics metrics = new SimulationMetrics();

        for (Issue251HardeningDataset.SingleMessageCase testCase : Issue251HardeningDataset.singleMessageCases()) {
            RouteDecision decision = route(testCase.input()).orElse(null);
            metrics.addSingle(testCase, decision);
            printSingle(testCase, decision);
        }
        for (Issue251HardeningDataset.SplitSequenceCase testCase : Issue251HardeningDataset.splitSequenceCases()) {
            // Recent Context는 NOT_ADOPTED다. sequence 결합이나 compactCandidate 생성 없이 항상 LLM에 위임한다.
            metrics.addSplit(testCase);
            System.out.printf("[251-RULE-SIMULATION] case=%s input=%s expected=%s/%s/%s route=LLM_REQUIRED ruleMatched=false reason=split-sequence-no-context-join result=PASS%n",
                    testCase.caseId(), testCase.messages().stream().map(Issue251HardeningDataset.SplitMessage::content).toList(),
                    testCase.proposedFinalModerationResult(), testCase.proposedCategories(), testCase.proposedRisk());
        }

        metrics.print();
        assertThat(metrics.clearFlaggedFalsePositive).as("Human SAFE case must not enter CLEAR_FLAGGED").isZero();
        assertThat(metrics.clearFlaggedLabelMismatch).as("CLEAR_FLAGGED decision must match Human FLAGGED label").isZero();
    }

    private Optional<RouteDecision> route(String input) {
        return ruleFilter.clearFlagged(input).map(result -> new RouteDecision(result.categories().iterator().next(), result.riskLevel(), reason(result.categories().iterator().next())));
    }

    private static String reason(ModerationCategory category) {
        return switch (category) {
            case PERSONAL_INFORMATION -> "mobile-phone-010";
            case PROFANITY -> "strong-profanity";
            case SPAM -> "commercial-financial-inducement";
        };
    }

    private static void printSingle(Issue251HardeningDataset.SingleMessageCase testCase, RouteDecision decision) {
        if (decision == null) {
            System.out.printf("[251-RULE-SIMULATION] case=%s input=%s expected=%s/%s/%s route=LLM_REQUIRED ruleMatched=false reason=%s result=PASS%n",
                    testCase.caseId(), quote(testCase.input()), testCase.proposedModerationResult(), testCase.proposedCategories(), testCase.proposedRisk(),
                    "PROMPT_INJECTION".equals(testCase.type()) ? "prompt-injection-preserve-llm-security-contract" : "no-high-confidence-rule");
            return;
        }
        System.out.printf("[251-RULE-SIMULATION] case=%s input=%s expected=%s/%s/%s route=CLEAR_FLAGGED ruleMatched=true category=%s risk=%s reason=%s result=PASS%n",
                testCase.caseId(), quote(testCase.input()), testCase.proposedModerationResult(), testCase.proposedCategories(), testCase.proposedRisk(),
                decision.category(), decision.risk(), decision.reason());
    }

    private static String quote(String input) { return '"' + input.replace("\n", "\\n") + '"'; }

    private record RouteDecision(ModerationCategory category, RiskLevel risk, String reason) { }

    private static final class SimulationMetrics {
        int cases;
        int clearFlagged;
        int llmRequired;
        int clearFlaggedTruePositive;
        int clearFlaggedFalsePositive;
        int clearFlaggedLabelMismatch;

        void addSingle(Issue251HardeningDataset.SingleMessageCase testCase, RouteDecision decision) {
            cases++;
            if (decision == null) {
                llmRequired++;
                return;
            }
            clearFlagged++;
            boolean expectedFlagged = testCase.proposedModerationResult() == ModerationResultType.FLAGGED;
            boolean exact = expectedFlagged && testCase.proposedCategories().equals(java.util.Set.of(decision.category()))
                    && testCase.proposedRisk() == decision.risk();
            if (exact) clearFlaggedTruePositive++;
            else if (!expectedFlagged) clearFlaggedFalsePositive++;
            else clearFlaggedLabelMismatch++;
        }

        void addSplit(Issue251HardeningDataset.SplitSequenceCase ignored) { cases++; llmRequired++; }

        void print() {
            double precision = clearFlagged == 0 ? 0 : (double) clearFlaggedTruePositive / clearFlagged;
            System.out.printf("[251-RULE-SIMULATION-SUMMARY] datasetCases=%d clearFlagged=%d llmRequired=%d ruleFastPathCoverage=%.3f ruleFastPathPrecision=%.3f ruleFastPathFalsePositive=%d humanSafeClearFlagged=%d%n",
                    cases, clearFlagged, llmRequired, (double) clearFlagged / cases, precision, clearFlaggedFalsePositive, clearFlaggedFalsePositive);
        }
    }
}
