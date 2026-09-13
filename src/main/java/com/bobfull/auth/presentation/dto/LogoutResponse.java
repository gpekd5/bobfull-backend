package com.bobfull.auth.presentation.dto;

public record LogoutResponse(boolean result) {

    public static LogoutResponse success() {
        return new LogoutResponse(true);
    }
}
