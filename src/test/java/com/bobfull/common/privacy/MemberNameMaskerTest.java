package com.bobfull.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * MemberNameMasker의 길이별 마스킹 분기(§11-11, API 명세 예시 "홍○동" 기준)를 검증한다.
 */
class MemberNameMaskerTest {

    @Test
    void null_이름은_그대로_반환한다() {
        assertThat(MemberNameMasker.mask(null)).isNull();
    }

    @Test
    void 한_글자_이름은_그대로_반환한다() {
        assertThat(MemberNameMasker.mask("김")).isEqualTo("김");
    }

    @Test
    void 두_글자_이름은_두번째_글자만_마스킹한다() {
        assertThat(MemberNameMasker.mask("홍길")).isEqualTo("홍○");
    }

    @Test
    void 세_글자_이름은_가운데_글자만_마스킹한다() {
        assertThat(MemberNameMasker.mask("홍길동")).isEqualTo("홍○동");
    }

    @Test
    void 네_글자_이상_이름은_첫_글자와_마지막_글자만_남기고_마스킹한다() {
        assertThat(MemberNameMasker.mask("남궁석민")).isEqualTo("남○○민");
    }
}
