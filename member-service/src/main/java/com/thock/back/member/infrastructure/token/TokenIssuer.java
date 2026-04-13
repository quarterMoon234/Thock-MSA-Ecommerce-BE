package com.thock.back.member.infrastructure.token;

import com.thock.back.member.domain.entity.Member;
import com.thock.back.member.domain.vo.TokenPair;
import com.thock.back.member.out.RefreshSessionStore;
import com.thock.back.member.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * JWT 토큰 발급을 담당하는 Infrastructure Service
 * 책임:
 * - AccessToken 생성
 * - RefreshToken 생성 및 Redis 세션 저장
 * - 기존 Refresh 세션 폐기
 * 위치 이유:
 * - 외부 시스템(JWT 라이브러리, DB)과 상호작용하는 인프라 로직
 * - 도메인 로직이 아닌 기술적 구현
 **/

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenIssuer {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshSessionStore refreshSessionStore;

    /**
     * 회원에게 새로운 토큰 쌍을 발급
     *
     * @param member = 토큰을 발급받을 회원
     * @return AccessToken과 RefreshToken 쌍
     **/
    @Transactional
    public TokenPair issueTokens(Member member) {
        // 기존 RefreshToken 폐기
        revokeExistingTokens(member.getId());

        // AccessToken 생성
        String accessToken = jwtTokenProvider.createAccessToken(
                member.getId(),
                member.getRole(),
                member.getState()
        );

        // RefreshToken 생성 및 저장
        String refreshJti = UUID.randomUUID().toString();
        String refreshTokenValue = jwtTokenProvider.createRefreshToken(member.getId(), refreshJti);
        saveRefreshSession(member.getId(), refreshJti);

        log.info("[TOKEN] Tokens issued for member. memberId={}]", member.getId());

        return new TokenPair(accessToken, refreshTokenValue);
    }

    private void revokeExistingTokens(Long memberId) {
        refreshSessionStore.revokeAll(memberId);
        log.info("[TOKEN] Revoked existing refresh sessions for member. memberId={}", memberId);
    }

    private void saveRefreshSession(Long memberId, String jti) {
        refreshSessionStore.saveActive(
                memberId,
                jti,
                Duration.ofSeconds(jwtTokenProvider.getRefreshTokenExpSeconds())
        );
    }
}

