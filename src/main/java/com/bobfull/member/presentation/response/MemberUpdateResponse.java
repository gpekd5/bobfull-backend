package com.bobfull.member.presentation.response;

public record MemberUpdateResponse(boolean result) {

    public static MemberUpdateResponse success() {
        return new MemberUpdateResponse(true);
    }
}
