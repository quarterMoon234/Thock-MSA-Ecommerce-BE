# Market Circuit Breaker Before/After Experiment Analysis

## 1. 실험 목적

이 실험은 주문 생성 경로에서 결제 서비스 장애가 전파될 때, `Circuit Breaker` 적용 전후가 어떻게 달라지는지 비교하기 위해 설계했다.

- BEFORE: `Circuit Breaker` 비활성화
- AFTER: `Circuit Breaker` 활성화

검증하고자 한 핵심은 세 가지다.

- 결제 서비스 장애 시 후속 주문 요청이 계속 느리게 실패하는가
- `Circuit Breaker`가 열린 뒤 후속 요청을 빠르게 차단하는가
- 결제 서비스 복구 후 `HALF_OPEN -> CLOSED`로 수렴하며 주문 생성이 정상 복구되는가

즉 이 실험은 단순한 주문 API 성능 비교가 아니라, **외부 결제 의존 장애 상황에서 주문 경로의 실패 응답시간과 복구 동작이 어떻게 달라지는지**를 검증하는 실험이다.

## 2. 비교 대상

### BEFORE

- `payment-wallet-client`에 대한 `Circuit Breaker` 비활성화
- 결제 서비스 장애 시 모든 주문 요청이 실제 외부 호출까지 시도됨
- 후속 요청도 계속 connect/read timeout 영향을 받음

### AFTER

- `payment-wallet-client`에 대한 `Circuit Breaker` 활성화
- 실패율이 threshold를 넘으면 `OPEN`
- `OPEN` 상태에서는 `CALL_NOT_PERMITTED`로 후속 요청을 빠르게 차단
- `waitDurationInOpenState` 후 `HALF_OPEN`
- 복구가 확인되면 `CLOSED`

## 3. 최종 사용 설정

초기에는 "1회 실패 즉시 OPEN" 되는 공격적인 실험 설정으로 하네스를 검증했다. 다만 이 값은 실무 기준으로 과도하게 공격적이어서 최종 결과에는 사용하지 않았다.

최종 run `1776253192`에는 아래 설정을 사용했다.

- `slidingWindowSize = 20`
- `minimumNumberOfCalls = 10`
- `failureRateThreshold = 50`
- `waitDurationInOpenState = 10s`
- `permittedNumberOfCallsInHalfOpenState = 1`
- `feign connectTimeout = 700ms`
- `feign readTimeout = 1200ms`
- 장애 요청 수: `12회`

이 설정을 쓴 이유는 아래와 같다.

- 일시적 단건 실패로 즉시 `OPEN` 되는 과도한 오탐을 피한다
- 일정 수(`10회`) 이상의 호출이 쌓인 뒤 실패율을 기준으로 열린다
- `OPEN` 상태에서 충분히 기다린 뒤 `HALF_OPEN`으로 진입한다
- `HALF_OPEN`에서는 1건만 먼저 통과시켜 복구 여부를 보수적으로 확인한다

즉 이 실험은 "무조건 빨리 열리는 데모 설정"이 아니라, **실무에서 설명 가능한 threshold 기반 차단 설정**으로 돌린 값이다.

### 왜 "1회 실패 즉시 OPEN"이 실무에서 부적절한가

초기 하네스는 `minimumNumberOfCalls = 1`, `slidingWindowSize = 1`에 가까운 형태로도 검증했다. 이 방식은 "서킷 브레이커가 열린다"는 기능 증명에는 좋지만, 실무 설정으로는 부적절하다.

이유는 아래와 같다.

- 일시적 네트워크 흔들림이나 단건 timeout에도 바로 `OPEN` 되어 **오탐**이 많아진다
- 장애가 아닌 짧은 transient failure에도 후속 정상 요청까지 차단해 **가용성 손실**이 커진다
- 실패율을 누적해 판단하지 않으므로 "진짜 장애"와 "우연한 1회 실패"를 구분하지 못한다
- 면접이나 실무 리뷰에서 "왜 단건 실패만으로 전체 경로를 차단하느냐"는 질문에 방어가 어렵다

즉 `1회 실패 즉시 OPEN`은 데모용으로는 단순하지만, 운영 관점에서는 **민감도는 높고 신뢰도는 낮은 설정**이다.

### 이번에 실무형으로 어떻게 고쳤는가

이번 최종 실험에서는 아래처럼 판단 기준을 완화했다.

- `minimumNumberOfCalls = 10`
  - 최소 10건은 보고 난 뒤 판단
- `failureRateThreshold = 50`
  - 절반 이상이 실패해야 연다
- `slidingWindowSize = 20`
  - 짧은 단발성 실패가 아니라 일정 구간의 실패율을 본다
- `waitDurationInOpenState = 10s`
  - 장애가 잠깐 회복될 시간을 주고 재시도
- `permittedNumberOfCallsInHalfOpenState = 1`
  - 복구 확인은 보수적으로 1건만 통과시켜 판단

이렇게 바꾸면:

- transient failure에는 덜 민감해지고
- 실제 장애에는 충분히 빠르게 열리며
- 복구도 보수적으로 확인할 수 있다

즉 이번 설정은 "무조건 빨리 열리는 값"이 아니라, **오탐과 장애 전파 완화 사이의 균형을 잡은 값**이다.

## 4. 다른 방법과 왜 Circuit Breaker가 적절했는가

주문 결제 경로에서 고려할 수 있는 대안은 여러 가지가 있다.

### 1. Timeout만 줄이는 방법

- 장점: 가장 단순하다
- 한계: 장애 시 느린 실패는 줄일 수 있어도, **후속 요청이 계속 외부 호출을 시도하는 구조**는 그대로다

즉 timeout만으로는 fast-fail을 만들 수 없다.

### 2. Retry

- 장점: 일시적 실패에는 유효할 수 있다
- 한계: 외부 서비스가 실제로 죽어 있을 때는 재시도가 오히려 호출 수를 늘려 **장애를 증폭**시킬 수 있다

특히 주문 결제처럼 동기 정합성이 중요한 경로에서 무분별한 retry는 오히려 더 위험하다.

### 3. Bulkhead / Thread Pool 격리

- 장점: 외부 의존 장애가 스레드 풀 전체를 잠식하는 문제를 줄일 수 있다
- 한계: **후속 요청을 빠르게 차단하는 판단 자체**는 하지 못한다

즉 자원 격리에는 좋지만, failure propagation 자체를 끊는 데는 불충분하다.

### 4. Fallback 응답

- 장점: 사용자에게 대체 응답을 줄 수 있다
- 한계: 주문 결제 경로는 정합성이 중요해서 "대체 성공 응답"을 주기 어렵다

예를 들어 결제 확인이 안 된 상태에서 주문을 성공처럼 응답하는 것은 잘못된 설계다.

### 왜 이 경로에서는 Circuit Breaker가 적절했는가

이 주문 경로는:

- 결제 확인 때문에 동기 호출을 유지해야 하고
- fallback으로 성공 응답을 대체할 수 없으며
- retry는 장애를 악화시킬 수 있고
- timeout만으로는 후속 요청이 계속 느리게 실패한다

따라서 이 경로에서 가장 적절한 선택은:

- **일정 수준 이상 실패가 누적되면 외부 호출 자체를 끊고**
- **후속 요청을 빠르게 실패시키며**
- **복구 후 자동으로 재개할 수 있는**

`Circuit Breaker`였다.

즉 "모든 상황에서 Circuit Breaker가 최선"이 아니라, **정합성 때문에 동기 호출을 유지해야 하는 주문-결제 경로에서는 Circuit Breaker가 가장 설명 가능한 선택**이었다고 보는 게 정확하다.

## 5. 테스트 조건

공통 조건은 아래와 같다.

- 입력 경로: `POST /api/v1/orders`
- 실험용 상품: `3개`
- 각 주문은 장바구니에 담긴 상품 `3개`를 한 번에 주문
- 장애 요청 수: `12회`
- 복구 요청 수: 최대 `4회`
- 비교 방식: 동일 조건의 `BEFORE / AFTER`

실험 흐름은 아래와 같다.

1. `product-service`를 `experiment` 프로필로 재기동
2. 실험용 상품 3개 생성
3. `market-service`의 `CartProductView`를 실험 상품 기준으로 동기화
4. 신규 구매자 회원가입 / 로그인
5. 장바구니에 실험 상품 3개 추가
6. `payment-service` 중단
7. 주문 요청 `12회` 전송
8. AFTER에서는 `payment-service` 복구 후 recovery 요청 수행
9. market 로그와 응답시간을 기반으로 요약 JSON 생성

## 6. 부하는 어떻게 주었는가

이번 실험은 `k6`로 지속적인 HTTP 부하를 주는 구조가 아니라, wrapper script가 **장애 상황을 재현하고 고정된 횟수의 주문 요청을 순차적으로 보내는 형태**다.

실행 스크립트:

- `loadtest/run-market-circuit-breaker-before-after-experiment.sh`

핵심은:

- 같은 주문 페이로드를 사용해
- 장애 상태에서 연속 요청을 보내고
- threshold 이전/이후 응답시간과 상태 전이를 보는 것

즉 이 실험은 "TPS 최대치"를 보는 부하 실험이 아니라, **장애 전파와 빠른 실패(fast-fail) 동작을 비교하는 실패 시나리오 실험**이다.

## 7. 측정 기준

핵심 지표는 아래다.

- `failureRequests`
- `failureAvgTimeMs`
- `followupFailureAvgTimeMs`
- `postThresholdFailureAvgTimeMs`
- `callNotPermittedCount`
- `openTransitionCount`
- `halfOpenTransitionCount`
- `closedTransitionCount`
- `recoveryRequests`

### 왜 `postThresholdFailureAvgTimeMs`를 보나

최종 설정에서는 `minimumNumberOfCalls = 10`이다.

즉 첫 10건은 `Circuit Breaker`가 아직 열리지 않은 상태에서 실패율을 누적하는 구간이고, **실제 fast-fail 효과는 threshold를 넘긴 뒤 요청들**에서 드러난다.

그래서 이번 실험의 핵심 비교값은:

- BEFORE: 11~12번째 실패 요청 평균
- AFTER: 11~12번째 실패 요청 평균

인 `postThresholdFailureAvgTimeMs`다.

### 응답시간 기준

응답시간은 사용자 기준의 실제 HTTP 응답시간이다.

- 주문 요청 전송 시작
- API Gateway
- market-service 주문 처리
- payment-service 호출 시도 또는 `CALL_NOT_PERMITTED`
- 최종 HTTP 응답 반환

까지 포함한다.

## 8. recovery 결과에 `409`가 포함되는 이유

AFTER의 recovery 요청은 아래처럼 해석해야 한다.

- 첫 recovery 요청 `201`
  - 결제 서비스 복구 후 주문 생성이 다시 성공했다는 뜻
- 이후 recovery 요청 `409`
  - 이미 결제 대기 주문이 생성돼 있어 동일 장바구니로 추가 주문이 막힌 정상 결과

즉 `409`는 실패가 아니라, **복구 후 비즈니스 규칙이 다시 정상 적용됐다는 증거**다.

## 9. 최종 결과

기준 run:

- `1776253192`

결과:

| 항목 | BEFORE | AFTER | 개선율 |
|---|---:|---:|---:|
| threshold 이후 실패 응답시간 | 421.329ms | 29.1415ms | 93.08% |
| `CALL_NOT_PERMITTED` | 0 | 2 | - |
| `OPEN` 전이 | 0 | 2 | - |
| `HALF_OPEN` 전이 | 0 | 2 | - |
| `CLOSED` 전이 | 0 | 2 | - |
| recovery 성공 | 없음 | 첫 요청 `201` | - |

validation 결과:

- `beforePostThresholdFailuresSlow = true`
- `afterOpenBlockedFast = true`
- `afterRecovered = true`

즉 실무형 threshold 기준에서도:

- BEFORE는 결제 장애가 계속 느리게 전파됐고
- AFTER는 threshold를 넘긴 뒤 fast-fail로 차단됐으며
- 결제 서비스 복구 후 `CLOSED`로 돌아오며 주문 생성이 다시 성공했다

고 해석할 수 있다.

## 10. 면접에서 설명할 때의 핵심 문장

짧게 설명하면 아래 정도가 가장 정확하다.

> 주문 서비스에서 결제 서비스 장애가 전파되는 상황을 재현해 `Circuit Breaker` 전후를 비교했습니다. 실무형 threshold(`minimumNumberOfCalls=10`, `failureRateThreshold=50`) 기준으로, threshold 이후 실패 응답시간을 `421.329ms -> 29.1415ms`로 93.08% 줄였고, `OPEN -> HALF_OPEN -> CLOSED` 전이와 복구 후 주문 생성 성공을 함께 확인했습니다.

## 11. 이 실험이 증명하는 것과 아닌 것

### 증명하는 것

- 외부 결제 장애가 후속 주문 요청에 계속 느리게 전파되는 문제를 줄일 수 있음
- `Circuit Breaker`가 열린 뒤 후속 요청을 빠르게 차단함
- 복구 후 `HALF_OPEN -> CLOSED`로 수렴함

### 증명하지 않는 것

- 주문 경로 전체의 최대 TPS
- 결제 서비스 자체의 성능
- 모든 종류의 장애에서 동일하게 동작함을 일반화하는 것

즉 이번 실험은 **failure propagation control**을 검증한 실험이지, 전체 주문 시스템의 종합 성능 실험은 아니다.
