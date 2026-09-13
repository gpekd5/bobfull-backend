package com.bobfull.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.member.presentation.dto.MemberResponse;
import com.bobfull.member.presentation.dto.MemberUpdateRequest;
import com.bobfull.member.presentation.dto.MemberUpdateResponse;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 인증 사용자 본인 정보 조회·수정을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberService memberService;

    @Test
    void 존재하는_회원ID로_조회하면_본인_정보를_반환한다() {
        // given
        Member member = Member.createMember("user@example.com", "encoded-password", "홍길동", "01011112222");
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        // when
        MemberResponse response = memberService.getMe(1L);

        // then
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.businessNumber()).isNull();
    }

    @Test
    void 존재하지_않는_회원ID로_조회하면_예외가_발생한다() {
        // given
        given(memberRepository.findById(999L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> memberService.getMe(999L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void 전화번호를_변경하지_않으면_중복_검사_없이_수정에_성공한다() {
        // given
        Member member = Member.createMember("user@example.com", "encoded-password", "홍길동", "01011112222");
        MemberUpdateRequest request = new MemberUpdateRequest("새이름", "01011112222");
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        // when
        MemberUpdateResponse response = memberService.updateMe(1L, request);

        // then
        assertThat(response.result()).isTrue();
        assertThat(member.getName()).isEqualTo("새이름");
        verify(memberRepository, never()).existsByPhoneNumber(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 다른_회원이_사용중인_전화번호로_변경하면_예외가_발생한다() {
        // given
        Member member = Member.createMember("user@example.com", "encoded-password", "홍길동", "01011112222");
        MemberUpdateRequest request = new MemberUpdateRequest("새이름", "01099998888");
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(memberRepository.existsByPhoneNumber("01099998888")).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> memberService.updateMe(1L, request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_PHONE_NUMBER);
        assertThat(member.getPhoneNumber()).isEqualTo("01011112222");
    }

    @Test
    void 사전_검사_통과후_저장시점에_DB_제약을_위반하면_중복_전화번호_예외로_변환한다() {
        // given
        Member member = Member.createMember("user@example.com", "encoded-password", "홍길동", "01011112222");
        MemberUpdateRequest request = new MemberUpdateRequest("새이름", "01099998888");
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(memberRepository.existsByPhoneNumber("01099998888")).willReturn(false);
        given(memberRepository.saveAndFlush(member)).willThrow(new DataIntegrityViolationException("duplicate key"));

        // when
        Throwable result = catchThrowable(() -> memberService.updateMe(1L, request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_PHONE_NUMBER);
    }
}
