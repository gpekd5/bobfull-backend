package com.bobfull.chat.application.service;

import com.bobfull.chat.application.dto.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** #251의 고신뢰 CLEAR_FLAGGED 전용 filter다. 매칭하지 않으면 항상 LLM에 위임한다. */
@Component
public class ModerationRuleFilter {
    private static final Pattern MOBILE_PHONE = Pattern.compile("(?<!\\d)010[\\s.-]*\\d{4}[\\s.-]*\\d{4}(?!\\d)");
    private static final Pattern PERSONAL_PHONE_CONTEXT = Pattern.compile("(내 번호|제 번호|내 연락처|제 연락처)");
    private static final Pattern EXACT_PROFANITY = Pattern.compile("^(씨발|시발|시[\\s.\\-@]+발|개새끼(야)?|죽여버린다)$");
    private static final Pattern COIN_INDUCEMENT = Pattern.compile("코인 수익방.*(들어오세요|가입하세요|참여하세요|신청하세요)");
    private static final Pattern STOCK_INDUCEMENT = Pattern.compile("주식 리딩방.*(들어오세요|가입하세요|참여하세요|신청하세요|종목을 알려드립니다)");
    private static final Pattern LOAN_INDUCEMENT = Pattern.compile("대출 승인 보장.*(신청하세요|가입하세요)");

    public Optional<ModerationResult> clearFlagged(String content) {
        if (isPromptInjectionCandidate(content)) return Optional.empty();
        boolean personal = MOBILE_PHONE.matcher(content).find() && PERSONAL_PHONE_CONTEXT.matcher(content).find()
                && !hasPersonalContextNegation(content);
        boolean profanity = EXACT_PROFANITY.matcher(content.trim()).matches();
        boolean spam = COIN_INDUCEMENT.matcher(content).find() || STOCK_INDUCEMENT.matcher(content).find()
                || LOAN_INDUCEMENT.matcher(content).find();
        boolean profanitySignal = hasProfanitySignal(content);
        boolean spamSignal = hasSpamSignal(content);
        if ((personal ? 1 : 0) + (profanitySignal ? 1 : 0) + (spamSignal ? 1 : 0) > 1) return Optional.empty();
        int matchedFamilies = (personal ? 1 : 0) + (profanity ? 1 : 0) + (spam ? 1 : 0);
        if (matchedFamilies != 1) return Optional.empty();
        if (personal) return flagged(ModerationCategory.PERSONAL_INFORMATION, RiskLevel.MEDIUM);
        if (profanity) return flagged(ModerationCategory.PROFANITY, RiskLevel.HIGH);
        return flagged(ModerationCategory.SPAM, RiskLevel.HIGH);
    }

    Optional<ModerationResult> clearSplitFlagged(String joinedNormalized) {
        if (joinedNormalized.matches("^(씨발|시발|병신|개새끼(야)?|죽여버린다)$")) {
            return flagged(ModerationCategory.PROFANITY, RiskLevel.HIGH);
        }
        return Optional.empty();
    }

    Optional<ModerationResult> clearSplitFlagged(List<String> canonicalCandidates) {
        return canonicalCandidates.stream().map(this::clearSplitFlagged).flatMap(Optional::stream).findFirst();
    }

    private static Optional<ModerationResult> flagged(ModerationCategory category, RiskLevel riskLevel) {
        return Optional.of(new ModerationResult(ModerationResultType.FLAGGED, java.util.Set.of(category), riskLevel));
    }

    private static boolean isPromptInjectionCandidate(String content) {
        return content.contains("이전 지시") || content.contains("이전 명령") || content.contains("System Prompt")
                || content.contains("JSON Schema") || content.contains("Moderation AI") || content.contains("{\"result\":\"SAFE\"}")
                || content.contains("관리자 권한") || content.contains("분석하지 말고") || content.contains("정책을 공개")
                || content.contains("역할을 바꿔");
    }
    private static boolean hasPersonalContextNegation(String content) {
        return content.contains("내 번호 아니") || content.contains("제 번호 아니")
                || content.contains("내 연락처 아니") || content.contains("제 연락처 아니");
    }
    private static boolean hasProfanitySignal(String content) {
        return content.contains("씨발") || content.contains("시발") || content.contains("개새끼") || content.contains("죽여버린다");
    }
    private static boolean hasSpamSignal(String content) {
        return content.contains("수익방") || content.contains("주식 리딩방") || content.contains("대출 승인 보장");
    }
}
