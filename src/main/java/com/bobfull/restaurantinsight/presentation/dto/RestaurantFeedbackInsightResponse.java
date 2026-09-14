package com.bobfull.restaurantinsight.presentation.dto;

import com.bobfull.restaurantinsight.domain.entity.FeedbackAspectType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackOpinionType;
import com.bobfull.restaurantinsight.infrastructure.repository.RestaurantFeedbackInsightRepository.Aggregation;

public record RestaurantFeedbackInsightResponse(String category, String aspectType, String normalizedAspect, String opinionType, String sentiment, long count, String summary) {
    public static RestaurantFeedbackInsightResponse from(Aggregation result) {
        String sentimentText = sentimentKorean(result.getSentiment().name());
        // MENU와 ETC는 normalizedAspect가 검증된 LLM 자유 텍스트(실제 메뉴명/구체 대상)라
        // opinionType과 함께 써야 뜻이 통한다("탕수육 식감", "주차 공간 문의 대응"). 그 외에는
        // normalizedAspect 자체가 이미 서버 canonical 문구("직원 응대", "가격" 등)라 opinionType을
        // 따로 덧붙이면 "직원 응대 친절"처럼 중복된다.
        boolean keepsLlmAspect = result.getAspectType() == FeedbackAspectType.MENU
                || result.getAspectType() == FeedbackAspectType.ETC
                || result.getOpinionType() == FeedbackOpinionType.ETC;
        String summary = keepsLlmAspect
                ? result.getAspect() + " " + opinionKorean(result.getOpinionType().name()) + "에 대한 " + sentimentText + " 의견 " + result.getSenderCount() + "명"
                : result.getAspect() + "에 대한 " + sentimentText + " 의견 " + result.getSenderCount() + "명";
        return new RestaurantFeedbackInsightResponse(result.getCategory().name(), result.getAspectType().name(), result.getAspect(), result.getOpinionType().name(), result.getSentiment().name(), result.getSenderCount(), summary);
    }
    private static String sentimentKorean(String sentiment) { return switch (sentiment) { case "POSITIVE" -> "긍정"; case "NEGATIVE" -> "부정"; default -> "중립"; }; }
    private static String opinionKorean(String opinion) {
        return switch (opinion) {
            case "TASTE" -> "맛"; case "TEXTURE" -> "식감"; case "SALTINESS" -> "간"; case "SPICINESS" -> "매운맛";
            case "SWEETNESS" -> "단맛"; case "PORTION" -> "양"; case "FRESHNESS" -> "신선도"; case "TEMPERATURE" -> "온도";
            case "FRIENDLINESS" -> "친절"; case "SERVICE_SPEED" -> "응대 속도"; case "PRICE_LEVEL" -> "가격";
            case "CLEANLINESS" -> "청결"; case "WAITING" -> "대기 시간"; default -> "평가";
        };
    }
}
