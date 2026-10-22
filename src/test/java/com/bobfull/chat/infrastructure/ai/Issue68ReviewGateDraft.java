package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.ArrayList;
import java.util.List;

// 의미 판단/허용 목록은 확정하지 않는다. V1과 관리자 검토 신호를 분리하는 테스트 전용 초안이다.
public final class Issue68ReviewGateDraft {

    private Issue68ReviewGateDraft() {
    }

    public record Comparison(String decision, List<String> v1Reasons, List<String> draftHoldReasons,
                             boolean resultExact, boolean categoryExact, boolean riskExact,
                             boolean expectedReviewTarget, boolean actualReviewTarget,
                             boolean highToMediumReviewPreserved) {
    }

    public static Comparison compare(Issue68EvaluationContract.Case testCase, ModerationResult actual,
            boolean dbCommitted) {
        var expected = testCase.expected();
        var v1 = Issue68EvaluationContract.gate(testCase, actual, dbCommitted);
        boolean expectedTarget = reviewTarget(expected);
        boolean actualTarget = dbCommitted && reviewTarget(actual);
        List<String> holds = new ArrayList<>();
        if (!dbCommitted || actual == null) {
            holds.add("UNRESOLVED");
        } else {
            if (v1.contains("FN")) {
                holds.add("FN");
            }
            if (expectedTarget && !actualTarget) {
                holds.add("REVIEW_TARGET_MISS");
            }
            if (expected.result() == ModerationResultType.SAFE && actual.result() == ModerationResultType.FLAGGED) {
                holds.add("FP_REQUIRES_HUMAN");
            }
            if (!expected.categories().equals(actual.categories())) {
                holds.add("CATEGORY_MISMATCH_REQUIRES_HUMAN");
            }
            if (v1.contains("POLICY_COMBINATION")) {
                holds.add("POLICY_COMBINATION");
            }
        }
        boolean resolved = dbCommitted && actual != null;
        boolean resultExact = resolved && expected.result() == actual.result();
        boolean categoryExact = resolved && expected.categories().equals(actual.categories());
        boolean riskExact = resolved && expected.riskLevel() == actual.riskLevel();
        boolean highToMedium = actual != null && expected.riskLevel() == RiskLevel.HIGH
                && actual.riskLevel() == RiskLevel.MEDIUM && expectedTarget && actualTarget
                && resultExact && categoryExact;
        // holds가 비어도 통과가 아니다. Critical 지정/문맥 의존 GT/허용 범위는 Human 승인 전이다.
        return new Comparison("HUMAN_APPROVAL_REQUIRED", v1, List.copyOf(holds), resultExact,
                categoryExact, riskExact, expectedTarget, actualTarget, highToMedium);
    }

    public static boolean reviewTarget(ModerationResult result) {
        return result != null && result.result() == ModerationResultType.FLAGGED
                && (result.riskLevel() == RiskLevel.MEDIUM || result.riskLevel() == RiskLevel.HIGH);
    }
}
