package com.thock.back.member.out;

import java.time.Duration;
import java.util.Optional;

public interface RefreshSessionStore {

    // 현재 유효한 refresh 세션 등록
    void saveActive(Long memberId, String jti, Duration ttl);

    // active whitelist 조회
    Optional<Long> findActiveMemberId(String jti);

    // 재발급으로 폐기된 old token marker 저장
    void markRotated(Long memberId, String jti, Duration ttl);

    // reuse detection용 확인
    boolean isRotated(String jti);

    // logout/current session revoke
    void revoke(String jti);

    // reuse detection 또는 logout-all
    void revokeAll(Long memberId);
}
