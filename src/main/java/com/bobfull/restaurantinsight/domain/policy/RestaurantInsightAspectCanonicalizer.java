package com.bobfull.restaurantinsight.domain.policy;

import com.bobfull.restaurantinsight.domain.entity.FeedbackOpinionType;

// 모델이 다르게 표현한 같은 의견을 안정적인 집계 키로 정규화한다.
public final class RestaurantInsightAspectCanonicalizer {

    private RestaurantInsightAspectCanonicalizer() { }

    // 자유 문구를 그대로 쓰면 같은 의견이 여러 그룹으로 나뉘어 최소 발신자 기준을 넘지 못할 수 있다.
    // MENU와 ETC처럼 enum만으로 대상을 확정할 수 없는 항목은 호출자가 제외하고 검증된 문구를 유지한다.
    public static String canonicalAspectFor(FeedbackOpinionType opinionType) {
        return switch (opinionType) {
            case FRIENDLINESS -> "직원 응대";
            case SERVICE_SPEED -> "서비스 속도";
            case PRICE_LEVEL -> "가격";
            case CLEANLINESS -> "매장 청결";
            case WAITING -> "대기 시간";
            case TASTE -> "맛";
            case TEXTURE -> "식감";
            case SALTINESS -> "간";
            case SPICINESS -> "매운맛";
            case SWEETNESS -> "단맛";
            case PORTION -> "양";
            case FRESHNESS -> "신선도";
            case TEMPERATURE -> "온도";
            case ETC -> throw new IllegalArgumentException(
                    "ETC opinionType의 의미는 enum만으로 확정되지 않아 canonicalize 대상이 아니다. "
                            + "호출측에서 aspectType==MENU와 동일하게 검증된 LLM normalizedAspect를 유지해야 한다.");
        };
    }
}
