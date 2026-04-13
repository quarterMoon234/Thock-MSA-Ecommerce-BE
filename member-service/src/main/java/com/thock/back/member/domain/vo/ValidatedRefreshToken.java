package com.thock.back.member.domain.vo;

import com.thock.back.member.domain.entity.Member;

/**
 * 검증이 완료된 RefreshToken과 해당 Member를 함께 관리하는 Value Object
 * - RefreshTokenValidator의 검증 결과
 * - 검증된 데이터만 포함
 **/

public record ValidatedRefreshToken(
        String refreshTokenValue,
        String jti,
        Member member
) {
    public ValidatedRefreshToken {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw new IllegalArgumentException("Refresh token value must not be blank");
        }
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("Refresh token jti must not be blank");
        }
        if (member == null) {
            throw new IllegalArgumentException("Member must not be null");
        }
    }
}
