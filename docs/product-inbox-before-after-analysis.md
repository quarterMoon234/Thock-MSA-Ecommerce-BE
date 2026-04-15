# Product Inbox Before/After Experiment Analysis

## 1. 실험 목적

이 실험은 재고 변경 이벤트 소비 구조를 아래 두 방식으로 비교하기 위해 설계했다.

- BEFORE: 중복 방지 없이 이벤트를 그대로 소비
- AFTER: `Inbox` 기반 idempotency를 적용해 동일 이벤트를 한 번만 소비

검증하고자 한 핵심은 두 가지다.

- 동일 이벤트가 여러 번 들어오면 실제 재고 반영이 중복되는가
- Inbox를 적용하면 중복 메시지가 실제로 차단되는가

즉 이 실험은 단순 소비 성능 비교가 아니라, **Outbox의 at-least-once 발행 이후 남는 duplicate consume 문제를 Inbox가 실제로 해결하는지** 검증하는 correctness 실험이다.

## 2. 왜 Inbox가 필요한가

Outbox 실험에서 broker 장애 후 복구 시:

- direct는 유실이 발생했고
- outbox는 유실 0건으로 수렴했지만
- duplicate publish가 남을 수 있음을 확인했다

즉 Outbox는 **no-loss**를 해결하지만, **no-duplicate**를 해결하지는 않는다.

따라서 소비 측에서는:

- 같은 이벤트가 재전달되더라도
- 비즈니스 로직이 한 번만 적용되도록

중복 소비 차단 장치가 필요하다.

이 역할이 Inbox다.

## 3. 비교 대상

### BEFORE: Inbox 미적용

- 동일한 재고 예약 이벤트가 여러 번 들어오면 매번 비즈니스 처리를 수행
- 중복 메시지가 그대로 재고 예약에 누적 반영됨

### AFTER: Inbox 적용

- `topic + consumerGroup + idempotencyKey`를 기준으로 Inbox claim 수행
- 처음 본 이벤트만 처리
- 이미 처리된 동일 이벤트는 duplicate로 간주하고 skip

## 4. 구현 방식

핵심 구현은 아래 두 가지다.

- **Inbox claim**
  - `product_inbox_event`에 `(topic, consumer_group, idempotency_key)` 유니크 제약을 둠
  - `INSERT IGNORE` 방식으로 최초 처리 여부를 판별

- **비즈니스 처리 + Inbox 기록의 같은 트랜잭션 처리**
  - Kafka listener가 이벤트를 받으면
  - 먼저 Inbox claim을 시도하고
  - 성공한 경우에만 재고 변경 비즈니스 로직을 수행
  - duplicate면 비즈니스 처리를 건너뜀

즉 "처리 성공 시에만 Inbox가 남고, Inbox가 없으면 중복 차단 근거도 없다"가 아니라,

- **Inbox claim 성공 -> 처리 진행**
- **Inbox claim 실패 -> duplicate skip**

구조로 되어 있다.

## 5. 테스트 조건

기준 run:

- `1776241903`

공통 조건:

- 동일한 재고 예약 이벤트 `100회` 중복 발행
- 상품 재고 `100`
- 수량 `1`
- topic: `market.order.stock.changed.experiment.inbox`
- listener concurrency: `1`
- Redis 재고 선차감 비활성화
  - 목적: Redis 중복 방어가 아니라 Inbox 효과만 분리해 보기 위함

### BEFORE

- `PRODUCT_INBOX_ENABLED=false`
- 동일 이벤트 100건을 그대로 소비

### AFTER

- `PRODUCT_INBOX_ENABLED=true`
- 동일 이벤트 100건을 Inbox 기준으로 claim 후 중복 skip

## 6. 부하는 어떻게 주었는가

실행 스크립트는 아래다.

- `loadtest/run-product-inbox-before-after-experiment.sh`
- 실제 오케스트레이션 스크립트: `loadtest/product-inbox-before-after-experiment.js`

이번 실험은 일반 사용자 API 부하가 아니라, experiment API를 이용해 **중복 메시지 소비 상황을 재현하는 구조**다.

흐름은 다음과 같다.

1. `product-service`를 `experiment` 프로필로 재기동
2. 실험용 상품 1개 생성
3. run 초기화
4. 같은 `orderNumber`, 같은 `eventType`, 같은 `productId`, 같은 `quantity`로 `RESERVE` 이벤트를 `100회` 발행
5. listener가 모든 메시지를 처리하거나 skip할 때까지 summary polling
6. BEFORE / AFTER 결과를 하나의 JSON으로 저장

즉 입력은:

- **같은 재고 예약 이벤트 100회 중복 발행**

이고,

측정 대상은:

- 실제 처리 수
- duplicate skip 수
- 재고 예약 증가량
- Inbox 기록 수

이다.

## 7. 측정 기준

이번 실험에서 핵심은 "몇 건을 받았나"가 아니라, **몇 건이 실제 비즈니스 처리로 반영됐는가**다.

최종적으로는 아래 값을 본다.

- `processedCount`
  - 실제 비즈니스 처리까지 수행된 메시지 수
- `duplicateSkippedCount`
  - Inbox에 의해 duplicate로 차단된 메시지 수
- `reservedDelta`
  - 실험 전후 예약 재고 증가량
- `appliedReservationCount`
  - 수량 기준으로 실제 예약이 몇 번 반영됐는지
- `inboxRecordCount`
  - 해당 idempotency key로 남은 Inbox row 수

이번 실험에서 중요한 해석은 아래다.

- Inbox가 없으면
  - `processedCount = 100`
  - `reservedDelta = 100`
- Inbox가 있으면
  - `processedCount = 1`
  - `duplicateSkippedCount = 99`
  - `reservedDelta = 1`
  - `inboxRecordCount = 1`

즉 "동일 이벤트 100회 입력이 실제로 100번 반영되는가, 아니면 1번만 반영되는가"를 보는 실험이다.

## 8. 최종 결과

기준 run:

- `1776241903`

결과는 아래와 같다.

| 항목 | BEFORE (`Inbox 미적용`) | AFTER (`Inbox 적용`) |
|---|---:|---:|
| 입력 메시지 수 | 100 | 100 |
| 실제 처리 수 | 100 | 1 |
| 중복 차단 수 | 0 | 99 |
| 재고 예약 반영 수 | 100 | 1 |
| 예약 재고 증가량 | 100 | 1 |
| Inbox 기록 수 | 0 | 1 |

해석:

- Inbox가 없으면 동일한 재고 예약 이벤트가 100번 모두 반영됨
- Inbox를 적용하면 1번만 처리되고 99건은 duplicate로 차단됨
- 최종적으로 같은 idempotency key에 대한 Inbox 기록은 1건만 남음

즉 이 실험은 아래를 증명한다.

- Outbox 이후 남는 duplicate consume 문제는 실제로 존재한다
- Inbox는 동일 이벤트의 중복 소비를 비즈니스 처리 전에 차단한다
- 결과적으로 재고 정합성을 보호할 수 있다

## 9. 왜 single-run 결과로 충분한가

이번 실험은 성능 실험이 아니라 correctness 실험이다.

즉 핵심은:

- 평균 응답시간
- p95
- 중앙값

이 아니라,

- duplicate input `100건`
- actual apply `1건`
- duplicate skip `99건`

이라는 **정확한 상태 전이 결과**다.

따라서 이 실험은 3회 중앙값보다, **단일 run에서 기대한 correctness가 정확히 나왔는지**가 더 중요하다.

## 10. 면접에서 설명할 때의 핵심 문장

짧게 설명하면 아래가 가장 정확하다.

> Outbox로 유실은 막았지만, recovery 과정에서 duplicate publish 가능성이 남습니다. 그래서 소비 측에 Inbox를 두고 `topic + consumerGroup + idempotencyKey` 기준으로 최초 이벤트만 claim하도록 구성했습니다. 동일한 재고 예약 이벤트를 100회 중복 발행한 실험에서 Inbox 미적용 시 재고 예약이 100회 반영됐지만, 적용 후에는 1회만 반영되고 99건은 중복 차단되는 것을 확인했습니다.

조금 더 기술적으로 설명하면 이렇게 이어갈 수 있다.

> Kafka listener에서 먼저 Inbox claim을 시도하고, claim 성공 시에만 재고 변경 비즈니스 로직을 수행합니다. claim이 실패하면 이미 처리된 동일 이벤트라고 보고 skip합니다. 이 구조 덕분에 at-least-once 발행 특성 아래에서도 소비는 effectively-once에 가깝게 만들 수 있었습니다.

## 11. 이 실험이 증명하는 것과 증명하지 않는 것

### 증명하는 것

- duplicate input에 대한 Inbox의 중복 소비 차단 효과
- 재고 예약 정합성 보호
- Outbox 다음 단계로 Inbox가 왜 필요한지

### 증명하지 않는 것

- 일반적인 Kafka 소비 성능
- 여러 파티션에서의 순서 보장
- producer duplicate 자체의 원인

이번 실험은 **duplicate consume 차단 효과**만 분리해서 본 것이다.

## 12. 실행 방법

실행 스크립트:

- `loadtest/run-product-inbox-before-after-experiment.sh`

실행 예시:

```bash
RUN_ID=$(date +%s) \
EXPERIMENT_DUPLICATE_COUNT=100 \
bash loadtest/run-product-inbox-before-after-experiment.sh
```

결과 파일:

- `loadtest/results/product-inbox-before-after-<run_id>.json`

이 JSON 하나로 BEFORE/AFTER 표를 만들 수 있다.
