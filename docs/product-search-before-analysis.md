# Product Search Before Analysis

## 목적

상품 검색 API의 조회 성능을 `Querydsl + 복합 인덱스 + 실행계획` 관점에서 개선하기 전에,
현재 병목이 어디에 있는지 계측으로 먼저 고정한다.

이번 Before 분석의 대상은 `keyword` 없는 탐색형 검색이다.

## 기준 코드

- 검색 구현: `product-service/src/main/java/com/thock/back/product/out/ProductRepositoryImpl.java`
- 검색 인덱스: `product-service/src/main/resources/db/migration/V7__add_product_search_indexes.sql`

현재 검색 정렬은 다음 셋뿐이다.

- `LATEST -> ORDER BY id DESC`
- `PRICE_ASC -> ORDER BY price ASC`
- `PRICE_DESC -> ORDER BY price DESC`

현재 products 테이블 인덱스는 다음 두 개다.

- `PRIMARY (id)`
- `idx_products_category_state_price_id (category, state, price, id)`

즉 현재 구조는 `search()` 하나와 복합 인덱스 하나로
`LATEST`, `PRICE_ASC`, `PRICE_DESC`, `COUNT`를 모두 처리하려는 범용 구조다.

## Before 실험 환경

- run_id: `1775974311`
- 데이터 건수: `100,000`
- 상태 분포:
  - `ON_SALE 75,000`
  - `SOLD_OUT 15,000`
  - `STOPPED 10,000`
- category: `KEYBOARD`
- 부하 조건:
  - `K6_ITERATIONS=3000`
  - `K6_VUS=50`

근거 파일:

- `loadtest/results/product-search-dataset-1775974311.json`
- `loadtest/results/product-search-latest-1775974311.json`
- `loadtest/results/product-search-price-asc-1775974311.json`
- `loadtest/results/product-search-price-desc-1775974311.json`
- `loadtest/results/product-search-latest-explain-1775974311.txt`
- `loadtest/results/product-search-price-asc-explain-1775974311.txt`
- `loadtest/results/product-search-price-desc-explain-1775974311.txt`

## 실제 쿼리 Shape

### 1. LATEST

```sql
SELECT ...
FROM products
WHERE category = 'KEYBOARD'
  AND state = 'ON_SALE'
ORDER BY id DESC
LIMIT 20 OFFSET 0;

SELECT COUNT(*)
FROM products
WHERE category = 'KEYBOARD'
  AND state = 'ON_SALE';
```

### 2. PRICE_ASC

```sql
SELECT ...
FROM products
WHERE category = 'KEYBOARD'
  AND state = 'ON_SALE'
  AND price BETWEEN 50000 AND 300000
ORDER BY price ASC
LIMIT 20 OFFSET 0;

SELECT COUNT(*)
FROM products
WHERE category = 'KEYBOARD'
  AND state = 'ON_SALE'
  AND price BETWEEN 50000 AND 300000;
```

### 3. PRICE_DESC

```sql
SELECT ...
FROM products
WHERE category = 'KEYBOARD'
  AND state = 'ON_SALE'
  AND price BETWEEN 50000 AND 300000
ORDER BY price DESC
LIMIT 20 OFFSET 0;

SELECT COUNT(*)
FROM products
WHERE category = 'KEYBOARD'
  AND state = 'ON_SALE'
  AND price BETWEEN 50000 AND 300000;
```

## Before 결과

### 응답시간

- `LATEST`
  - avg `217.94ms`
  - p95 `384.37ms`
- `PRICE_ASC`
  - avg `125.41ms`
  - p95 `212.66ms`
- `PRICE_DESC`
  - avg `111.95ms`
  - p95 `188.74ms`

### 실행계획 핵심 요약

#### LATEST

- content 조회: `PRIMARY reverse scan`
- count 조회: `idx_products_category_state_price_id` 사용

해석:

- 현재 복합 인덱스는 `latest(id desc)` 전용 인덱스가 아니다.
- 그래서 optimizer는 검색용 복합 인덱스보다 `PRIMARY reverse scan`을 택했다.

#### PRICE_ASC

- content 조회: `table scan + sort`
- count 조회: `idx_products_category_state_price_id` range scan

해석:

- `price`가 인덱스에 포함돼 있어도 content 조회 전체 비용이 충분히 싸다고 optimizer가 보지 않았다.
- 현재 데이터 분포상:
  - 전체 `100,000`
  - `ON_SALE 75,000`
  - `price between 50000 and 300000` 이후 `38,308`
- 즉 선두 조건 선택도가 낮고, content 조회는 covering index도 아니다.
- 그래서 optimizer는 `index range scan + row lookup`보다 `table scan + sort`가 더 싸다고 판단했다.

#### PRICE_DESC

- content 조회: `idx_products_category_state_price_id reverse range scan`
- count 조회: `idx_products_category_state_price_id` range scan

해석:

- 현재 인덱스를 역방향으로 읽는 경로가 optimizer에게 더 싸게 보였다.
- 이것은 `DESC용으로 잘못 설계했다`는 뜻이 아니라,
  현재 cost model에서 `DESC` 경로만 상대적으로 인덱스를 탈 만하다고 본 것이다.

## 핵심 문제 정리

현재 문제는 인덱스가 전혀 없는 것이 아니다.

문제는 다음 두 가지다.

1. `search()` 하나가 `LATEST`, `PRICE_ASC`, `PRICE_DESC`, `COUNT`를 모두 처리하는 범용 구조다.
2. 인덱스도 `(category, state, price, id)` 하나로 여러 정렬 패턴을 동시에 만족시키려 한다.

즉 코드상으로는 동적 쿼리이지만, DB 관점에서는 여전히 하나의 범용 검색 파이프라인이다.

이 구조에서는 각 검색이 원하는 정렬 축이 다름에도,
optimizer가 모든 경우에 현재 인덱스를 "가장 싼 경로"라고 보지 않는다.

## 중요한 학습 포인트

### 1. 컬럼이 인덱스에 있다고 해서 정렬 최적화가 보장되지는 않는다

`price ASC`가 현재 인덱스에 포함돼 있어도,
optimizer는 전체 비용이 더 싸지 않다고 보면 index scan 대신 table scan + sort를 선택할 수 있다.

### 2. 동적 쿼리와 쿼리 분리는 다른 문제다

현재도 조건에 따라 SQL은 달라지지만,
실제로는 하나의 공통 `search()` 메서드와 공통 `count` 전략을 사용한다.

튜닝 관점에서의 "분리"는 단순 if문이 아니라,
정렬/용도별로 다른 SQL shape, 다른 count 전략, 다른 인덱스 전략을 허용하는 것이다.

### 3. 오설계의 본질은 "DESC를 빠르게 만든 것"이 아니다

현재 문제는 `DESC`가 잘 되는 오설계가 아니라,
`LATEST`와 `PRICE_ASC`가 optimizer가 신뢰할 만한 전용 경로를 갖지 못했다는 점이다.

## 다음 튜닝 방향

### 1. 탐색형 검색을 정렬별로 분리

- `searchLatest(...)`
- `searchPriceAsc(...)`
- `searchPriceDesc(...)`

`keyword` 검색은 현재 단계에서 튜닝 대상에서 제외하거나 fallback 경로로 유지한다.

### 2. 인덱스를 목적별로 분리

검토 후보:

- `LATEST` 전용 인덱스: `(category, state, id)`
- `PRICE` 전용 인덱스: `(category, state, price, id)` 유지 또는 조정

### 3. PRICE_ASC는 필요하면 2단계 조회로 전환

예시 방향:

1. 인덱스로 `id`만 20개 선조회
2. 그 20개 `id`로 실제 컬럼 fetch

목적:

- optimizer가 인덱스 기반 limit 경로를 자연스럽게 고르도록 SQL shape를 더 명확하게 만든다.

### 4. count 전략 재검토

현재는 모든 탐색 검색이 `Page + count(*)`를 유지한다.

검토 대상:

- `Slice` 전환
- count 조건부 실행
- content 조회와 count 전략 분리

## 한 줄 결론

현재 상품 검색 문제는 "인덱스가 없다"가 아니라,
"하나의 범용 검색 경로와 하나의 범용 인덱스로 여러 정렬 패턴을 동시에 만족시키려다 보니 optimizer가 일부 경로를 신뢰하지 못하는 구조"다.

따라서 다음 단계는 탐색형 검색을 정렬별로 분리하고,
그에 맞는 전용 인덱스와 조회 전략으로 optimizer가 자연스럽게 인덱스를 선택하게 만드는 것이다.
