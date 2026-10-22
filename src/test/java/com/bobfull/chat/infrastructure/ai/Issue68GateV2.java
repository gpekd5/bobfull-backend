package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// GT는 그대로 두고 사전 의미 분류/보호 속성만 분리한다. 과거 응답을 조회하지 않는다.
public final class Issue68GateV2 {
    public static final String VERSION = "issue68-service-gate-v2-proposal-1";
    public static final String METADATA_VERSION = "issue68-policy-v2-review-metadata-1";
    public static final String EXECUTION_VERSION = "issue68-service-gate-v2-execution-2";
    public static final String FROZEN_METADATA_SHA256 = "a3a424323e5585da1b9a611281ac37174c60c4b946987e541c44ad5981cd40b5";

    public enum Profile { CRITICAL, CONTEXT_DEPENDENT, RISK_BOUNDARY, STANDARD }

    public record Metadata(String id, String contentSha256, Profile profile, String reason,
                           Set<ModerationCategory> requiredCategories, RiskLevel minimumRisk) {
    }

    public record Evaluation(String id, List<String> v1Blockers, List<String> v2Blockers,
                             List<String> v2Notes, Issue68ReviewGateDraft.Comparison exactAndReview) {
    }

    private Issue68GateV2() {
    }

    public static List<Metadata> metadata() {
        return Issue68EvaluationContract.heldout().stream().map(c -> {
            String id = c.id();
            Profile profile = Profile.STANDARD;
            String reason = switch (id.split("-")[1]) {
                case "SAFE" -> "예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음";
                case "PROFANITY" -> "문장에 상대방을 향한 직접 모욕/적대 표현이 명시됨";
                case "PI" -> "개인정보 제외 조건인 공개 사업장 연락처 또는 일반 숫자/예약번호";
                default -> "명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용";
            };
            if (numbered(id, "PROFANITY", Set.of(2, 8, 9, 10))) {
                profile = Profile.CRITICAL;
                reason = "명시적 강한 욕설/약어/분리 표기 모욕; 최소 Risk는 원래 GT와 동일";
            } else if (numbered(id, "PROFANITY", Set.of(14, 15))) {
                profile = Profile.CRITICAL;
                reason = "Human 엄격 보호: 조건부 보복 또는 거주지 추적·방문; 기존 HIGH 유지";
            } else if (id.startsWith("HOLDOUT-PI-") && Integer.parseInt(id.substring(id.length() - 2)) <= 16) {
                profile = Profile.CRITICAL;
                reason = "개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호";
            } else if (numbered(id, "SPAM", Set.of(8, 9, 10))) {
                profile = Profile.CRITICAL;
                reason = "투자 리딩방/고수익 부업방/확정 수익 정보방의 명시적 고위험 금전 유도";
            } else if (numbered(id, "SAFE", Set.of(16, 17, 19, 20))
                    || numbered(id, "PROFANITY", Set.of(5, 6, 7, 12, 13, 16, 17, 18, 19, 20))) {
                profile = Profile.CONTEXT_DEPENDENT;
                reason = id.equals("HOLDOUT-PROFANITY-16")
                        ? "위해 행위/의도나 강한 적대감이 명시되지 않은 만남·각오 요구; Human 문맥 의존 지정"
                        : "경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음";
            } else if (numbered(id, "SPAM", Set.of(11, 16))) {
                profile = Profile.RISK_BOUNDARY;
                reason = "Human 위험도 경계 지정; 기존 SPAM/HIGH 유지, 비Critical 하향의 검토 신호 보존 확인";
            } else if (id.equals("HOLDOUT-SAFE-09")) {
                reason = "Human 기존 SAFE 유지: 단체 예약 연락처; 공개/개인 소유자를 추가 추정해 라벨을 바꾸지 않음";
            } else if (id.equals("HOLDOUT-SPAM-12")) {
                reason = "Human 기존 SPAM/MEDIUM 유지: 신메뉴 안내와 이용 요청의 확정된 홍보 경계";
            }
            return new Metadata(id, Issue68EvaluationContract.sha256(c.content()), profile, reason,
                    profile == Profile.CRITICAL ? c.expected().categories() : Set.of(),
                    profile == Profile.CRITICAL ? c.expected().riskLevel() : null);
        }).toList();
    }

    private static boolean numbered(String id, String family, Set<Integer> numbers) {
        return id.startsWith("HOLDOUT-" + family + "-")
                && numbers.contains(Integer.parseInt(id.substring(id.length() - 2)));
    }

    public static String metadataSha256() {
        String canonical = METADATA_VERSION + "\n" + Issue68EvaluationContract.groundTruthSha256() + "\n"
                + metadata().stream().sorted(java.util.Comparator.comparing(Metadata::id))
                .map(m -> m.id() + "|" + m.contentSha256() + "|" + m.profile() + "|" + m.reason() + "|"
                        + m.requiredCategories().stream().map(Enum::name).sorted().toList() + "|" + m.minimumRisk())
                .collect(java.util.stream.Collectors.joining("\n"));
        return Issue68EvaluationContract.sha256(canonical);
    }

    public static void requireFrozen() {
        if (!FROZEN_METADATA_SHA256.equals(metadataSha256())
                || !Issue68EvaluationContract.FROZEN_GT_SHA256.equals(Issue68EvaluationContract.groundTruthSha256())) {
            throw new IllegalStateException("ISSUE68_GATE_METADATA_CHANGED");
        }
    }

    public static Evaluation evaluate(Issue68EvaluationContract.Case c, ModerationResult actual, boolean committed) {
        var metadata = metadata().stream().filter(m -> m.id().equals(c.id())).findFirst().orElseThrow();
        if (!metadata.contentSha256().equals(Issue68EvaluationContract.sha256(c.content()))) {
            throw new IllegalArgumentException("ISSUE68_METADATA_CONTENT_CHANGED");
        }
        var detail = Issue68ReviewGateDraft.compare(c, actual, committed);
        List<String> blockers = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (!committed || actual == null) {
            blockers.add("UNRESOLVED");
        } else {
            if (detail.expectedReviewTarget() && !detail.actualReviewTarget()) {
                blockers.add("REVIEW_TARGET_MISS");
            }
            if (c.expected().result() == ModerationResultType.FLAGGED && actual.result() == ModerationResultType.SAFE) {
                blockers.add(metadata.profile() == Profile.CRITICAL ? "CRITICAL_FN" : "FN");
            }
            if (metadata.profile() == Profile.CRITICAL) {
                if (!actual.categories().containsAll(metadata.requiredCategories())) {
                    blockers.add("CRITICAL_CATEGORY_MISS");
                }
                if (actual.riskLevel().ordinal() < metadata.minimumRisk().ordinal()) {
                    blockers.add("CRITICAL_RISK_DOWNGRADE");
                }
            }
            if (detail.v1Reasons().contains("POLICY_COMBINATION")) {
                blockers.add("POLICY_COMBINATION");
            }
            if (!detail.categoryExact()) {
                blockers.add("CATEGORY_MISMATCH");
            }
            if (c.expected().result() == ModerationResultType.SAFE && actual.result() == ModerationResultType.FLAGGED) {
                // Native 공통 FP도 별도 검토 없이 절대 품질 통과로 만들지 않는다.
                blockers.add("FP_HUMAN_REVIEW");
            }
            if (!detail.riskExact()) {
                if (metadata.profile() != Profile.CRITICAL && detail.highToMediumReviewPreserved()) {
                    notes.add("NONCRITICAL_HIGH_MEDIUM_REVIEW_PRESERVED");
                } else if (metadata.profile() != Profile.CRITICAL
                        || actual.riskLevel().ordinal() > metadata.minimumRisk().ordinal()) {
                    blockers.add("OTHER_RISK_MISMATCH_HUMAN_REVIEW");
                }
            }
        }
        if (metadata.profile() == Profile.CONTEXT_DEPENDENT) {
            notes.add("CONTEXT_DEPENDENT_REPORT_WITHOUT_EXCLUSION");
        }
        return new Evaluation(c.id(), detail.v1Reasons(), List.copyOf(blockers), List.copyOf(notes), detail);
    }

    public static Map<String, Object> manifest() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", VERSION);
        result.put("metadataVersion", METADATA_VERSION);
        result.put("groundTruthSha256", Issue68EvaluationContract.groundTruthSha256());
        result.put("metadataSha256", metadataSha256());
        result.put("provenance", "historical-outputs-known-not-blind; protection-list-proposal-for-human");
        result.put("profiles", metadata());
        return result;
    }

    // 평가 오류는 그대로 보존하고 실행 중단 여부만 별도로 결정한다.
    public static List<String> executionBlockers(Issue68EvaluationContract.Case c, ModerationResult actual,
            boolean committed, boolean nativeMode, ModerationResult nativeResult) {
        var evaluation = evaluate(c, actual, committed);
        boolean fp = actual != null && c.expected().result() == ModerationResultType.SAFE
                && actual.result() == ModerationResultType.FLAGGED;
        boolean commonFp = fp && nativeResult != null && nativeResult.result() == ModerationResultType.FLAGGED;
        List<String> reasons = new ArrayList<>(evaluation.v2Blockers());
        if (fp && (nativeMode || commonFp)) {
            // 일반 FP에 수반되는 Category/Risk 오차도 점수에서 삭제하지 않는다.
            reasons.removeAll(List.of("FP_HUMAN_REVIEW", "CATEGORY_MISMATCH", "OTHER_RISK_MISMATCH_HUMAN_REVIEW"));
            if (!nativeMode && !actual.equals(nativeResult)) {
                reasons.add("COMMON_FP_CHANGED_REQUIRES_REVIEW");
            }
        }
        if (!nativeMode) {
            reasons.addAll(paired(c, nativeResult, actual).regressions());
        }
        return reasons.stream().distinct().sorted().toList();
    }

    public record Paired(List<String> regressions, List<String> commonErrors) {
    }

    public static Paired paired(Issue68EvaluationContract.Case c, ModerationResult nativeResult,
            ModerationResult pollResult) {
        List<String> regressions = new ArrayList<>(Issue68EvaluationContract.pairedGate(c, nativeResult, pollResult));
        List<String> common = new ArrayList<>();
        if (nativeResult != null && pollResult != null) {
            if (nativeResult.categories().equals(c.expected().categories())
                    && !pollResult.categories().equals(c.expected().categories())) {
                regressions.add("CATEGORY_REGRESSION");
            }
            if (nativeResult.result() != c.expected().result() && pollResult.result() != c.expected().result()) {
                common.add(c.expected().result() == ModerationResultType.SAFE ? "COMMON_FP" : "COMMON_FN");
            }
            if (!nativeResult.categories().equals(c.expected().categories())
                    && !pollResult.categories().equals(c.expected().categories())) {
                common.add("COMMON_CATEGORY_ERROR");
            }
            if (nativeResult.riskLevel() != c.expected().riskLevel() && pollResult.riskLevel() != c.expected().riskLevel()) {
                common.add("COMMON_RISK_ERROR");
            }
        }
        return new Paired(List.copyOf(regressions), List.copyOf(common));
    }
}
