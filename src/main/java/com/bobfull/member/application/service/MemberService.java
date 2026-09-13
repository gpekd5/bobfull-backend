package com.bobfull.member.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.member.presentation.dto.MemberResponse;
import com.bobfull.member.presentation.dto.MemberUpdateRequest;
import com.bobfull.member.presentation.dto.MemberUpdateResponse;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 사용자 본인의 정보 조회·수정을 담당한다.
 * 대상 회원은 SecurityContext의 인증 사용자 ID로만 결정하며 Request 값을 신뢰하지 않는다.
 */
@Service
public class MemberService {

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public MemberResponse getMe(Long memberId) {
        Member member = findMemberOrThrow(memberId);
        return MemberResponse.from(member);
    }

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
