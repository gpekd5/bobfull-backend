package com.bobfull.auth.presentation.controller;

import com.bobfull.auth.presentation.request.LoginRequest;
import com.bobfull.auth.presentation.response.LoginResponse;
import com.bobfull.auth.presentation.response.LogoutResponse;
import com.bobfull.auth.presentation.request.ReissueRequest;
import com.bobfull.auth.presentation.response.ReissueResponse;
import com.bobfull.auth.presentation.request.SignupOwnerRequest;
import com.bobfull.auth.presentation.response.SignupResponse;
import com.bobfull.auth.presentation.request.SignupUserRequest;
import com.bobfull.auth.application.service.AuthService;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.auth.application.model.AuthMember;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    @PostMapping("/signup/users")
    public ResponseEntity<ApiResponse<SignupResponse>> signupUser(@Valid @RequestBody SignupUserRequest request) {
        SignupResponse response = authService.signupMember(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/signup/owners")
    public ResponseEntity<ApiResponse<SignupResponse>> signupOwner(@Valid @RequestBody SignupOwnerRequest request) {
        SignupResponse response = authService.signupOwner(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<ReissueResponse>> reissue(@Valid @RequestBody ReissueRequest request) {
        ReissueResponse response = authService.reissue(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<LogoutResponse>> logout(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestHeader("Authorization") String authorization
    ) {
        String accessToken = authorization.substring(BEARER_PREFIX.length());
        LogoutResponse response = authService.logout(authMember.id(), accessToken);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
