package com.thock.back.member.domain.service;

import com.thock.back.global.exception.CustomException;
import com.thock.back.global.exception.ErrorCode;
import com.thock.back.member.domain.entity.Member;
import com.thock.back.member.domain.vo.ValidatedRefreshToken;
import com.thock.back.member.out.MemberRepository;
import com.thock.back.member.out.RefreshSessionStore;
import com.thock.back.member.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenValidatorTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private RefreshSessionStore refreshSessionStore;

    @InjectMocks
    private RefreshTokenValidator refreshTokenValidator;

    @Test
    void validate_returnsValidatedRefreshTokenWhenSessionIsActive() {
        Member member = activeMember(1L);

        when(jwtTokenProvider.validate("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.extractTokenType("refresh-token")).thenReturn("refresh");
        when(jwtTokenProvider.extractJti("refresh-token")).thenReturn("refresh-jti");
        when(refreshSessionStore.findActiveMemberId("refresh-jti")).thenReturn(Optional.of(1L));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        ValidatedRefreshToken validated = refreshTokenValidator.validate("refresh-token");

        assertThat(validated.refreshTokenValue()).isEqualTo("refresh-token");
        assertThat(validated.jti()).isEqualTo("refresh-jti");
        assertThat(validated.member()).isSameAs(member);
    }

    @Test
    void validate_whenRotatedTokenIsReused_revokesAllSessionsAndThrows() {
        when(jwtTokenProvider.validate("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.extractTokenType("refresh-token")).thenReturn("refresh");
        when(jwtTokenProvider.extractJti("refresh-token")).thenReturn("refresh-jti");
        when(refreshSessionStore.findActiveMemberId("refresh-jti")).thenReturn(Optional.empty());
        when(jwtTokenProvider.extractMemberId("refresh-token")).thenReturn(5L);
        when(refreshSessionStore.isRotated("refresh-jti")).thenReturn(true);

        assertThatThrownBy(() -> refreshTokenValidator.validate("refresh-token"))
                .isInstanceOf(CustomException.class)
                .extracting(ex -> ((CustomException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_REVOKED);

        verify(refreshSessionStore).revokeAll(5L);
    }

    @Test
    void validate_whenSessionDoesNotExist_throwsNotFound() {
        when(jwtTokenProvider.validate("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.extractTokenType("refresh-token")).thenReturn("refresh");
        when(jwtTokenProvider.extractJti("refresh-token")).thenReturn("refresh-jti");
        when(refreshSessionStore.findActiveMemberId("refresh-jti")).thenReturn(Optional.empty());
        when(jwtTokenProvider.extractMemberId("refresh-token")).thenReturn(9L);
        when(refreshSessionStore.isRotated("refresh-jti")).thenReturn(false);

        assertThatThrownBy(() -> refreshTokenValidator.validate("refresh-token"))
                .isInstanceOf(CustomException.class)
                .extracting(ex -> ((CustomException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }

    private Member activeMember(Long id) {
        Member member = Member.signUp("user@test.com", "tester");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
