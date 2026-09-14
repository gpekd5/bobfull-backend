package com.bobfull.restaurantinsight.domain.policy;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** 명백한 잡담을 제외해 외부 AI 호출을 줄이는 저비용 Gate다. */
@Component
public class RestaurantInsightCandidateGate {
    private static final String[] KEYWORDS = {"맛", "메뉴", "음식", "서비스", "직원", "친절", "불친절", "가격", "비싸", "저렴", "청결", "깨끗", "더럽", "짜", "달", "양"};
    public boolean isCandidate(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        for (String keyword : KEYWORDS) if (normalized.contains(keyword)) return true;
        return false;
    }
}
