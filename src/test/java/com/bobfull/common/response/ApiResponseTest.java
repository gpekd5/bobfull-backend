package com.bobfull.common.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.common.exception.BaseErrorCode;
import com.bobfull.member.domain.exception.MemberErrorCode;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void 성공_응답을_생성하면_success가_true이고_data를_포함한다() {
        // given
        String data = "hello";

        // when
        ApiResponse<String> response = ApiResponse.success(data);

        // then
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("hello");
        assertThat(response.getCode()).isNull();
    }

    @Test
    void 실패_응답을_생성하면_success가_false이고_에러코드를_포함한다() {
        // given
        BaseErrorCode errorCode = MemberErrorCode.MEMBER_NOT_FOUND;

        // when
        ApiResponse<Void> response = ApiResponse.fail(errorCode);

        // then
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(response.getMessage()).isEqualTo(errorCode.getMessage());
        assertThat(response.getData()).isNull();
    }
}
