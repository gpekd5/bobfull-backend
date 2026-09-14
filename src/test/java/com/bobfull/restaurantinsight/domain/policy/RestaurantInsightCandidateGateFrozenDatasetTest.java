package com.bobfull.restaurantinsight.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #277 Evidence E: Candidate Gate가 명백한 잡담을 걸러 Provider 호출을 줄이는 비율을
 * 고정 데이터셋으로 재현한다. Human-labeled Ground Truth가 없으므로 accuracy/precision/recall은
 * 주장하지 않고, gate 통과율(=Provider 호출이 필요했을 건수)만 기록한다.
 */
class RestaurantInsightCandidateGateFrozenDatasetTest {

    private final RestaurantInsightCandidateGate gate = new RestaurantInsightCandidateGate();

    /** 실제 배포와 무관한 합성 데이터셋이다(#277 CLAUDE.md 금지 조항: 실사용 채팅 데이터 미사용). */
    private static final List<String> FROZEN_DATASET = List.of(
            "탕수육 맛 좋아요", "짜장면이 너무 짜요", "직원분이 친절했어요", "가격이 좀 비싸요",
            "매장이 깨끗해서 좋았어요", "양이 너무 적어요", "서비스가 별로였어요", "저렴하고 좋아요",
            "내일 몇 시에 만날까요", "오늘 날씨 좋네요", "다음에 또 봐요", "예약 확인 부탁드려요",
            "감사합니다", "안녕하세요", "네 알겠습니다", "언제 도착하세요"
    );

    @Test
    void Frozen_Dataset에서_Gate_통과율과_Provider_호출_감소율을_기록한다() {
        long total = FROZEN_DATASET.size();
        long passed = FROZEN_DATASET.stream().filter(gate::isCandidate).count();
        long reduced = total - passed;
        double reductionRate = (double) reduced / total;

        // 이번 고정 데이터셋(8건 관련 발화 + 8건 잡담) 기준 실제 결과를 고정 회귀로 남긴다.
        assertThat(total).isEqualTo(16);
        assertThat(passed).isEqualTo(8);
        assertThat(reduced).isEqualTo(8);
        assertThat(reductionRate).isEqualTo(0.5);
    }
}
