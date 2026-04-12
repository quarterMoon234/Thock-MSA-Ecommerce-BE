# Product Search After Analysis

## 목적

Before 분석에서 확인한 상품 검색 병목 중 두 가지를 직접 개선해 보려 했다.

- `LATEST`: `PRIMARY reverse scan`
- `PRICE_ASC`: `table scan + sort`

핵심 가설은 다음과 같았다.

1. `LATEST`는 `category + state + id` 전용 인덱스를 추가하면 더 나은 실행계획을 탈 수 있다.
2. `PRICE_ASC`는 이미 `(category, state, price, id)` 인덱스가 있음에도 optimizer가 content 조회에서 `table scan + sort`를 선택하므로,
   쿼리 shape를 더 구체적으로 쪼개면 optimizer를 설득할 수 있다.

## 시도한 구현

### 1. 검색 경로 분리

기존에는 `search()` 하나가 모든 정렬을 처리했다.

이를 아래처럼 정렬별 전용 경로로 분리했다.

- `searchLatest(...)`
- `searchPriceAsc(...)`
- `searchPriceDesc(...)`

의도:

- `LATEST`, `PRICE_ASC`, `PRICE_DESC`가 같은 검색 API 안에 있더라도
  DB 관점에서는 서로 다른 실행 전략을 가질 수 있게 만들기 위함

### 2. LATEST 전용 인덱스 추가

추가했던 인덱스:

```sql
CREATE INDEX idx_products_category_state_id
    ON products (category, state, id);
```

의도:

- `WHERE category = ? AND state = ? ORDER BY id DESC LIMIT ?`
  패턴에 대해 optimizer가 `PRIMARY reverse scan` 대신
  `category + state + id` 전용 인덱스를 선택하도록 유도

### 3. PRICE_ASC 2단계 조회

`PRICE_ASC`는 다음 구조로 바꿨다.

1. 인덱스를 활용해 `id`만 먼저 조회
2. 그 `id`들로 실제 본문 컬럼을 다시 조회

개념은 아래와 같았다.

```text
1차 조회: category + state + price range + order by price asc + limit -> id 20개
2차 조회: id in (...) -> name, imageUrl, price, seller 정보 조회
```

의도:

- 기존에는 content 조회에서 비인덱스 컬럼까지 한 번에 읽으려다 optimizer가 `table scan + sort`를 선택했다.
- 먼저 `id`만 좁히면 `(category, state, price, id)` 인덱스를 더 명확하게 탈 수 있다고 판단했다.

## 측정 결과

After 기준 run:

- `1775994364`
- `1775995228`

이 중 `1775995228`은 `LATEST` 인덱스 실험을 되돌리고 `PRICE_ASC` 2단계 조회만 남긴 상태의 결과다.

### 기준 Before

- `LATEST`
  - avg `217.94ms`
  - p95 `384.37ms`
- `PRICE_ASC`
  - avg `125.41ms`
  - p95 `212.66ms`
- `PRICE_DESC`
  - avg `111.95ms`
  - p95 `188.74ms`

### LATEST 인덱스 추가 시도 결과

관찰:

- content 조회는 여전히 `PRIMARY reverse scan`
- count만 새 인덱스를 사용
- 그리고 새 인덱스가 `PRICE_ASC`, `PRICE_DESC` 실행계획까지 오염시켰다

결론:

- `LATEST`용으로 추가한 인덱스가 핵심 content 조회를 충분히 개선하지 못했다
- 반대로 price 계열 경로에서 optimizer가 기존 `price` 인덱스 대신
  새 인덱스를 고르는 부작용을 만들었다

즉 `LATEST` 전용 인덱스 추가는 실패한 접근이었다.

### PRICE_ASC 2단계 조회 결과

최종적으로 `LATEST` 인덱스 실험을 제거한 뒤 측정한 결과:

- `PRICE_ASC`
  - Before avg `125.41ms`
  - After avg `128.37ms`
  - Before p95 `212.66ms`
  - After p95 `218.96ms`

즉 `PRICE_ASC`는 오히려 소폭 악화됐다.

## 왜 실패했는가

### 1. LATEST 인덱스 추가는 "한 쿼리 최적화"가 아니라 "다른 쿼리 오염"을 만들었다

추가한 `(category, state, id)` 인덱스는
`LATEST`를 위해 넣었지만, optimizer는 이를 `PRICE_ASC`, `PRICE_DESC` 경로의 count/content에도 활용하려고 시도했다.

즉 인덱스 하나를 추가하면 해당 쿼리만 좋아지는 것이 아니라,
같은 테이블을 쓰는 다른 쿼리의 실행계획까지 바뀔 수 있다는 점을 직접 확인했다.

### 2. PRICE_ASC는 content만 최적화해선 부족했다

2단계 조회 구조는 content 조회를 인덱스 친화적으로 만들려는 시도였다.
하지만 실제 API 전체 비용은 아래처럼 계산됐다.

- 기존:
  - content 조회 1회
  - count 1회
- 변경 후:
  - count 1회
  - id 선조회 1회
  - 본문 조회 1회

즉 `table scan + sort`를 줄이는 대신 DB 왕복이 한 번 늘었다.

현재 데이터 분포와 단일 서버 환경에서는,
기존 `PRICE_ASC`의 병목이 생각보다 치명적이지 않았고
추가된 쿼리 비용이 그 이득을 상쇄했다.

### 3. 진짜 병목 단위는 content만이 아니라 content + count 전체였다

처음에는 `PRICE_ASC` content 조회의 `table scan + sort`에 집중했다.
하지만 실제로는 `Page` 기반 검색이라 count 비용도 항상 포함된다.

즉 content만 최적화한다고 전체 API가 빨라지지 않는다는 사실을 확인했다.

## 배운 점

### 1. "인덱스가 있으니 그 인덱스를 타게 만들면 된다"는 단순한 문제가 아니었다

실제로는:

- 데이터 분포
- selection
- count 존재 여부
- row lookup 비용
- 쿼리 횟수 증가

까지 모두 합쳐서 전체 비용을 봐야 했다.

### 2. 인덱스 추가는 국소 최적화가 아니라 실행계획 전체를 흔드는 작업이다

`LATEST` 전용 인덱스를 추가했을 때,
원래 타던 `price` 인덱스까지 optimizer가 덜 쓰게 되는 부작용을 겪었다.

즉 새 인덱스는 "좋아질 쿼리"만 보는 게 아니라
"같은 테이블을 쓰는 다른 핵심 쿼리까지 어떻게 바꿀지"를 같이 봐야 한다.

### 3. Query shape를 쪼개는 것만으로는 충분하지 않을 수 있다

`PRICE_ASC`는 분명히 더 구체적인 구조로 쪼갰다.
하지만 count를 그대로 둔 상태에선 전체 API 비용이 개선되지 않았다.

즉 실제 최적화는:

- content
- count
- round trip 수
- 페이지네이션 방식

전체를 같이 봐야 한다.

### 4. "문제를 발견하고 버릴 수 있는 것"도 실력이다

이번 시도는 최종 성능 개선으로 이어지지 않았다.
하지만 다음 두 가지를 명확히 확인했다.

1. `LATEST` 인덱스 추가는 현재 구조에선 부작용이 더 크다
2. `PRICE_ASC`는 count까지 포함해 다시 설계하지 않으면 개선이 어렵다

즉 결과가 안 좋을 때도 억지로 밀어붙이지 않고,
측정 결과에 따라 가설을 폐기하는 판단이 필요하다는 점을 배웠다.

### 5. 인덱스만으로는 탐색형 검색 최적화에 한계가 있다

이번 시도를 통해 가장 크게 배운 점은
"탐색형 검색은 인덱스만 잘 설계하면 해결된다"는 생각이 틀릴 수 있다는 점이다.

실제 API 비용은 인덱스 하나로 결정되지 않는다.

- `WHERE` 선택도
- `ORDER BY`
- `LIMIT / OFFSET`
- `COUNT(*)`
- projection 컬럼 수
- 쿼리 횟수
- 데이터 분포

이 요소들이 함께 전체 비용을 만든다.

즉 인덱스는 중요한 수단이지만,
검색 API 전체 구조를 대신 해결해주지는 못한다.

### 6. 옵티마이저를 유도하는 것도 중요하지만, 그 전에 병목 단위를 정확히 잡아야 한다

이번 시도에서는 "optimizer가 `PRICE_ASC`에서 인덱스를 타게 만들자"는 접근으로 시작했다.
그 자체는 타당한 문제의식이었다.

하지만 실제로는 `PRICE_ASC`의 병목이
content 조회 하나가 아니라
`content + count + pagination 비용` 전체였기 때문에,
optimizer 유도만으로는 충분하지 않았다.

즉 튜닝은 단순히 "실행계획을 원하는 모양으로 바꾸는 것"이 아니라,
"사용자 요청 하나를 처리하는 전체 비용 구조를 바꾸는 것"이라는 점을 확인했다.

## 남은 가능성

이번 시도로는 최종 개선을 만들지 못했지만, 다음 후보는 남아 있다.

### 1. `Page -> Slice`

현재 `PRICE_ASC`는 `Page` 기반이라 항상 `count(*)`가 따라붙는다.
그래서 2단계 조회를 해도 전체 API 비용이 잘 줄지 않았다.

`Slice`로 바꾸면 count를 제거할 수 있고,
그때는 2단계 조회의 이득이 실제로 드러날 가능성이 있다.

다만 이 경우 주제는
"ASC 인덱스 최적화"보다는
"탐색형 조회의 페이지네이션 비용 최적화"에 가까워진다.

### 2. Keyset pagination

특히 `LATEST`, `PRICE_ASC`처럼 정렬 중심 탐색은
깊은 페이지로 갈수록 `OFFSET` 비용이 커질 수 있다.

이 경우 keyset pagination이 더 적합할 수 있다.

하지만 API 계약이 바뀌므로,
이번 이력서 불릿 강화 목적과는 주제가 달라진다.

### 3. 검색용 read model 또는 별도 검색 구조

탐색형 검색이 더 복잡해지면
RDB 단일 테이블 인덱스만으로 끝까지 해결하려고 하기보다,
검색용 projection table이나 별도 검색 구조를 고민하는 것이 더 맞을 수 있다.

즉 지금의 실패는 "검색 최적화가 불가능하다"는 뜻이 아니라,
"이번 문제는 인덱스와 Querydsl 수준보다 더 상위의 조회 전략 문제"였다는 뜻에 가깝다.

## 최종 결론

이번 상품 검색 튜닝 시도는 Before 분석은 유의미했지만,
After에서 핵심 가설을 입증하지 못했다.

- `LATEST` 인덱스 추가는 다른 검색 경로의 optimizer 선택을 오염시켰다.
- `PRICE_ASC` 2단계 조회는 count 비용이 남아 있어 전체 성능 개선으로 이어지지 않았다.

따라서 이 변경은 서비스 코드에 남기지 않고 원복했으며,
문제 분석과 실패 원인을 학습 자산으로만 보존했다.

## 한 줄 요약

`PRICE_ASC`의 `table scan + sort`를 보고 쿼리 shape를 더 구체적으로 쪼개 optimizer를 설득하려 했지만,
현재 환경에서는 줄인 비용보다 늘어난 쿼리 비용이 더 커서 개선이 나오지 않았다.
