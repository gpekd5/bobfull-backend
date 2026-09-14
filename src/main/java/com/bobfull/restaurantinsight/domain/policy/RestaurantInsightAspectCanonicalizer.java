package com.bobfull.restaurantinsight.domain.policy;

import com.bobfull.restaurantinsight.domain.entity.FeedbackOpinionType;

/**
 * aspectType이 {@code MENU}가 아니면서 opinionType의 의미가 그 값만으로 충분히 확정되는
 * 경우(예: {@code FRIENDLINESS}는 항상 "직원 응대에 대한 의견"), LLM이 메시지마다 다르게
 * 생성하는 자유 텍스트 normalizedAspect("친절" vs "직원 친절함" 등)를 집계 키로 그대로 쓰면
 * 같은 의견이 문구 차이로 여러 그룹으로 쪼개져 distinct sender 집계가 3명 문턱을 넘지 못하는
 * 문제가 생긴다. 이를 막기 위해 이 경우만 서버가 고정한 canonical 문구로 치환해 항상 같은
 * 5-field 키로 모이게 한다.
 *
 * <p>{@code MENU} aspectType은 실제 메뉴명을 구분해야 하므로 치환 대상이 아니다. {@code ETC}
 * aspectType과 {@code ETC} opinionType은 둘 다 "기타"라는 이름과 달리 실제 의미가 enum만으로
 * 확정되지 않는 자유 범주이므로(구체적으로 무엇에 대한 기타 의견인지는 여전히 normalizedAspect
 * 문구가 결정한다) 이 치환 대상에서 제외한다. {@code aspectType==ETC}를 제외하지 않으면
 * opinionType만으로 canonicalize할 때 서로 다른 대상("국물"/"반찬"/"소스" 등)이 같은
 * opinionType 하나로 잘못 병합될 수 있다. 위 경우 모두 검증된 LLM normalizedAspect를 그대로
 * 유지한다(호출측 Service 참고).</p>
 */
public final class RestaurantInsightAspectCanonicalizer {

    private RestaurantInsightAspectCanonicalizer() { }

    /** {@code ETC}는 canonicalize 대상이 아니므로 호출측(Service)에서 미리 걸러야 한다. */
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
