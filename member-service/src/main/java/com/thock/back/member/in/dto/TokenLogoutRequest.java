package com.thock.back.member.in.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenLogoutRequest(
        @NotBlank(message = "refreshToken은 필수입니다.")
        String refreshToken
) {
}
