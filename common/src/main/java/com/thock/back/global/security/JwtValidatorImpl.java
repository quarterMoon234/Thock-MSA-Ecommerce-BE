package com.thock.back.global.security;

import com.thock.back.shared.member.domain.MemberRole;
import com.thock.back.shared.member.domain.MemberState;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Component
public class JwtValidatorImpl implements JwtValidator {

    protected final SecretKey key;
    private final String issuer;

    public JwtValidatorImpl(JwtProperties props) {
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.issuer = props.issuer();
    }

    @Override
    public boolean validate(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public Long extractMemberId(String token) {
        return Long.valueOf(getClaims(token).getSubject());
    }

    @Override
    public MemberRole extractRole(String token) {
        return MemberRole.valueOf(getClaims(token).get("role", String.class));
    }

    @Override
    public MemberState extractState(String token) {
        return MemberState.valueOf(getClaims(token).get("state", String.class));
    }

    // 토큰에서 Claims 추출 (검증 포함)
    private Claims getClaims(String token) {
        return parseClaims(token).getPayload();
    }

    private io.jsonwebtoken.Jws<Claims> parseClaims(String token) {
        var parserBuilder = Jwts.parser()
                .verifyWith(key);

        if (issuer != null && !issuer.isBlank()) {
            parserBuilder.requireIssuer(issuer);
        }

        return parserBuilder
                .build()
                .parseSignedClaims(token);
    }
}
