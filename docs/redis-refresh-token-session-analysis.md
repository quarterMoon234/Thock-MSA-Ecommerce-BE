# Redis Refresh Token Session Analysis

## 1. 배경

기존 `member-service`는 Refresh Token을 DB 테이블(`member_refresh_tokens`)에 저장하고, 해시 조회 + 폐기(revoke) + 재발급(rotation)으로 관리하고 있었다.

이 구조도 화이트리스트 자체는 가능했지만, 아래 요구를 더 자연스럽게 풀기에는 한계가 있었다.

- logout
- logout-all
- reuse detection
- TTL 기반 세션 만료
- 다중 인스턴스에서 공유 가능한 세션 저장소

그래서 Refresh Token을 비즈니스 영속 데이터보다 **세션 상태**로 보고, Redis 기반 세션 저장소로 전환했다.

핵심 의도는 “DB보다 무조건 빨라서”가 아니라, **세션 무효화와 재사용 탐지를 더 자연스럽게 구현하기 위해서**다.

## 2. 최종 구조

### Refresh Token JWT

Refresh Token에는 아래 claim이 들어간다.

- `type=refresh`
- `jti=<UUID>`

여기서 `jti`는 개별 Refresh 세션 식별자다.

### Redis key 구조

- `auth:refresh:active:{jti}`
  - 현재 유효한 Refresh 세션
  - value: `memberId`
  - ttl: Refresh Token 만료 시간

- `auth:refresh:rotated:{jti}`
  - rotation으로 폐기된 old token marker
  - reuse detection용
  - ttl: Refresh Token 만료 시간

- `auth:refresh:member:{memberId}`
  - 해당 회원의 active `jti` 집합
  - logout-all / 전체 revoke용

## 3. 처리 흐름

### 로그인

1. 회원 인증
2. 기존 Refresh 세션 전부 `revokeAll(memberId)`
3. 새 Access Token 발급
4. 새 Refresh Token 발급 (`jti` 포함)
5. Redis active 세션 저장

현재 구현은 `issueTokens()`에서 `revokeAll(memberId)`를 수행하므로 **single-session** 모델이다.

즉 사용자가 다시 로그인하거나 refresh를 수행하면 이전 세션은 유지되지 않는다.

### Refresh Token 재발급

1. JWT 서명 검증
2. `type=refresh` 검증
3. `jti` 추출
4. Redis active 세션 존재 여부 확인
5. 회원 상태 검증
6. 기존 `jti` revoke
7. 기존 `jti`를 rotated marker로 저장
8. 새 토큰 쌍 발급

### Logout

1. refresh token payload 검증
2. `jti` 추출
3. 해당 active 세션 revoke

### Logout All

1. refresh token payload 검증
2. `memberId` 추출
3. 회원의 모든 active 세션 revoke

### Reuse Detection

refresh 요청 시:

- active session이 있으면 정상
- active는 없고 rotated marker가 있으면 **이미 rotation으로 폐기된 토큰 재사용**으로 판단
- 이 경우 `revokeAll(memberId)` 수행 후 `REFRESH_TOKEN_REVOKED`

즉 old refresh token을 탈취해서 다시 쓰는 시도를 감지할 수 있다.

## 4. 왜 Redis를 사용했는가

DB로도 Refresh Token 화이트리스트는 구현 가능하다.

다만 이번 구조에서 Redis를 선택한 이유는 아래와 같다.

- Refresh Token은 주문/결제 같은 영속 비즈니스 데이터가 아니라 **세션 상태**에 가깝다
- TTL이 있는 상태를 관리하기 좋다
- revoke / logout / logout-all / reuse detection을 key 기반으로 다루기 쉽다
- 다중 인스턴스에서도 중앙 세션 저장소로 쓰기 쉽다

즉 이번 Redis 도입의 핵심 가치는 **세션 제어 모델**이지, 단순 성능 향상 하나가 아니다.

## 5. Redis 유실 리스크와 대응

이번 구현에서 Refresh 세션의 source of truth는 Redis다.

따라서:

- **애플리케이션 서버만 재시작되는 경우**
  - Redis가 살아 있으면 세션 유지

- **Redis 데이터가 유실되는 경우**
  - active refresh session이 사라짐
  - 사용자는 다음 refresh 시점에 세션이 없다고 판단됨
  - 결과적으로 **재로그인 필요**

다만 이미 발급된 Access Token은 stateless JWT이므로, 만료 전까지는 즉시 끊기지 않는다.

즉 Redis 유실은 주문/결제 정합성 사고가 아니라 **세션 유실 장애**다.

### 이번 대응

`docker-compose`에서 Redis에 아래를 적용했다.

- `appendonly yes`
- `appendfsync everysec`
- `redis-data` volume mount

의미:

- AOF로 메모리 데이터를 디스크에 기록
- volume으로 디스크 파일을 컨테이너 재생성 이후에도 유지

즉 Redis 재시작 시 세션 유실 가능성을 낮췄다.

완전 무손실은 아니다.

- `appendfsync everysec` 기준 최근 1초 이내 쓰기 정도는 유실 가능성이 있다.

## 6. AOF / volume에 대한 이해

- `persistence`
  - Redis 메모리 데이터를 디스크 파일로 기록하는 기능
  - 예: AOF, RDB

- `volume`
  - 그 디스크 파일을 컨테이너 밖에 유지하는 장치

둘은 역할이 다르며, **같이 있어야 재시작 후 복구에 의미가 있다.**

현재 용도에서는 `AOF + volume`이 적절하다.

이유:

- Refresh 세션은 영속 비즈니스 데이터까지는 아니지만, 자주 통째로 날아가면 사용자 경험이 나빠진다
- RDB보다 AOF가 더 최근 쓰기 보존에 유리하다

## 7. Active 세션 1개와 AOF 용량

현재 구조는 single-session이라 사용자당 active Refresh 세션은 사실상 1개다.

다만 완전히 “사용자당 1개라서 안전”한 구조는 아니다.

이유:

- rotation 시 old token `jti`를 `rotated` marker로 남긴다
- 이 marker는 TTL 동안 유지된다
- 따라서 사용자가 자주 refresh하면 rotated marker가 일정 기간 누적될 수 있다

하지만:

- 모든 key는 TTL 기반이다
- AOF는 rewrite로 압축될 수 있다

즉 현재 규모에서는 감당 가능하되, **무제한 공짜 구조는 아니다.**

## 8. Refresh Token 만료 2주의 의미

현재 설정:

- Access Token: 1800초 (30분)
- Refresh Token: 1209600초 (14일)

이 구조는 Access Token과 Refresh Token의 생명주기가 같은 것이 아니다.

refresh가 일어날 때마다:

- 기존 Refresh Token은 폐기
- 새 2주짜리 Refresh Token 발급

즉 현재 구조는 **슬라이딩 세션**에 가깝다.

### 절대 만료 vs 슬라이딩 만료

- 절대 만료
  - 사용자가 계속 활동해도 특정 시점이 되면 무조건 재로그인

- 슬라이딩 만료
  - 활동할 때마다 세션 만료가 뒤로 밀림

현재 구현은 절대 만료가 없는 슬라이딩 세션 구조다.

보안 관점에서는 절대 만료가 있는 편이 더 낫지만, UX는 다소 나빠진다.

실무에서는 보통:

- 짧은 Access Token
- Refresh Token rotation
- 슬라이딩 만료
- 절대 만료

를 같이 두는 경우가 많다.

이번 범위에서는 절대 만료까지는 구현하지 않았다.

## 9. 검증

최종 검증 결과:

- `./gradlew :member-service:compileJava` 통과
- `./gradlew :member-service:test` 통과

추가한 핵심 테스트:

- `AuthApplicationServiceTest`
  - refresh 시 revoke + rotated marker + 새 토큰 발급
  - logout 시 current session revoke
  - logout-all 시 member 전체 session revoke

- `RefreshTokenValidatorTest`
  - active session 검증 성공
  - rotated token 재사용 시 `revokeAll`
  - active/rotated 모두 없으면 not found

- `TokenIssuerTest`
  - 새 토큰 발급 시 기존 세션 revokeAll
  - 새 active session 저장

테스트 프로필은 H2 메모리 DB로 바꿔 외부 MySQL 없이도 `member-service:test` 전체가 통과하도록 정리했다.

## 10. 이번 구현의 의미

이번 작업은 단순히 DB 저장소를 Redis로 바꾼 것이 아니다.

핵심은:

- Redis 기반 Refresh Token whitelist
- rotation
- logout
- logout-all
- reuse detection
- 세션 유실 리스크에 대한 persistence 대응

까지 포함해, **Refresh Token을 진짜 세션 저장소로 운영할 수 있는 구조로 끌어올린 것**이다.

## 11. 남은 개선 여지

- 절대 만료 추가
- multi-session 지원
- device / user-agent / ip 메타데이터
- Redis HA (replica/sentinel/managed redis)
- reuse detection 시 알림/감사 로그 강화

현재 이력서/포트폴리오 범위에서는 여기까지면 충분하다.
