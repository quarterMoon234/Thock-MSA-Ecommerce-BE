package com.thock.back.member.infrastructure.token;

import com.thock.back.member.domain.entity.Member;
import com.thock.back.member.domain.vo.TokenPair;
import com.thock.back.member.out.RefreshSessionStore;
import com.thock.back.member.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenIssuerTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private RefreshSessionStore refreshSessionStore;

    @InjectMocks
    private TokenIssuer tokenIssuer;

    @Test
    void issueTokens_revokesExistingSessionsAndStoresNewActiveSession() {
        Member member = activeMember(11L);
        when(jwtTokenProvider.createAccessToken(member.getId(), member.getRole(), member.getState()))
                .thenReturn("access-token");
        when(jwtTokenProvider.createRefreshToken(eq(member.getId()), anyString()))
                .thenReturn("refresh-token");
        when(jwtTokenProvider.getRefreshTokenExpSeconds()).thenReturn(1209600L);

        TokenPair tokenPair = tokenIssuer.issueTokens(member);

        ArgumentCaptor<String> jtiCaptor = ArgumentCaptor.forClass(String.class);
        verify(refreshSessionStore).revokeAll(11L);
        verify(refreshSessionStore).saveActive(eq(11L), jtiCaptor.capture(), eq(Duration.ofSeconds(1209600L)));

        assertThat(jtiCaptor.getValue()).isNotBlank();
        assertThat(tokenPair.accessToken()).isEqualTo("access-token");
        assertThat(tokenPair.refreshToken()).isEqualTo("refresh-token");
    }

    private Member activeMember(Long id) {
        Member member = Member.signUp("user@test.com", "tester");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
