package com.thock.back.member.security;

import com.thock.back.global.security.JwtProperties;
import com.thock.back.global.security.JwtValidatorImpl;
import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.member.domain.MemberState;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
@Primary
public class JwtTokenProvider extends JwtValidatorImpl {

    private final JwtProperties props;

    public JwtTokenProvider(JwtProperties props) {
        super(props);
        this.props = props;
    }

    // AccessToken 생성
    public String createAccessToken(Long memberId, MemberRole role, MemberState state) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(props.accessTokenExpSeconds());

        return Jwts.builder()
                .issuer(props.issuer())
                .subject(String.valueOf(memberId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("role", role.name())
                .claim("state", state.name())
                .signWith(key)
                .compact();
    }

    // RefreshToken 생성
    public String createRefreshToken(Long memberId, String jti) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(props.refreshTokenExpSeconds());

        return Jwts.builder()
                .issuer(props.issuer())
                .subject(String.valueOf(memberId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("type", "refresh")
                .claim("jti", jti)
                .signWith(key)
                .compact();
    }

    public String createRefreshToken(Long memberId) {
        return createRefreshToken(memberId, UUID.randomUUID().toString());
    }

    public String extractJti(String token) {
        Claims claims = getClaims(token);
        Object jti = claims.get("jti");
        return jti == null ? null : String.valueOf(jti);
    }

    public String extractTokenType(String token) {
        Claims claims = getClaims(token);
        Object type = claims.get("type");
        return type == null ? null : String.valueOf(type);
    }

    private Claims getClaims(String token) {
        var parserBuilder = Jwts.parser()
                .verifyWith(key);

        if (props.issuer() != null && !props.issuer().isBlank()) {
            parserBuilder.requireIssuer(props.issuer());
        }

        return parserBuilder
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessTokenExpSeconds() {
        return props.accessTokenExpSeconds();
    }

    public long getRefreshTokenExpSeconds() {
        return props.refreshTokenExpSeconds();
    }
}
