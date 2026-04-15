# Product Outbox Before/After Experiment Analysis

## 1. 실험 목적

이 실험은 상품 변경 이벤트 발행 방식을 아래 두 구조로 비교하기 위해 설계했다.

- BEFORE: DB 커밋 후 Kafka로 바로 발행하는 `Direct Kafka`
- AFTER: DB 커밋과 Outbox 적재를 함께 수행한 뒤 poller가 발행하는 `Outbox`

검증하고자 한 핵심은 세 가지다.

- broker 장애 시 커밋된 데이터 기준으로 이벤트 유실이 발생하는가
- Outbox가 `PENDING -> SENT`로 실제 복구되는가
- Outbox가 유실은 없애지만 duplicate publish 가능성은 남기는가

즉 이 실험은 단순한 create API 성능 비교가 아니라, **Kafka broker 장애 상황에서 direct 발행과 outbox 발행의 신뢰성 차이**를 검증하는 실험이다.

## 2. 비교 대상

### BEFORE: Direct Kafka

- 상품 생성 트랜잭션이 커밋된 뒤 `afterCommit()`에서 Kafka로 바로 발행
- 별도의 durable retry state 없음
- Kafka publish 실패 시 DB에 남은 상품 row와 Kafka event 사이를 다시 맞출 근거가 없음

### AFTER: Outbox

- 상품 생성과 Outbox row 적재를 단일 트랜잭션으로 묶음
- broker 장애 시 `product_outbox_event`에 `PENDING`으로 남음
- broker 복구 후 poller가 재시도해 `SENT`로 수렴

## 3. 테스트 조건

공통 조건은 아래와 같다.

- 외부 상품 생성 요청: `2,400건`
- 동시 사용자: `50 VUs`
- 상품 재고: `5`
- Kafka broker: 생성 요청 동안 down 상태 유지
- 비교 기준: 동일 장애 조건의 Before/After
- 최종 비교 run: `1776235550`

### BEFORE 실행 조건

- publish mode: `direct`
- broker down 상태에서 상품 생성 요청 수행
- product-service 재기동 후 broker 복구

### AFTER 실행 조건

- publish mode: `outbox`
- broker down 상태에서 상품 생성 요청 수행
- 복구 전 `PENDING` 누적 확인
- broker 복구 후 poller 수렴 확인

## 4. Direct Kafka에서 적용한 실무적 완화책

이번 BEFORE는 "아무 조치도 하지 않은 direct"가 아니다. 운영에서 direct Kafka를 유지하려고 할 때 먼저 시도할 수 있는 완화책을 넣은 상태로 비교했다.

적용한 내용은 아래와 같다.

- **producer metadata warm-up**
  - broker가 살아있는 상태에서 사전 1건 발행을 수행해 producer metadata를 미리 채움
  - 목적: broker down 직후 첫 send 메타데이터 조회 비용 때문에 요청 경로가 바로 무너지는 상황 완화

- **afterCommit 비동기 offload**
  - `afterCommit()`에서 Kafka send를 request thread에서 직접 실행하지 않고 별도 executor로 위임
  - 목적: broker 장애가 request thread를 직접 붙잡는 정도 완화

- **짧은 direct producer timeout**
  - `request.timeout.ms = 100`
  - `delivery.timeout.ms = 200`
  - `max.block.ms = 10`
  - 목적: direct send가 오래 block되며 HTTP 요청 전체를 끌고 가지 않도록 제한

실험 wrapper에 기록된 direct baseline 설정값은 아래와 같다.

- `beforeDirectAsyncAfterCommitEnabled = true`
- `beforeDirectWarmupEnabled = true`
- `beforeDirectKafkaRequestTimeoutMs = 100`
- `beforeDirectKafkaDeliveryTimeoutMs = 200`
- `beforeDirectKafkaMaxBlockMs = 10`

## 5. 왜 그래도 Direct Kafka는 정답이 아닌가

위 완화책은 **요청 경로를 버티게 만드는 완충책**일 뿐, 이벤트 유실 문제 자체를 해결하지 못한다.

핵심 이유는 direct 구조가 아래 성질을 가지기 때문이다.

- DB commit은 이미 끝난 뒤 Kafka publish를 시도한다
- publish 실패 시 재시도할 durable state가 없다
- 따라서 "어떤 row는 DB에 커밋됐는데 어떤 event는 발행되지 않았다"는 불일치를 나중에 복구할 근거가 없다

즉 direct Kafka에서 할 수 있는 최선은:

- 요청 실패/지연을 줄이는 것
- send 실패를 빨리 감지하는 것

까지다.

하지만 **이벤트 유실 0건 보장**은 할 수 없다.

이 지점에서 Outbox가 필요한 이유가 나온다.

## 6. 부하는 어떻게 주었는가

실행 스크립트는 아래다.

- `loadtest/run-product-outbox-before-after-experiment.sh`
- 실제 부하 생성 스크립트: `loadtest/product-outbox-recovery.js`

이번 실험에서 Kafka에 이벤트 `2,400건`을 직접 넣는 것은 아니다.

입력은 **외부 상품 생성 요청 `2,400건`** 이다.

구체적으로는 `k6`가 아래 조건으로 동작한다.

- scenario executor: `shared-iterations`
- 총 iteration 수: `2,400`
- 동시 사용자: `50 VUs`
- 각 iteration에서 `POST /api/v1/products/create` 1회 호출

즉 부하 생성 수단은:

- `k6`가 API Gateway를 통해 상품 생성 요청을 `2,400건` 보냄
- 각 성공 요청이 상품 row 1건과 `product.changed` 이벤트 후보 1건을 만든다

따라서 이번 실험의 기대 단위는 아래처럼 맞춰진다.

- 외부 요청 `2,400건`
- DB 상품 row 최대 `2,400건`
- Kafka event 최대 `2,400건`

흐름은 다음과 같다.

1. 공통 인프라와 `product-service`를 `experiment` 프로필로 올림
2. BEFORE direct 모드로 시작
3. direct warm-up 1건 수행
4. broker를 내린 뒤 외부 상품 생성 요청 `2,400건` 수행
5. direct 결과 수집
6. AFTER outbox 모드로 다시 시작
7. broker를 내린 뒤 외부 상품 생성 요청 `2,400건` 수행
8. 복구 전 outbox 상태 수집
9. broker 복구 후 `PENDING -> SENT` 수렴 확인
10. BEFORE/AFTER를 하나의 JSON으로 저장

즉 한 번의 wrapper 실행으로:

- direct under outage
- outbox under outage + recovery

를 모두 수집한다.

## 7. 측정 기준

이번 실험에서 보는 값은 단순 HTTP latency가 아니다. 핵심은 **run_id 기준으로 이번 실험에 의해 만들어진 상품 row와 Kafka 메시지를 서로 대응시키는 것**이다.

### 7-1. DB 기준 집계

wrapper는 `products.name LIKE 'k6-<run_id>-%'` 조건으로 이번 run에서 실제 커밋된 상품 row 수를 센다.

이 값이:

- `dbProductsCreated`

이다.

즉 "요청이 몇 건 들어왔는가"가 아니라, **DB에 실제로 남은 상품 row가 몇 건인가**를 기준으로 본다.

### 7-2. Kafka topic 기준 집계

wrapper는 실험 시작 전에 topic offset을 먼저 기록한다.

그 다음 `loadtest/run-scoped-topic-stats.sh`가:

- 이번 run에서 생성된 상품 ID 목록을 DB에서 조회하고
- 시작 offset 이후의 `product.changed` 메시지 key를 읽은 뒤
- 상품 ID와 메시지 key를 매칭한다

이렇게 계산한 값이 아래다.

- `matchedTopicMessages`
  - 이번 run 상품과 매칭된 topic 메시지 총 수
- `matchedUniqueProducts`
  - 고유 product 기준으로 Kafka에 반영된 수
- `duplicateTopicMessages`
  - 같은 product에 대해 topic에 중복으로 들어간 수
- `unrelatedTopicMessages`
  - 이번 run과 무관한 메시지 수

즉 Kafka 쪽 핵심 판단 기준은:

- **고유 product 기준으로 몇 건이 실제 topic에 반영되었는가**

이고, 그 값이 `matchedUniqueProducts`다.

### 7-3. Outbox 기준 집계

AFTER에서는 `product_outbox_event`를 이번 run의 상품 row와 join해서 상태를 직접 센다.

핵심 값은 아래다.

- `pendingCount`
- `sentCount`
- `failedCount`

따라서:

- broker down 동안 실제로 `PENDING`이 누적됐는지
- broker 복구 후 `SENT`로 수렴했는지

를 확인할 수 있다.

### 7-4. 최종 비교 지표

최종적으로는 아래 값을 비교한다.

- `dbProductsCreated`
  - DB에 실제 커밋된 상품 row 수
- `matchedUniqueProducts`
  - Kafka topic에서 고유 product 기준으로 확인된 이벤트 수
- `missingPublishedEvent`
  - `dbProductsCreated - matchedUniqueProducts`
- `pendingCount`
  - 복구 전 `product_outbox_event`의 `PENDING` row 수
- `sentCount`
  - 복구 후 `product_outbox_event`의 `SENT` row 수
- `duplicateTopicMessages`
  - topic에 들어간 전체 메시지 수와 고유 product 수의 차이
- `recovery.durationMillis`
  - broker 복구 시작부터 outbox가 모두 `SENT`로 수렴할 때까지 걸린 시간

가장 중요한 계산식은 아래다.

- `missingPublishedEvent = dbProductsCreated - matchedUniqueProducts`

즉 이번 실험은:

- **외부 상품 생성 요청 2,400건**
  - 가 들어오면
- **DB에 몇 건이 실제로 커밋됐는지**
- **그중 몇 건이 Kafka topic에 고유 이벤트로 반영됐는지**
- **Outbox는 복구 전후 상태가 어떻게 바뀌는지**

를 run_id 기준으로 연결해서 비교하는 구조다.

핵심 비교 기준은 아래 식으로 이해하면 된다.

- **Direct no-loss 여부**
  - `missingPublishedEvent == 0` 인가
- **Outbox recovery 여부**
  - 복구 전 `pendingCount > 0`
  - 복구 후 `pendingCount == 0 && sentCount == dbProductsCreated`
- **Outbox duplicate 여부**
  - `duplicateTopicMessages > 0` 인가

## 8. 최종 결과

기준 run:

- `1776235550`

결과는 아래와 같다.

| 항목 | BEFORE (`Direct Kafka`) | AFTER (`Outbox`) |
|---|---:|---:|
| DB 생성 수 | 2400 | 2400 |
| 토픽 고유 반영 수 | 0 | 2400 |
| 이벤트 유실 수 | 2400 | 0 |
| 복구 전 PENDING 수 | - | 2400 |
| 복구 후 SENT 수 | - | 2400 |
| 중복 발행 수 | - | 50 |
| 복구 시간 | - | 47000ms |

해석:

- direct는 broker 장애 시 **커밋된 2,400건 모두 이벤트 유실**
- outbox는 동일 조건에서 **복구 후 2,400건 모두 발행되어 유실 0건**
- 다만 outbox는 `at-least-once` 특성상 **duplicate publish 50건**이 발생

즉 이 실험은 아래를 증명한다.

- direct Kafka는 best-effort tuning을 해도 no-loss를 보장하지 못한다
- outbox는 no-loss를 달성한다
- 대신 duplicate 가능성이 남으므로 **Inbox/idempotency와 함께 가야 한다**

## 9. 왜 duplicate 50건이 중요한가

이번 결과에서 `duplicateTopicMessages = 50`은 오히려 좋은 학습 포인트다.

이 값은:

- outbox가 유실은 없앴지만
- 발행 보장은 `exactly-once`가 아니라 `at-least-once`

라는 점을 드러낸다.

따라서 아키텍처 메시지는 이렇게 정리된다.

- Outbox: **유실 방지**
- Inbox / idempotency: **중복 소비 차단**

즉 Outbox 슬라이드 다음에 Inbox 슬라이드가 자연스럽게 이어진다.

## 10. 면접에서 설명할 때의 핵심 문장

짧게 설명하면 아래가 가장 정확하다.

> direct Kafka는 DB 커밋 후 Kafka로 바로 발행하기 때문에 broker 장애 시 커밋된 row와 발행된 이벤트 사이를 나중에 복구할 durable state가 없습니다. 그래서 producer timeout, metadata warm-up, afterCommit 비동기 offload 같은 완화책을 적용해도 유실 자체는 막지 못했습니다. 반면 Outbox는 상품 생성과 Outbox 적재를 한 트랜잭션으로 묶어 broker 장애 시 `PENDING`으로 남기고, 복구 후 poller가 `SENT`로 수렴시켜 유실 0건을 만들 수 있었습니다.

duplicate까지 이어서 설명하면 아래 정도면 충분하다.

> 다만 Outbox는 exactly-once가 아니라 at-least-once 발행이라 duplicate publish 가능성이 남습니다. 이번 실험에서도 duplicate 50건이 나왔고, 그래서 다음 단계로 Inbox 기반 idempotency를 적용했습니다.

## 11. 이 실험이 증명하는 것과 증명하지 않는 것

### 증명하는 것

- broker 장애 시 direct와 outbox의 **유실 차이**
- outbox의 **복구 가능성**
- outbox의 **at-least-once 성격**

### 증명하지 않는 것

- direct와 outbox의 일반적인 create API latency 비교
- Kafka exactly-once delivery 보장
- 소비 측 duplicate 처리 완결성

duplicate 소비 차단은 별도 Inbox 실험으로 봐야 한다.

## 12. 실행 방법

실행 스크립트:

- `loadtest/run-product-outbox-before-after-experiment.sh`

실행 예시:

```bash
RUN_ID=$(date +%s) bash loadtest/run-product-outbox-before-after-experiment.sh
```

결과 파일:

- `loadtest/results/product-outbox-before-after-<run_id>.json`

이 JSON 하나로 BEFORE/AFTER 비교표를 만들 수 있다.
