# Kafka Partition Before/After Experiment Analysis

## 1. 실험 목적

이 실험은 재고 변경 이벤트 소비 구조를 아래 두 방식으로 비교하기 위해 설계했다.

- BEFORE: 단일 파티션 + 직렬 소비
- AFTER: 다중 파티션 + 병렬 소비

검증하고자 한 핵심은 두 가지다.

- 처리량과 총 처리 시간이 실제로 개선되는가
- `orderNumber`를 Kafka key로 사용할 때 동일 주문의 이벤트 순서가 유지되는가

즉 이 실험은 "실제 주문 생성부터 상품 반영까지의 전체 E2E 성능"이 아니라, **동일한 재고 이벤트 backlog가 쌓였을 때 소비 구조 차이가 만드는 처리 성능과 순서 보장 효과**를 분리해 검증하는 실험이다.

## 2. 왜 `product-service` 내부 실험 하네스를 사용했는가

실서비스에서는 주문/마켓 서비스가 재고 변경 이벤트를 발행하고 `product-service`가 이를 소비한다.

다만 이번 실험에서는 소비 구조 자체를 검증하는 것이 목적이었기 때문에, 발행 측 비즈니스 로직이나 다른 서비스 오버헤드를 섞지 않기 위해 `product-service`의 `experiment` 프로필에서 **실험용 synthetic publisher**를 두었다.

즉 현재 실험은:

- 운영 경로를 대체하지 않는다
- 실험 전용 토픽만 사용한다
- 소비 구조 비교에 필요한 동일 이벤트 세트를 재현 가능하게 발행한다

## 3. 비교 조건

### 공통 조건

- 반복 횟수: `3회`
- 총 이벤트 수: `3,000건`
- 주문 수: `1,500건`
- 주문당 이벤트 수: `2건`
  - `RESERVE`
  - `COMMIT`
- 상품 수: `12개`
- 각 이벤트 수량: `1개`
- 초기 재고: 상품당 `100,000개`

### BEFORE

- 토픽: `market.order.stock.changed.experiment.single`
- 파티션 수: `1`
- consumer concurrency: `1`

### AFTER

- 토픽: `market.order.stock.changed.experiment.multi`
- 파티션 수: `3`
- consumer concurrency: `3`

### 공통 key 전략

- Kafka message key: `orderNumber`

이 전략으로 동일 주문의 `RESERVE`, `COMMIT` 이벤트가 같은 파티션으로 라우팅되도록 했다.

## 4. 부하는 어떻게 주었는가

이번 실험은 일정 주기로 HTTP 요청을 흘리는 형태가 아니라, **이벤트 backlog를 burst로 형성한 뒤 소비자가 이를 얼마나 빨리 drain 하는지**를 보는 구조다.

흐름은 아래와 같다.

1. `product-service`를 실험 프로필로 재기동
2. 실험용 상품 12개 생성
3. 실험 run 초기화
4. Kafka 이벤트 3,000건을 가능한 한 빠르게 연속 발행
5. 모든 이벤트 처리 완료까지 summary endpoint polling
6. single/multi 결과를 JSON으로 저장

### "3,000건 burst"의 정확한 의미

기술적으로 레코드 3,000개를 하나의 단일 API 호출로 넣는 구조는 아니다.

현재 실험용 publisher는 `for` 루프에서 이벤트를 **비동기 `send()` 호출로 연속 발행**한다.

- `sleep` 없음
- `throttle` 없음
- 중간 ack 대기 없음
- 마지막에만 전체 `Future`를 한 번에 `join()`

즉 실험 의미상으로는 **이벤트 3,000건을 burst로 밀어 넣어 backlog를 만든 조건**이다.

## 5. `send()`와 Kafka producer 내부 동작

이 부분은 면접에서 오해가 생기기 쉬워서 분리해 정리한다.

### 애플리케이션 스레드가 하는 일

- `kafkaTemplate.send(...)` 호출
- 레코드 직렬화
- Kafka producer 내부 버퍼에 적재
- `Future` 즉시 반환

### Kafka producer 내부 sender thread가 하는 일

- 버퍼에 쌓인 레코드를 배치로 묶음
- 브로커로 실제 네트워크 전송
- ack 수신
- 해당 `Future` 완료 처리

즉 `send()`는 비동기이지만, **호출 순서 자체는 애플리케이션 스레드가 순차적으로 만든다.**

## 6. `RESERVE -> COMMIT` 순서는 어떻게 만들어졌는가

실험용 publisher는 주문마다 아래 순서로 이벤트를 발행한다.

1. `RESERVE`
2. `COMMIT`

코드상으로는 같은 루프에서 아래처럼 순차 호출된다.

- `RESERVE send()`
- 바로 이어서 `COMMIT send()`

중요한 점은:

- `RESERVE`의 브로커 ack를 기다렸다가 `COMMIT`을 보내는 구조는 아니다
- 하지만 `send()` 호출 자체는 같은 스레드에서 순서대로 이루어진다
- 두 이벤트는 같은 `orderNumber` key를 사용하므로 같은 파티션으로 간다

따라서 이번 실험이 검증하는 것은:

- **정상 순서로 발행된 이벤트가**
- **단일 파티션/다중 파티션 소비 환경에서**
- **동일 주문 단위로 순서가 깨지지 않는가**

이지,

- 잘못된 순서로 발행된 이벤트를 시스템이 복구하는가

를 검증하는 실험은 아니다.

## 7. 순서 보장은 어떻게 검증했는가

소비 측에서는 주문별 상태를 메모리에서 추적한다.

- 초기 상태: `NONE`
- `RESERVE` 처리 후: `RESERVED`
- `COMMIT` 처리 후: `COMMITTED`

아래 경우를 순서 위반으로 간주한다.

- `COMMIT`이 `RESERVE`보다 먼저 처리됨
- `RESERVE`가 두 번 처리됨
- `COMMIT`이 두 번 처리됨
- 기대한 상태 전이가 아닌 경우

이 위반 횟수가 `orderingViolationCount`다.

이번 3회 실험에서는 모두:

- `orderingViolationCount = 0`

이었다.

즉 동일 주문에 대한 `RESERVE -> COMMIT` 순서가 소비 단계에서도 유지됐다는 뜻이다.

## 8. 측정 기준

핵심 지표는 아래다.

- `throughputEventsPerSecond`
- `totalDurationMillis`
- `processedEventCount`
- `completedOrderCount`
- `orderingViolationCount`
- `partitionCounts`
- `threadCounts`

### 측정 시작 시점

- 상품 생성 및 setup이 끝난 뒤
- `k6` runner가 `startedAtMillis`를 기록하고 `reset` 요청을 보낸 다음,
- **`product-service`의 `publish()` 메서드가 3,000건 `send()` 루프를 시작하기 직전**

### 측정 종료 시점

컨슈머가 마지막 이벤트를 처리 완료한 시점

- recorder에 등록한 `expectedEventCount=3000`, `expectedOrderCount=1500`이 모두 충족되고,
- `orderingViolationCount=0`, `failedCount=0` 조건을 만족한 상태에서
- **마지막 이벤트의 비즈니스 처리가 완료되어 `lastProcessedAtMillis`가 기록된 시점**

즉 `totalDurationMillis`는:

- setup 시간 제외
- `publish()` 루프 시작 전부터 consume 완료까지

의 **총 처리 시간(end-to-end event drain time)** 이다.

## 9. 최종 결과

기준:

- 3회 반복 부하 실험 중앙값

Run ID:

- `1776224276`
- `1776224424`
- `1776224497`

중앙값 기준 결과:

| 항목 | BEFORE | AFTER | 개선율 |
|---|---:|---:|---:|
| 처리량 | 293.80 events/s | 471.40 events/s | 60.45% |
| 총 처리 시간 | 10211ms | 6364ms | 37.68% |
| 처리 이벤트 수 | 3000 | 3000 | - |
| 완료 주문 수 | 1500 | 1500 | - |
| 주문별 순서 위반 | 0건 | 0건 | - |

추가로 분산 처리 여부도 실제로 확인했다.

- BEFORE: 1개 파티션, 1개 스레드
- AFTER: 3개 파티션, 3개 스레드에 분산 처리

## 10. 면접에서 설명할 때의 핵심 문장

짧게 설명하면 아래 정도가 가장 정확하다.

> 동일한 재고 변경 이벤트 3,000건을 burst로 발행해 backlog를 형성한 뒤, 단일 파티션 직렬 소비와 다중 파티션 병렬 소비를 비교했습니다. `orderNumber`를 Kafka key로 사용해 주문별 `RESERVE -> COMMIT` 순서 보장을 유지하면서, 3회 반복 부하 실험 중앙값 기준 처리량을 60.45% 높이고 총 처리 시간을 37.68% 단축했습니다.

보강 설명이 필요하면 이렇게 이어가면 된다.

> 실서비스에서는 주문/마켓 서비스가 이벤트를 발행하지만, 이번 실험은 소비 구조 차이만 분리해 보기 위해 `product-service` 내부 실험 하네스가 동일 이벤트를 synthetic하게 발행하도록 구성했습니다. setup 시간을 제외하고 `publish()`의 3,000건 `send()` 루프 시작 직전부터 마지막 consume 완료 시점까지 측정했고, 주문별 상태 전이를 추적해 순서 위반 0건을 확인했습니다.

즉 종료는 Kafka가 "이게 마지막 레코드"라고 알려줘서 판단하는 것이 아니라, **이번 실험에서 기대한 3,000개 이벤트와 1,500개 주문 완료 수가 모두 채워졌는지**를 recorder가 확인해 결정한다.

## 11. 실행 방법

실행 스크립트:

- `loadtest/run-partition-experiment.sh`

실행 예시:

```bash
RUN_ID=$(date +%s) bash loadtest/run-partition-experiment.sh
```

결과 파일:

- `loadtest/results/partition-experiment-<run_id>.json`
- `loadtest/results/partition-experiment-single-<run_id>.json`
- `loadtest/results/partition-experiment-multi-<run_id>.json`

## 12. 이 실험이 증명하는 것과 증명하지 않는 것

### 증명하는 것

- 단일 파티션 대비 다중 파티션 병렬 소비의 처리량 개선
- 총 처리 시간 단축
- `orderNumber` key 기반 동일 주문 순서 보장 유지

### 증명하지 않는 것

- 실서비스 전체 주문 생성 E2E latency
- 주문/마켓 서비스의 발행 성능
- 외부 API 응답시간

즉 이 실험은 **Kafka 소비 구조 최적화 실험**으로 해석하는 것이 가장 정확하다.
