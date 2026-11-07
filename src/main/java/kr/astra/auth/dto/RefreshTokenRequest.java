package kr.astra.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /auth/refresh, POST /auth/logout */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
