package com.thock.back.member.domain.service;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.member.domain.entity.Member;
import com.thock.back.member.domain.vo.ValidatedRefreshToken;
import com.thock.back.member.out.MemberRepository;
import com.thock.back.member.out.RefreshSessionStore;
import com.thock.back.member.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RefreshToken 검증을 담당하는 Domain Service
 * 책임:
 * - RefreshToken 존재 여부 확인
 * - 토큰 상태 검증 (폐기, 만료)
 * - JWT 서명 검증
 * - MemberId 일치 검증
 * - 회원 상태 검증
 * 위치 이유:
 * - 복잡한 검증 로직을 캡슐화
 * - RefreshToken, Member 엔티티와 JWT 검증을 조합
 **/

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenValidator {

    private final JwtTokenProvider jwtTokenProvider;
    private final MemberRepository memberRepository;
    private final RefreshSessionStore refreshSessionStore;

    /**
     * RefreshToken을 종합적으로 검증
     *
     * @param refreshTokenValue = 평문 RefreshToken
     * @return 검증된 토큰과 회원 정보
     * @throws CustomException 검증 실패 시
     **/
    public ValidatedRefreshToken validate(String refreshTokenValue) {
        validateJwtSignature(refreshTokenValue);
        validateRefreshTokenType(refreshTokenValue);

        String jti = extractJti(refreshTokenValue);
        Long memberId = validateSession(refreshTokenValue, jti);
        Member member = findAndValidateMember(memberId);

        log.info("[AUTH] RefreshToken validated successfully. memberId={}, jti={}", member.getId(), jti);

        return new ValidatedRefreshToken(refreshTokenValue, jti, member);
    }

    private void validateJwtSignature(String refreshTokenValue) {
        if (!jwtTokenProvider.validate(refreshTokenValue)) {
            log.warn("[SECURITY] Invalid RefreshToken JWT signature");
            throw new CustomException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    private Member findAndValidateMember(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            log.warn("[Security] Withdrawn member tried to refresh token. memberId={}", member.getId());
            throw new CustomException(ErrorCode.MEMBER_WITHDRAWN);
        }

        if (member.isInActive()) {
            log.warn("[Security] Inactive member tried to refresh token. memberId={}", member.getId());
            throw new CustomException(ErrorCode.MEMBER_INACTIVE);
        }

        return member;
    }

    private void validateRefreshTokenType(String refreshTokenValue) {
        String tokenType = jwtTokenProvider.extractTokenType(refreshTokenValue);
        if (!"refresh".equals(tokenType)) {
            log.warn("[SECURITY] Invalid token type for refresh token. type={}", tokenType);
            throw new CustomException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    private Long validateSession(String refreshTokenValue, String jti) {
        return refreshSessionStore.findActiveMemberId(jti)
                .orElseGet(() -> handleInactiveSession(refreshTokenValue, jti));
    }

    private String extractJti(String refreshTokenValue) {
        String jti = jwtTokenProvider.extractJti(refreshTokenValue);
        if (jti == null || jti.isBlank()) {
            log.warn("[SECURITY] Missing jti in refresh token");
            throw new CustomException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        return jti;
    }

    private Long handleInactiveSession(String refreshTokenValue, String jti) {
        Long memberId = jwtTokenProvider.extractMemberId(refreshTokenValue);

        if (refreshSessionStore.isRotated(jti)) {
            log.warn("[SECURITY] Reused rotated refresh token detected. memberId={}, jti={}", memberId, jti);
            refreshSessionStore.revokeAll(memberId);
            throw new CustomException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }

        log.warn("[SECURITY] Refresh token not found in active session store. memberId={}, jti={}", memberId, jti);
        throw new CustomException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }
}
