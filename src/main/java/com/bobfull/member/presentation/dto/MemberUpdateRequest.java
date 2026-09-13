package com.bobfull.member.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record MemberUpdateRequest(
        @NotBlank String name,
        @NotBlank String phoneNumber
) {
}
