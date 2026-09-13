package com.bobfull.auth.presentation.dto;

public record LoginResponse(String accessToken, String tokenType, String refreshToken) {

    private static final String BEARER_TOKEN_TYPE = "Bearer";

    public static LoginResponse of(String accessToken, String refreshToken) {
        return new LoginResponse(accessToken, BEARER_TOKEN_TYPE, refreshToken);
    }
}
