package com.bobfull.auth.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SignupOwnerRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank String name,
        @NotBlank String phoneNumber,
        @NotBlank String businessNumber
) {
}
