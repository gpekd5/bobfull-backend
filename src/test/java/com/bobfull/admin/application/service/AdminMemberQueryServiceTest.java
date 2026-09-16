package com.bobfull.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;

import com.bobfull.admin.presentation.response.AdminMemberDetailResponse;
import com.bobfull.admin.application.result.AdminMemberResult;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AdminMemberQueryServiceTest {

    @Mock private MemberRepository memberRepository;

    @InjectMocks private AdminMemberQueryService service;

    @Test
    void 유효하지_않은_role_필터는_400_예외가_발생한다() {
        Pageable pageable = PageRequest.of(0, 20);

        Throwable result = catchThrowable(() -> service.getMembers(null, "INVALID_ROLE", null, pageable));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 존재하지_않는_memberId_상세조회는_404_예외가_발생한다() {
        given(memberRepository.findMemberDetail(999L)).willReturn(Optional.empty());

        Throwable result = catchThrowable(() -> service.getMember(999L));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_ID_NOT_FOUND);
    }

    @Test
    void 존재하는_memberId_상세조회는_서울시간으로_변환된_응답을_반환한다() {
        AdminMemberResult result = new AdminMemberResult(
                1L, "user@example.com", "홍길동", "01011112222", MemberRole.MEMBER, 0L,
                Instant.parse("2026-08-01T00:00:00Z"), null);
        given(memberRepository.findMemberDetail(1L)).willReturn(Optional.of(result));

        AdminMemberDetailResponse response = service.getMember(1L);

        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.deletedAt()).isNull();
    }
}
