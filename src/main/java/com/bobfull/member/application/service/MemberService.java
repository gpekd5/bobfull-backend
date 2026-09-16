package com.bobfull.member.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.member.presentation.response.MemberResponse;
import com.bobfull.member.presentation.request.MemberUpdateRequest;
import com.bobfull.member.presentation.response.MemberUpdateResponse;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 인증 사용자 본인의 정보 조회와 수정을 담당한다.
// 대상 회원은 클라이언트 입력이 아닌 인증 사용자 ID로 결정한다.
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public MemberResponse getMe(Long memberId) {
        Member member = findMemberOrThrow(memberId);
        return MemberResponse.from(member);
    }

    // 인증 회원 본인의 이름과 휴대전화번호를 변경하고 중복 번호를 차단한다.
    @Transactional
    public MemberUpdateResponse updateMe(Long memberId, MemberUpdateRequest request) {
        Member member = findMemberOrThrow(memberId);

        if (isPhoneNumberChanged(member, request.phoneNumber())
                && memberRepository.existsByPhoneNumber(request.phoneNumber())) {
            throw new CustomException(MemberErrorCode.DUPLICATE_PHONE_NUMBER);
        }

        member.updateProfile(request.name(), request.phoneNumber());

        try {
            memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(MemberErrorCode.DUPLICATE_PHONE_NUMBER);
        }

        return MemberUpdateResponse.success();
    }

    private Member findMemberOrThrow(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    private boolean isPhoneNumberChanged(Member member, String newPhoneNumber) {
        return !member.getPhoneNumber().equals(newPhoneNumber);
    }
}
