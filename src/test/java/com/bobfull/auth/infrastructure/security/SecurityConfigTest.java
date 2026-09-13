package com.bobfull.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * SecurityConfig가 제공하는 PasswordEncoder의 해시·매칭 동작을 검증한다.
 */
class SecurityConfigTest {

    private final PasswordEncoder passwordEncoder = new SecurityConfig().passwordEncoder();

    @Test
    void 비밀번호를_인코딩하면_원본과_다른_값이_저장된다() {
        // when
        String encoded = passwordEncoder.encode("Password123!");

        // then
        assertThat(encoded).isNotEqualTo("Password123!");
    }

    @Test
    void 인코딩된_비밀번호는_원본_비밀번호와_매칭에_성공한다() {
        // given
        String encoded = passwordEncoder.encode("Password123!");

        // when
        boolean matches = passwordEncoder.matches("Password123!", encoded);

        // then
        assertThat(matches).isTrue();
    }

    @Test
    void 다른_비밀번호는_매칭에_실패한다() {
        // given
        String encoded = passwordEncoder.encode("Password123!");

        // when
        boolean matches = passwordEncoder.matches("WrongPassword!", encoded);

        // then
        assertThat(matches).isFalse();
    }
}
