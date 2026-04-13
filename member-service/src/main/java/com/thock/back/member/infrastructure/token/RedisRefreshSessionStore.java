package com.thock.back.member.infrastructure.token;

import com.thock.back.member.out.RefreshSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisRefreshSessionStore implements RefreshSessionStore {

    private static final String ACTIVE_KEY_PREFIX = "auth:refresh:active:";
    private static final String ROTATED_KEY_PREFIX = "auth:refresh:rotated:";
    private static final String MEMBER_KEY_PREFIX = "auth:refresh:member:";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void saveActive(Long memberId, String jti, Duration ttl) {
        String activeKey = activeKey(jti);
        String memberKey = memberKey(memberId);

        stringRedisTemplate.opsForValue().set(activeKey, String.valueOf(memberId), ttl);
        stringRedisTemplate.opsForSet().add(memberKey, jti);
        stringRedisTemplate.expire(memberKey, ttl);
    }

    @Override
    public Optional<Long> findActiveMemberId(String jti) {
        String value = stringRedisTemplate.opsForValue().get(activeKey(jti));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Long.valueOf(value));
    }

    @Override
    public void markRotated(Long memberId, String jti, Duration ttl) {
        stringRedisTemplate.opsForValue().set(rotatedKey(jti), String.valueOf(memberId), ttl);
    }

    @Override
    public boolean isRotated(String jti) {
        Boolean exists = stringRedisTemplate.hasKey(rotatedKey(jti));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void revoke(String jti) {
        Optional<Long> memberId = findActiveMemberId(jti);
        memberId.ifPresent(id -> stringRedisTemplate.opsForSet().remove(memberKey(id), jti));
        stringRedisTemplate.delete(activeKey(jti));
    }

    @Override
    public void revokeAll(Long memberId) {
        String memberKey = memberKey(memberId);
        Set<String> jtIs = stringRedisTemplate.opsForSet().members(memberKey);
        if (jtIs != null && !jtIs.isEmpty()) {
            String[] activeKeys = jtIs.stream()
                    .map(this::activeKey)
                    .toArray(String[]::new);
            stringRedisTemplate.delete(Set.of(activeKeys));
        }
        stringRedisTemplate.delete(memberKey);
    }

    private String activeKey(String jti) {
        return ACTIVE_KEY_PREFIX + jti;
    }

    private String rotatedKey(String jti) {
        return ROTATED_KEY_PREFIX + jti;
    }

    private String memberKey(Long memberId) {
        return MEMBER_KEY_PREFIX + memberId;
    }

}
