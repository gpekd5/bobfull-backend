package com.bobfull.restaurantinsight.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class RestaurantInsightPrivacyValidatorTest {
    private final RestaurantInsightPrivacyValidator validator = new RestaurantInsightPrivacyValidator();

    @Test
    void 전화번호가_포함된_메시지는_Insight_분석에서_제외한다() {
        assertThat(validator.containsSensitiveIdentifier("010-1234-5678로 연락주세요")).isTrue();
    }

    @Test
    void 식별_단서가_없는_메뉴_aspect만_저장을_허용한다() {
        assertThat(validator.isSafeAspect("탕수육")).isTrue();
        assertThat(validator.isSafeAspect("김철수님")).isFalse();
        assertThat(validator.isSafeAspect("010 1234 5678")).isFalse();
    }

    @Test
    void aspect는_NFKC와_공백을_정규화하고_40_code_point를_초과하면_제외한다() {
        assertThat(validator.normalizeSafeAspect("  탕수육　 맛  ")).isEqualTo("탕수육 맛");
        assertThat(validator.normalizeSafeAspect("가".repeat(41))).isNull();
        assertThat(validator.normalizeSafeAspect("가".repeat(40))).isNotNull();
    }

    // 리뷰 지적(MAJOR): 존칭 없는 실명, 신체·복장 묘사가 결합된 직원 식별 표현, URL을 차단해야 한다.
    @Test
    void 특정_인물_이름과_직원_식별_표현을_차단한다() {
        assertThat(validator.isSafeAspect("김철수")).isFalse();
        assertThat(validator.isSafeAspect("안경 쓴 남자 직원")).isFalse();
        assertThat(validator.isSafeAspect("창가의 모자 쓴 직원")).isFalse();
        assertThat(validator.isSafeAspect("박수민씨")).isFalse();
        // "직원 응대"처럼 인상착의 묘사 없이 일반화된 표현은 계속 허용한다.
        assertThat(validator.isSafeAspect("직원 응대")).isTrue();
        assertThat(validator.isSafeAspect("직원 친절")).isTrue();
    }

    // 재리뷰 지적(MAJOR): 고정 placeholder 목록에 없는 임의 이름도 역할 단어와 결합되면 차단해야 한다.
    @Test
    void 목록에_없는_임의_이름도_역할_단어와_결합되면_차단한다() {
        assertThat(validator.isSafeAspect("직원 김현승")).isFalse();
        assertThat(validator.isSafeAspect("김현승 직원")).isFalse();
        assertThat(validator.isSafeAspect("김현승 매니저")).isFalse();
        assertThat(validator.containsSensitiveIdentifier("직원 김현승 친절했어요")).isTrue();
        // 역할 단어 근처의 메뉴/일반 명사는 이름으로 오인해 차단하지 않는다.
        assertThat(validator.isSafeAspect("직원 응대")).isTrue();
        assertThat(validator.isSafeAspect("직원 친절")).isTrue();
        assertThat(validator.isSafeAspect("탕수육")).isTrue();
        assertThat(validator.isSafeAspect("김말이")).isTrue();
        assertThat(validator.isSafeAspect("짜장면")).isTrue();
        assertThat(validator.isSafeAspect("서비스 속도")).isTrue();
        assertThat(validator.containsSensitiveIdentifier("탕수육 짜장면 직원 서비스 후기")).isFalse();
    }

    @Test
    void URL과_계정_식별자가_포함된_문장은_Provider_전송에서_제외한다() {
        assertThat(validator.containsSensitiveIdentifier("메뉴는 www.example.com/menu에서 보세요")).isTrue();
        assertThat(validator.containsSensitiveIdentifier("자세한건 http://example.com 참고")).isTrue();
        assertThat(validator.containsSensitiveIdentifier("아이디 abc123으로 문의주세요")).isTrue();
    }

    @Test
    void 계약이_허용한_기호만_aspect에서_허용한다() {
        assertThat(validator.isSafeAspect("한식/양식")).isTrue();
        assertThat(validator.isSafeAspect("스테이크(미디엄)")).isTrue();
        assertThat(validator.isSafeAspect("A&B 세트")).isTrue();
        assertThat(validator.isSafeAspect("중간점 · 표기")).isTrue();
        // 계약에 없는 기호(.,+)는 더 이상 허용하지 않는다.
        assertThat(validator.isSafeAspect("가격+할인")).isFalse();
        assertThat(validator.isSafeAspect("가성비.최고")).isFalse();
        assertThat(validator.isSafeAspect("맛,가격")).isFalse();
    }
}
