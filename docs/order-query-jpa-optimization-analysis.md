# 주문 조회 JPA 최적화 분석

## 목표

주문 목록/상세 조회에서 `LAZY` 연관 로딩으로 인해 발생하는 `N+1` 문제를 제거하고, 이를 쿼리 수와 응답시간으로 검증한다.

대상 경로는 다음 두 가지다.

- 주문 목록 조회: `getMyOrders()`
- 주문 상세 조회: `getOrderDetail()`

---

## 문제 상황

기존 주문 조회는 `Order`만 먼저 조회한 뒤, 응답 DTO 변환 과정에서 `order.getItems()`를 순회했다.

핵심 문제는 다음이다.

- `Order.items`는 `LAZY`
- `OrderDetailResponse.from(order)`가 `order.getItems()`에 접근
- 주문 목록 조회 시 주문 수만큼 `items` 추가 쿼리가 발생

즉 목록 조회는 구조적으로 다음과 같았다.

1. 주문 목록 1회 조회
2. 각 주문의 `items` lazy 조회

주문이 `N`건이면 전체 쿼리는 `N+1`이 된다.

상세 조회도 같은 원리로:

1. 주문 1회 조회
2. `items` lazy 조회 1회

총 2쿼리가 발생했다.

---

## 원인 코드

기존 병목의 핵심은 DTO 변환 시점의 연관 컬렉션 접근이었다.

- `OrderService.getMyOrders()`
- `OrderService.getOrderDetail()`
- `OrderDetailResponse.from(order)`

`OrderDetailResponse.from(order)`는 아래 형태로 동작한다.

```java
order.getItems().stream()
    .map(OrderItemDto::from)
    .toList();
```

즉 서비스 계층에서는 단순 조회처럼 보이지만, 실제로는 응답 생성 시점에 `items` 컬렉션이 초기화되면서 추가 SQL이 발생한다.

---

## 최적화 방향

이번 개선의 목표는 인덱스나 실행계획이 아니라, **JPA 연관 로딩 구조 자체를 바꾸는 것**이었다.

선택한 방식은 `fetch join`이다.

이유:

- 문제의 원인이 `LAZY` 연관 컬렉션 초기화였음
- 필요한 연관 데이터가 `Order.items`로 명확했음
- 목록/상세 모두 읽기 전용 조회라 `fetch join` 적용이 자연스러움

즉 `Order`를 가져올 때 `items`도 함께 로딩하도록 Repository 쿼리를 분리했다.

---

## 구현 내용

### 1. 주문 목록 조회 전용 fetch join 쿼리 추가

`OrderRepository`

```java
@Query("""
        select distinct o
        from Order o
        left join fetch o.items
        where o.buyer.id = :buyerId
        order by o.createdAt desc
        """)
List<Order> findDetailsByBuyerIdOrderByCreatedAtDesc(@Param("buyerId") Long buyerId);
```

설명:

- `left join fetch o.items`로 `OrderItem`을 함께 조회
- `distinct`는 `Order 1 : N OrderItem` 조인으로 루트 엔티티가 중복되는 것을 방지

### 2. 주문 상세 조회 전용 fetch join 쿼리 추가

```java
@Query("""
        select distinct o
        from Order o
        left join fetch o.items
        where o.id = :orderId
        """)
Optional<Order> findDetailById(@Param("orderId") Long orderId);
```

### 3. 서비스 계층에서 전용 조회 메서드 사용

- `getMyOrders()`는 `findDetailsByBuyerIdOrderByCreatedAtDesc()`
- `getOrderDetail()`는 `findDetailById()`

즉 변경의 본질은:

- 기존: `Order` 조회 후 DTO 변환 중 연관 컬렉션 lazy 로딩
- 변경: `Order` 조회 시 `items`까지 한 번에 로딩

---

## 쿼리 수 검증

검증은 Hibernate Statistics를 사용했다.

테스트 파일:

- `market-service/src/test/java/com/thock/back/market/app/OrderServiceQueryOptimizationTest.java`

기준 데이터:

- 주문 5건
- 주문당 아이템 2건

검증 결과:

| 케이스 | Baseline | Optimized | 개선 |
|---|---:|---:|---:|
| 주문 목록 조회 | 6 queries | 1 query | 83.3% 감소 |
| 주문 상세 조회 | 2 queries | 1 query | 50.0% 감소 |

해석:

- 목록 조회는 `N+1 -> 1`
- 상세 조회는 `2 -> 1`

즉 이번 개선은 “체감상 빨라진 것 같다”가 아니라, **SQL 실행 수 자체를 줄였다는 점**이 먼저 입증됐다.

### 왜 주문당 상품 5개인데 쿼리가 6개인가

여기서 헷갈리기 쉬운 포인트가 있다.

`Order.items`는 개별 `OrderItem`을 하나씩 가져오는 구조가 아니라, **주문 1건에 연결된 `OrderItem` 컬렉션 전체를 한 번에 초기화하는 구조**다.

즉 `OrderDetailResponse.from(order)`에서:

```java
order.getItems().stream()
    .map(OrderItemDto::from)
    .toList();
```

이 코드가 실행될 때,

- 아이템 5개면 5번 쿼리

가 아니라,

- 해당 주문의 `items` 전체를 조회하는 쿼리 1번

이 실행된다.

Hibernate는 대략 아래 형태의 SQL로 컬렉션을 초기화한다.

```sql
select *
from market_order_items
where order_id = ?
```

즉 테스트에서 주문 5건, 주문당 아이템 2건이었다면 baseline `6쿼리`는:

- 주문 목록 조회 1쿼리
- 각 주문의 items 컬렉션 조회 5쿼리

를 뜻한다.

즉 이 문제는 “아이템 개수만큼 쿼리”가 아니라, **주문 개수만큼 컬렉션 조회가 추가되는 N+1** 문제다.

---

## 응답시간 검증

쿼리 수 감소만으로 끝내지 않고, 실험용 endpoint와 k6 스크립트를 추가해 baseline/optimized 응답시간도 비교했다.

실험 인프라:

- `market-service/src/main/java/com/thock/back/market/experiment/OrderQueryExperimentService.java`
- `market-service/src/main/java/com/thock/back/market/experiment/OrderQueryExperimentController.java`
- `loadtest/order-query-read.js`
- `loadtest/run-order-query-experiment.sh`

실험 방식:

- `experiment` 프로필에서 baseline endpoint와 optimized endpoint를 같이 노출
- 동일 데이터셋을 reset/seed 후 두 경로를 각각 측정
- baseline: lazy 조회 경로
- optimized: fetch join 조회 경로

조건:

- 주문 100건
- 주문당 상품 5개
- k6 `300 iterations`
- `20 VUs`
- 3회 반복

유효 run:

- `1775998763`
- `1775998834`
- `1775998872`

중앙값 기준 결과:

| 항목 | Baseline | Optimized | 개선 |
|---|---:|---:|---:|
| avg | 315.76ms | 36.86ms | 88.3% 감소 |
| p95 | 461.39ms | 62.41ms | 86.5% 감소 |

---

## 왜 응답시간까지 개선됐는가

이번 개선은 인덱스 추가가 아니라 **왕복 SQL 수 감소**가 핵심이었다.

주문 목록 조회 기준으로 보면:

- 기존: 주문 1회 + 각 주문의 items lazy 조회
- 변경: 주문 + items를 한 번에 조회

즉 응답 생성 과정에서 발생하던 다수의 DB round trip을 제거했기 때문에, 쿼리 수 감소가 응답시간 개선으로 직접 이어졌다.

이번 사례는 상품 검색 튜닝과 달리:

- 병목 원인이 명확했고
- 개선 수단이 병목과 정확히 맞물렸고
- 그 결과 쿼리 수와 응답시간이 함께 개선됐다

---

## 이번 작업에서 배운 점

### 1. JPA 조회 최적화는 실행계획보다 로딩 구조가 먼저일 수 있다

이번 문제는 인덱스가 아니라 `LAZY` 연관 로딩 구조가 원인이었다.

즉 조회 성능 문제는 항상:

- 인덱스
- SQL 실행계획

에서만 시작하는 것이 아니라,

- 엔티티 연관관계
- DTO 변환 시점
- fetch 전략

에서 시작할 수 있다.

### 2. DTO 변환 코드도 쿼리 발생 지점이 될 수 있다

서비스 계층에서 조회 메서드는 단순해 보여도, DTO 조립 시 `getItems()` 같은 접근이 실제 추가 쿼리를 만든다.

즉 JPA 성능 문제는 Repository 메서드만 보지 말고, **응답 조립 코드까지 같이 봐야 한다.**

### 3. 검증은 두 단계로 나눠야 한다

이번에는 아래 순서로 검증했다.

1. Hibernate Statistics로 쿼리 수 감소 검증
2. k6로 응답시간 개선 검증

이 방식이 좋았던 이유:

- 쿼리 수 감소는 구조 개선 증거
- 응답시간 감소는 사용자 관점 성과

둘을 같이 잡을 수 있었기 때문이다.

---

## 최종 정리

이번 개선의 핵심은 다음 한 줄로 정리할 수 있다.

> 주문 목록·상세 조회에서 LAZY 연관 로딩으로 발생하던 N+1을 fetch join으로 제거해, 목록 조회를 `N+1 -> 1`, 상세 조회를 `2 -> 1`로 줄였고, 주문 100건·주문당 상품 5개 기준 3회 반복 부하 실험 중앙값에서 `avg 88.3%`, `p95 86.5%` 개선했다.

즉 이번 사례는:

- JPA 연관 로딩 문제를 정확히 인식했고
- Repository 쿼리를 목적에 맞게 분리했으며
- 쿼리 수와 응답시간을 함께 검증한

**읽기 경로 최적화 사례**로 정리할 수 있다.
