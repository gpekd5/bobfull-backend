package com.bobfull.member.presentation.dto;

public record MemberUpdateResponse(boolean result) {

    public static MemberUpdateResponse success() {
        return new MemberUpdateResponse(true);
    }
}
