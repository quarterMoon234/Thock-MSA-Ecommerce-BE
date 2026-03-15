# Observability Stack Explanation

이 문서는 현재 코드베이스에서 `k6`, `Prometheus`, `Grafana`, `Promtail`, `Loki`가 어떤 역할로 연결되어 있는지, 그리고 실제로 어떤 파일과 코드가 그 구조를 만들고 있는지를 설명합니다.

핵심만 먼저 말하면 이 저장소의 구조는 아래와 같습니다.

- `k6`는 부하를 발생시키는 도구입니다.
- 각 Spring 서비스는 `Actuator + Micrometer + Prometheus registry`로 메트릭을 노출합니다.
- `Prometheus`는 각 서비스의 `/actuator/prometheus`를 주기적으로 긁어서 시계열 메트릭을 저장합니다.
- `Grafana`는 `Prometheus`, `Loki`, `MySQL`을 데이터소스로 붙여 대시보드를 그립니다.
- `Promtail`은 파일 로그를 읽어서 `Loki`로 보내고, Grafana는 이 로그를 조회합니다.
- 즉, 현재 구조는 "k6 결과를 Grafana에 직접 그리는 구조"가 아니라 "k6가 트래픽을 만들어서 애플리케이션 메트릭과 로그를 발생시키고, 그 결과를 Grafana가 보는 구조"입니다.

## 1. 전체 흐름

```text
k6
  -> API Gateway
    -> member/product/market/payment/settlement-service
      -> /actuator/prometheus 로 메트릭 노출
      -> JSON 파일 로그 생성
      -> Kafka/DB 처리

Prometheus
  -> 각 서비스 /actuator/prometheus scrape
  -> Redpanda metrics scrape

Promtail
  -> ./logs/*/*.log 수집
  -> Loki 로 전달

Grafana
  -> Prometheus 에 PromQL 질의
  -> Loki 에 LogQL 질의
  -> MySQL 에 SQL 질의
  -> Dashboard 자동 로드
```

포트폴리오 설명용으로 바꾸면 다음처럼 이해하면 됩니다.

1. `k6`가 실제 사용자처럼 API를 계속 호출합니다.
2. 그 호출 때문에 서비스 내부에서 HTTP 요청 수, 응답 시간, JVM 메모리, 커스텀 비즈니스 메트릭, Kafka 처리 수, 로그가 생성됩니다.
3. `Prometheus`와 `Promtail`이 이 데이터를 모읍니다.
4. `Grafana`가 모아진 데이터를 시각화합니다.

## 2. Docker Compose 에서 어떻게 묶여 있는가

루트 `docker-compose.yml`이 이 구조의 중심입니다.

- 애플리케이션 서비스: `api-gateway`, `member-service`, `product-service`, `market-service`, `payment-service`, `settlement-service`
- 메시지 브로커: `redpanda`
- 모니터링: `prometheus`, `grafana`, `loki`, `promtail`
- 부하 테스트: `k6`, `kcat`

여기서 중요한 포인트는 다음입니다.

### 2.1 k6 는 항상 떠 있는 서비스가 아니다

`k6`는 `profiles: loadtest`로 정의되어 있어 필요할 때만 실행됩니다.

- 평소에는 애플리케이션과 모니터링 스택만 띄웁니다.
- 실험할 때만 `docker compose --profile loadtest run --rm k6 ...` 형태로 실행합니다.

즉 `k6`는 서버가 아니라 "실험용 실행 컨테이너"입니다.

### 2.2 로그는 컨테이너 내부가 아니라 호스트 디렉터리에 남긴다

각 서비스는 다음처럼 로그 볼륨을 마운트합니다.

- `./logs/gateway:/app/logs`
- `./logs/member:/app/logs`
- `./logs/product:/app/logs`
- `./logs/market:/app/logs`
- `./logs/payment:/app/logs`
- `./logs/settlement:/app/logs`

그래서 Spring 서비스가 파일 로그를 쓰면, 호스트의 `./logs/...` 아래에 실제 파일이 생기고, `promtail`이 그 파일들을 다시 읽을 수 있습니다.

## 3. Spring 서비스는 왜 Prometheus 메트릭을 내보낼 수 있는가

현재 프로젝트는 `common` 모듈에 메트릭 관련 의존성을 넣어 두었습니다.

- `common/build.gradle`
  - `spring-boot-starter-actuator`
  - `micrometer-registry-prometheus`

대부분의 서비스는 `implementation project(':common')`로 이 의존성을 가져갑니다.  
`api-gateway`는 예외적으로 own dependency를 다시 선언하지만 결과적으로 동일하게 actuator/prometheus 기능을 갖습니다.

즉 원리는 아래와 같습니다.

1. `Actuator`가 운영용 엔드포인트를 제공합니다.
2. `Micrometer`가 JVM, HTTP, DB pool 같은 메트릭을 수집합니다.
3. `Prometheus registry`가 그 메트릭을 Prometheus 형식으로 변환합니다.
4. `/actuator/prometheus`에서 텍스트 형태로 노출합니다.

## 4. 각 서비스에서 메트릭 endpoint 를 노출하는 설정

각 서비스의 `application.yml`에는 공통적으로 아래 설정이 들어 있습니다.

- `management.endpoints.web.exposure.include: health,info,prometheus,metrics`
- `management.metrics.tags.application: ${spring.application.name}`

이 설정이 중요한 이유는 다음과 같습니다.

### 4.1 `/actuator/prometheus`가 열려야 Prometheus 가 읽을 수 있다

예를 들어 `product-service`와 `market-service`는 `/actuator/prometheus`를 공개합니다.  
`api-gateway`는 여기에 `gateway` endpoint도 추가로 열어 둡니다.

### 4.2 `application` 태그를 강제로 붙여서 서비스별 집계를 쉽게 만든다

`management.metrics.tags.application: ${spring.application.name}` 때문에 Prometheus 메트릭에 `application="product-service"` 같은 태그가 붙습니다.

이 덕분에 Grafana 대시보드에서 다음 같은 질의를 쉽게 할 수 있습니다.

- `http_server_requests_seconds_count{application="settlement-service"}`
- `jvm_memory_used_bytes{application="settlement-service"}`

즉 이 한 줄이 "서비스별 그래프"를 만들기 쉽게 해 줍니다.

## 5. Prometheus 는 무엇을 어떻게 수집하는가

수집 설정은 `monitoring/prometheus/prometheus.yml`에 있습니다.

여기서 Prometheus 는 다음 타겟을 주기적으로 scrape 합니다.

- `api-gateway:8080/actuator/prometheus`
- `member-service:8081/actuator/prometheus`
- `product-service:8082/actuator/prometheus`
- `market-service:8083/actuator/prometheus`
- `payment-service:8084/actuator/prometheus`
- `settlement-service:8085/actuator/prometheus`
- `redpanda:9644`

즉 Grafana가 보는 메트릭은 대부분 Prometheus가 직접 긁어 온 것입니다.

### 5.1 기본 메트릭

Spring Boot + Micrometer만으로도 이미 여러 메트릭이 나옵니다.

- HTTP 요청 수: `http_server_requests_seconds_count`
- 응답 시간 관련 메트릭
- JVM 메모리: `jvm_memory_used_bytes`, `jvm_memory_max_bytes`
- DB connection pool: `hikaricp_*`

그래서 별도 코드 없이도 `spring-boot-overview.json`, 일부 settlement dashboard 같은 화면을 만들 수 있습니다.

### 5.2 Redpanda 메트릭

`redpanda:9644`도 Prometheus가 긁습니다.  
Kafka consumer lag 대시보드는 이 Redpanda 메트릭을 기반으로 계산합니다.

예를 들어 `monitoring/grafana/dashboards/kafka-consumer-lag-ko.json`은 다음 계열 메트릭을 사용합니다.

- `vectorized_cluster_partition_last_stable_offset`
- `vectorized_kafka_group_offset`

즉 이 대시보드는 애플리케이션 코드가 직접 만든 메트릭이 아니라 Redpanda가 제공하는 브로커 메트릭을 시각화한 것입니다.

## 6. 이 프로젝트가 직접 만든 커스텀 메트릭

기본 Spring 메트릭만으로는 outbox 상태, Kafka inbound 처리 현황 같은 비즈니스 지표를 보기 어렵기 때문에 몇 군데서 직접 Micrometer 메트릭을 등록했습니다.

### 6.1 Product Service: Outbox 상태 메트릭

`product-service`에는 두 종류의 커스텀 메트릭 코드가 있습니다.

#### A. Outbox publish 성공/실패 카운터

`product-service/src/main/java/com/thock/back/product/monitoring/ProductOutboxPublishMetrics.java`

- `product_outbox_publish_success_total`
- `product_outbox_publish_failure_total`

이 카운터는 `ProductOutboxPoller`에서 실제 Kafka 발행 성공/실패 시점에 증가합니다.

즉 흐름은 아래와 같습니다.

1. 상품 변경 이벤트를 outbox 테이블에 저장
2. `ProductOutboxPoller`가 `PENDING` 이벤트를 조회
3. Kafka 전송 성공 시 success counter 증가
4. Kafka 전송 실패 시 failure counter 증가

#### B. Outbox 현황 Gauge

`product-service/src/main/java/com/thock/back/product/monitoring/ProductOutboxMetricsCollector.java`

이 클래스는 `@Scheduled`로 주기적으로 DB를 조회하여 Gauge 를 업데이트합니다.

- `product_outbox_total_count`
- `product_outbox_pending_ratio_percent`
- `product_outbox_status_count{status=...}`
- `product_outbox_status_ratio_percent{status=...}`

즉 이 메트릭은 "요청이 들어올 때마다 증가"하는 것이 아니라 "현재 DB 상태를 주기적으로 스냅샷"하는 방식입니다.

### 6.2 Market Service: 주문/Outbox/Kafka lag/Inbound 메트릭

`market-service`는 커스텀 메트릭이 더 많습니다.

#### A. 배치성 수집 Gauge

`market-service/src/main/java/com/thock/back/market/monitoring/MarketMetricsCollector.java`

이 클래스는 주기적으로 다음을 수집합니다.

- 주문 총 건수: `market_order_total_count`
- 주문 상태별 건수: `market_order_state_count{state=...}`
- outbox 총 건수: `market_outbox_total_count`
- outbox 상태별 건수/비율
- backlog/failed 비율
- Kafka consumer lag total
- topic 별 Kafka lag

특히 Kafka lag 는 `AdminClient`로 consumer group offset 과 latest offset 을 비교해서 계산합니다.

즉 "market-service 내부 관점의 lag"를 자체 메트릭으로도 만들고 있습니다.

#### B. Kafka inbound 처리 카운터

`market-service/src/main/java/com/thock/back/market/monitoring/MarketKafkaInboundMetrics.java`

`MarketKafkaListener`가 이벤트를 받을 때 아래 카운터를 올립니다.

- `market_kafka_inbound_received_total`
- `market_kafka_inbound_processed_total`
- `market_kafka_inbound_duplicate_total`
- `market_kafka_inbound_failed_total`

이 덕분에 Grafana에서 "메시지를 얼마나 받았는지", "중복으로 스킵한 건 몇 개인지", "실패가 얼마나 났는지"를 topic 단위로 볼 수 있습니다.

## 7. Product 이벤트 실험에서 direct 와 outbox 를 어떻게 비교하는가

이 저장소에서 `k6` 도입 목적은 단순 부하 테스트가 아니라 `product-service`의 이벤트 발행 전략 비교 실험에 가깝습니다.

### 7.1 publish mode 전환

`product-service`는 `product.event.publish-mode` 값에 따라 구현체가 바뀝니다.

- `outbox`
  - `ProductOutboxEventPublisher`
  - DB의 `product_outbox_event` 테이블에 이벤트를 저장
  - 이후 `ProductOutboxPoller`가 Kafka로 발행
- `direct`
  - `ProductDirectKafkaEventPublisher`
  - 트랜잭션 commit 이후 Kafka로 직접 발행

즉 같은 기능을 두 방식으로 실행하고, 실험 중에 브로커 장애 상황 등을 걸어서 차이를 비교할 수 있게 해 둔 구조입니다.

### 7.2 experiment profile

`product-service/src/main/resources/application-experiment.yml`은 실험용 오버라이드입니다.

- `product.metrics.collect-interval-ms`
- `product.event.publish-mode`
- `product.outbox.enabled`
- `product.outbox.poller.interval-ms`
- `product.outbox.poller.after-send-delay-ms`

즉 실험 시에는 메트릭 수집 주기, poller 간격, 지연 시간을 따로 조절할 수 있습니다.

## 8. k6 는 실제로 무엇을 하는가

`loadtest/product-create-outbox.js`가 핵심 스크립트입니다.

### 8.1 setup 단계

`setup()`은 테스트용 seller 계정을 준비합니다.

- 전달받은 `SELLER_ACCESS_TOKEN`이 있으면 그 토큰을 사용
- 없으면 회원가입
- 로그인
- seller 권한 승격
- 다시 로그인 후 access token 확보

즉 k6는 단순히 URL만 때리는 것이 아니라, 실제 인증 흐름을 거쳐 상품 생성 가능한 계정을 만든 뒤 요청을 보냅니다.

### 8.2 본 실행 단계

기본 함수에서는 `/api/v1/products/create`로 POST 요청을 보냅니다.

- `BASE_URL` 기본값은 `http://api-gateway:8080`
- 즉 k6는 보통 `api-gateway`를 통해 전체 시스템에 들어갑니다
- `constant-arrival-rate` 시나리오를 써서 초당 요청 수를 일정하게 유지합니다

핵심 옵션은 다음입니다.

- `RATE`
- `DURATION`
- `PRE_ALLOCATED_VUS`
- `MAX_VUS`

즉 이 스크립트는 "몇 명의 가상 사용자를 동시에 붙일지"보다 "초당 몇 건의 생성 요청을 계속 넣을지"에 초점을 둔 구성입니다.

### 8.3 k6 자체 메트릭과 summary

이 스크립트는 k6 내부 카운터도 정의합니다.

- `products_created_total`
- `product_create_failures_total`

그리고 `handleSummary()`에서 결과를 두 군데로 남깁니다.

- 표준 출력
- `/results/...json`

즉 `loadtest/results/*.json`은 k6 summary 원본입니다.

중요한 점은 이 저장소에서는 k6 summary가 Prometheus 로 전송되지 않는다는 것입니다.  
현재 `docker-compose.yml`에는 `k6 -> Prometheus remote write`나 `InfluxDB` 같은 출력 설정이 없습니다.

그래서 현재 시각화는 "k6 고유 메트릭 대시보드"라기보다 "k6가 발생시킨 부하에 대한 시스템 반응 대시보드"입니다.

## 9. loadtest shell script 는 왜 필요한가

`loadtest/run-product-create-experiment.sh`는 실험 자동화 스크립트입니다.

이 스크립트는 다음 순서로 동작합니다.

1. 필요한 컨테이너 기동
2. gateway health check 대기
3. 테스트 시작 전 Kafka topic 메시지 수 측정
4. `k6 run /scripts/product-create-outbox.js` 실행
5. 테스트 종료 후 Kafka topic 메시지 수 재측정
6. MySQL에서 생성된 상품 수, outbox row 수, `SENT/PENDING` 수 조회
7. 최종 비교 지표 출력

즉 이 스크립트의 목적은 단순 TPS 측정이 아니라 다음 질문에 답하는 것입니다.

- 요청 수만큼 실제 상품이 생성되었는가?
- 생성된 상품 수만큼 Kafka 이벤트가 발행되었는가?
- outbox 테이블이 어디까지 처리되었는가?
- direct 방식과 outbox 방식의 신뢰성 차이는 무엇인가?

여기서 나오는 최종 비교 값은 다음과 같습니다.

- `kafka_published_delta`
- `db_products_created`
- `db_outbox_rows`
- `db_outbox_sent`
- `db_outbox_pending`
- `db_vs_kafka_gap`

즉 포트폴리오에서는 "부하 테스트를 했다"보다 "이벤트 유실/정합성을 비교하는 실험 환경을 만들었다"는 설명이 더 정확합니다.

## 10. 로그는 어떻게 Grafana 까지 가는가

이 프로젝트는 메트릭만 보는 것이 아니라 로그도 시각화합니다.

### 10.1 로그 생성

`common/src/main/resources/logback-base.xml`에 공통 JSON appender 들이 정의되어 있습니다.

- `APP_FILE`
- `KAFKA_EVENT_FILE`
- `SPRING_EVENT_FILE`
- `API_FILE`
- `SERVICE_INFO_FILE`
- `SERVICE_ERROR_FILE`

각 appender는 `logstash-logback-encoder`를 써서 JSON 로그를 파일로 남깁니다.

즉 로그가 plain text 가 아니라 JSON 이기 때문에, 이후 Promtail/Loki에서 필드 추출이 쉬워집니다.

### 10.2 서비스별 logger 분리

각 서비스의 `logback-spring.xml`은 어떤 logger 를 어떤 파일로 보낼지 정합니다.

예시:

- `market-service`
  - `MarketKafkaListener` -> `kafka-event.log`
  - `MarketEventListener` -> `spring-event.log`
  - API controller -> `api.log`
- `product-service`
  - `ProductController` -> `api.log`

즉 Grafana 로그 화면에서 "API 로그", "Kafka 이벤트 로그", "Spring 이벤트 로그"를 분리해서 볼 수 있는 이유가 여기 있습니다.

### 10.3 Promtail 수집

`monitoring/promtail/promtail-config.yml`은 각 로그 파일 경로를 읽습니다.

예를 들면:

- `/var/log/apps/product/app*.log`
- `/var/log/apps/product/api*.log`
- `/var/log/apps/market/kafka-event*.log`

그리고 JSON 파싱 후 아래 라벨을 붙입니다.

- `job`
- `service`
- `log_type`
- `level`
- `logger`

즉 Grafana의 로그 대시보드에서 `service=product`, `log_type=api`, `level=ERROR` 같은 조건으로 필터링할 수 있는 이유가 바로 이 라벨링 때문입니다.

### 10.4 Loki 와 Grafana

`promtail`은 로그를 `loki`로 보냅니다.  
Grafana는 `monitoring/grafana/provisioning/datasources/datasources.yml`에서 `Loki` 데이터소스를 자동 등록합니다.

그래서 `monitoring/grafana/dashboards/logs-dashboard.json` 같은 대시보드는 LogQL 로 로그를 조회합니다.

특히 `payment-service-dashboard.json`은 Prometheus 메트릭이 아니라 Loki 로그를 기반으로 건수와 금액을 계산하는 패널도 포함합니다.  
즉 Grafana 대시보드라고 해서 모두 Prometheus 기반은 아닙니다.

## 11. Grafana 는 어떻게 자동 설정되는가

Grafana 관련 설정은 두 단계로 들어갑니다.

### 11.1 datasource provisioning

`monitoring/grafana/provisioning/datasources/datasources.yml`

현재 자동 등록되는 데이터소스는 다음입니다.

- `Prometheus`
- `Loki`
- `Settlement MySQL`

즉 Grafana UI에서 수동으로 datasource 를 만들 필요가 없습니다.

### 11.2 dashboard provisioning

`monitoring/grafana/provisioning/dashboards/dashboards.yml`

이 설정이 `/var/lib/grafana/dashboards` 폴더의 JSON 파일들을 자동 로드합니다.

그래서 다음 파일들이 Grafana 대시보드로 바로 나타납니다.

- `spring-boot-overview.json`
- `product-outbox-dashboard.json`
- `market-service-dashboard.json`
- `kafka-consumer-lag-ko.json`
- `logs-dashboard.json`
- `payment-service-dashboard.json`
- settlement 관련 dashboard 들

즉 대시보드도 코드로 관리되고 있습니다.

## 12. 대시보드별로 실제 데이터 출처가 다르다

이 부분이 가장 많이 헷갈립니다.

### 12.1 Prometheus 기반 대시보드

- `spring-boot-overview.json`
- `product-outbox-dashboard.json`
- `market-service-dashboard.json`
- `kafka-consumer-lag-ko.json`
- settlement 운영 대시보드 일부

이들은 PromQL 을 사용합니다.

### 12.2 Loki 기반 대시보드

- `logs-dashboard.json`
- `payment-service-dashboard.json`의 상당수 패널

이들은 LogQL 을 사용합니다.

### 12.3 MySQL 기반 대시보드

- 일부 settlement dashboard

이들은 Grafana의 MySQL datasource 로 직접 SQL 조회를 합니다.

즉 현재 Grafana는 "Prometheus 전용 화면"이 아니라 "메트릭 + 로그 + SQL 결과"를 함께 보는 통합 대시보드 역할을 합니다.

## 13. 포트폴리오에서 이렇게 설명하면 자연스럽다

아래 식으로 설명하면 코드베이스와 잘 맞습니다.

> k6로 API Gateway를 통해 실제 상품 생성 요청을 지속적으로 발생시켰고,  
> Spring Boot Actuator와 Micrometer로 노출한 애플리케이션 메트릭을 Prometheus가 수집하도록 구성했습니다.  
> Grafana에서는 Prometheus 메트릭뿐 아니라 Loki 로그와 MySQL datasource도 함께 사용해  
> HTTP 성능, JVM 상태, Outbox 적체, Kafka consumer lag, 비즈니스 로그를 한 화면에서 분석할 수 있게 만들었습니다.  
> 특히 product-service는 direct publish와 outbox publish를 실험적으로 전환할 수 있도록 구현하여,  
> 부하 상황과 브로커 장애 상황에서 이벤트 유실 여부를 DB/Kafka/메트릭 기준으로 비교할 수 있게 했습니다.

## 14. 현재 코드 기준으로 알아두면 좋은 점

### 14.1 k6 메트릭이 Grafana 로 직접 들어가지는 않는다

현재 설정에는 다음이 없습니다.

- `k6 experimental-prometheus-rw`
- InfluxDB output
- k6 exporter

즉 Grafana가 직접 보는 것은 `k6 내부 메트릭`이 아니라 `k6가 유발한 시스템 메트릭/로그`입니다.

### 14.2 Prometheus alert rule 은 설정 선언과 compose mount 를 같이 봐야 한다

`monitoring/prometheus/prometheus.yml`에는 아래 rule file 이 선언되어 있습니다.

- `/etc/prometheus/rules/http-error-rate-rules.yml`

하지만 현재 루트 `docker-compose.yml`의 Prometheus volume 정의만 보면 `prometheus.yml`만 직접 마운트하고 있습니다.  
즉 rule 파일이 실제 컨테이너에 함께 들어가는지는 한 번 더 확인이 필요합니다.

다시 말해 "시계열 수집과 시각화"는 분명히 구현되어 있지만, "알림 규칙까지 완전하게 연결되었는지"는 compose volume 정의까지 같이 점검해야 합니다.

## 15. 한 줄 정리

현재 이 저장소의 관측성 구조는 다음 한 문장으로 요약할 수 있습니다.

> `k6`가 시스템에 부하를 만들고, Spring 서비스가 `Actuator + Micrometer`로 메트릭을 노출하며, `Prometheus`와 `Promtail/Loki`가 메트릭과 로그를 수집하고, `Grafana`가 이를 대시보드로 시각화하는 구조입니다.
