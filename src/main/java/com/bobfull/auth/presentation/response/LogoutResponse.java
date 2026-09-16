package com.bobfull.auth.presentation.response;

public record LogoutResponse(boolean result) {

    public static LogoutResponse success() {
        return new LogoutResponse(true);
    }
}
