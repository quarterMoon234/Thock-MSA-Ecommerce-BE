# k6 Load Test

This directory contains Docker-based `k6` scripts for the product event experiments.

## Run

Start the application stack first.

```bash
docker compose up -d mysql redpanda member-service product-service api-gateway prometheus grafana
```

Run the product create load test through the dedicated `k6` service.

```bash
docker compose --profile loadtest run --rm \
  -e EXPERIMENT_NAME=direct-baseline \
  -e RATE=20 \
  -e DURATION=120s \
  k6 run /scripts/product-create-outbox.js
```

Run the full experiment wrapper with Kafka topic counting and DB comparison.

```bash
bash loadtest/run-product-create-experiment.sh
```

Run the product Redis cache Before/After experiment.

```bash
TARGET=internal PRODUCT_IDS=1,2,3 K6_ITERATIONS=3000 K6_VUS=50 \
bash loadtest/run-product-cache-experiment.sh
```

This wrapper runs the same `k6` load twice:

- `cache-off`: restarts `product-service` with `PRODUCT_CACHE_ENABLED=false`
- `cache-on`: restarts `product-service` with `PRODUCT_CACHE_ENABLED=true`
- Redis is flushed before each phase with `redis-cli FLUSHDB`
- The default executor is `shared-iterations`, so the cache-off/on business request count is fixed
- Summary files are written under `loadtest/results/`

Use `product_detail_reads_total` or `product_internal_list_reads_total` as the business request count.
`http_reqs_count` includes setup/warm-up requests.

Supported cache targets:

- `TARGET=detail`: `GET /api/v1/products/{id}` directly to `product-service`
- `TARGET=internal`: `POST /api/v1/products/internal/list` directly to `product-service`
- `TARGET=mixed`: alternates detail and internal list reads

If you want to measure the external route including Gateway overhead, override:

```bash
TARGET=detail K6_DETAIL_BASE_URL=http://api-gateway:8080 \
bash loadtest/run-product-cache-experiment.sh
```

Run the pessimistic-lock stock reservation baseline before Redis Lua.

```bash
bash loadtest/run-product-stock-baseline-experiment.sh
```

This wrapper enables the product-service `experiment` profile and runs 100, 500, and 1,000 reservation attempts against a product with stock `10`.
The baseline intentionally disables product Redis cache with `PRODUCT_CACHE_ENABLED=false` so the current DB pessimistic-lock path is measured.
The default executor is `per-vu-iterations`, so each attempt is executed by a dedicated VU once.

Useful overrides:

- `STOCK_EXPERIMENT_COUNTS="100 500 1000"`
- `INITIAL_STOCK=10`
- `QUANTITY=1`
- `STOCK_EXPERIMENT_EXECUTOR=per-vu`
- `STOCK_EXPERIMENT_EXECUTOR=shared K6_VUS=200`

Use these metrics as the baseline:

- `stock_reservation_attempts_total`: business reservation request count
- `stock_reservation_accepted_total`: should match available stock, for example `10`
- `stock_reservation_rejected_total`: should be total attempts minus accepted count
- `stock_reservation_failures_total`: should be `0`
- `stock_reservation_duration_avg`
- `stock_reservation_duration_p95`

Do not use `http_reqs` as the reservation attempt count because k6 includes setup and teardown HTTP calls.

Reset the product experiment state cleanly before each run.

```bash
bash loadtest/reset-product-experiment.sh
```

## Useful overrides

- `BASE_URL=http://api-gateway:8080`
- `SELLER_ACCESS_TOKEN=<token>`
- `RATE=20`
- `DURATION=120s`
- `PRE_ALLOCATED_VUS=20`
- `MAX_VUS=100`
- `PRICE=1000`
- `SALE_PRICE=0`
- `STOCK=5`
- `TARGET=internal`
- `PRODUCT_IDS=1,2,3`
- `PRODUCT_BATCH_SIZE=3`
- `WARM_CACHE=true`
- `K6_EXECUTOR=iterations`
- `K6_ITERATIONS=3000`
- `K6_VUS=50`
- `K6_DETAIL_BASE_URL=http://product-service:8082`
- `SUMMARY_PATH=/results/direct-baseline-summary.json`
- `RUN_ID=1742000000`

## Compare direct vs outbox

Direct mode:

```bash
PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
PRODUCT_EVENT_PUBLISH_MODE=direct \
docker compose up -d --build product-service

docker compose --profile loadtest run --rm \
  -e EXPERIMENT_NAME=direct-broker-down \
  -e RATE=20 \
  -e DURATION=180s \
  -e SUMMARY_PATH=/results/direct-broker-down.json \
  k6 run /scripts/product-create-outbox.js
```

Outbox mode:

```bash
PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
PRODUCT_EVENT_PUBLISH_MODE=outbox \
docker compose up -d --build product-service

docker compose --profile loadtest run --rm \
  -e EXPERIMENT_NAME=outbox-broker-down \
  -e RATE=20 \
  -e DURATION=180s \
  -e SUMMARY_PATH=/results/outbox-broker-down.json \
  k6 run /scripts/product-create-outbox.js
```

Stop `redpanda` during the run to compare event loss behavior.

## Kafka count helpers

Current total message count in `product.changed`:

```bash
bash loadtest/topic-message-count.sh product.changed
```

End-to-end experiment output includes:

- `topic_new_messages`
- `matched_topic_messages`
- `matched_unique_products`
- `duplicate_topic_messages`
- `unrelated_topic_messages`
- `db_products_created`
- `missing_published_event`

Re-format a completed run cleanly:

```bash
bash loadtest/summarize-product-run.sh 1773509307 0
```

Reset product topic and product DB tables, then verify all counts are zero:

```bash
bash loadtest/reset-product-experiment.sh
```

Optional:

- `RESET_PROMETHEUS_DATA=true bash loadtest/reset-product-experiment.sh`
