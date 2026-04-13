package com.thock.back.member.app;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.member.domain.entity.Member;
import com.thock.back.member.domain.service.MemberAuthenticator;
import com.thock.back.member.domain.service.RefreshTokenValidator;
import com.thock.back.member.domain.vo.TokenPair;
import com.thock.back.member.domain.vo.ValidatedRefreshToken;
import com.thock.back.member.in.dto.AuthenticationResult;
import com.thock.back.member.infrastructure.history.LoginHistoryRecorder;
import com.thock.back.member.infrastructure.token.TokenIssuer;
import com.thock.back.member.out.MemberRepository;
import com.thock.back.member.out.RefreshSessionStore;
import com.thock.back.member.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthApplicationServiceTest {

    @Mock
    private MemberAuthenticator memberAuthenticator;
    @Mock
    private RefreshTokenValidator refreshTokenValidator;
    @Mock
    private TokenIssuer tokenIssuer;
    @Mock
    private LoginHistoryRecorder loginHistoryRecorder;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private RefreshSessionStore refreshSessionStore;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private AuthApplicationService authApplicationService;

    @Test
    void refreshAccessToken_rotatesCurrentSessionAndIssuesNewTokens() {
        Member member = activeMember(1L);
        ValidatedRefreshToken validated = new ValidatedRefreshToken("old-refresh", "old-jti", member);
        TokenPair tokenPair = new TokenPair("new-access", "new-refresh");

        when(refreshTokenValidator.validate("old-refresh")).thenReturn(validated);
        when(jwtTokenProvider.getRefreshTokenExpSeconds()).thenReturn(1209600L);
        when(tokenIssuer.issueTokens(member)).thenReturn(tokenPair);

        AuthenticationResult result = authApplicationService.refreshAccessToken("old-refresh");

        verify(refreshSessionStore).revoke("old-jti");
        verify(refreshSessionStore).markRotated(1L, "old-jti", Duration.ofSeconds(1209600L));
        verify(tokenIssuer).issueTokens(member);
        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void logout_revokesCurrentRefreshSession() {
        when(jwtTokenProvider.validate("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.extractTokenType("refresh-token")).thenReturn("refresh");
        when(jwtTokenProvider.extractJti("refresh-token")).thenReturn("refresh-jti");

        authApplicationService.logout("refresh-token");

        verify(refreshSessionStore).revoke("refresh-jti");
    }

    @Test
    void logout_withInvalidToken_throwsAndDoesNotRevoke() {
        when(jwtTokenProvider.validate("broken-token")).thenReturn(false);

        assertThatThrownBy(() -> authApplicationService.logout("broken-token"))
                .isInstanceOf(CustomException.class)
                .extracting(ex -> ((CustomException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);

        verify(refreshSessionStore, never()).revoke("broken-token");
    }

    @Test
    void logoutAll_revokesAllSessionsForMember() {
        when(jwtTokenProvider.validate("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.extractTokenType("refresh-token")).thenReturn("refresh");
        when(jwtTokenProvider.extractJti("refresh-token")).thenReturn("refresh-jti");
        when(jwtTokenProvider.extractMemberId("refresh-token")).thenReturn(7L);

        authApplicationService.logoutAll("refresh-token");

        verify(refreshSessionStore).revokeAll(7L);
    }

    private Member activeMember(Long id) {
        Member member = Member.signUp("user@test.com", "tester");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
