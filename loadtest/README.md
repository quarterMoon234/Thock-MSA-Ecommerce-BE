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

Run the Redis Lua stock gate Before/After wrapper.

```bash
bash loadtest/run-product-stock-redis-experiment.sh
```

This wrapper runs the same stock reservation scenario twice under identical counts:

- `redis-off`: `PRODUCT_STOCK_REDIS_ENABLED=false`
- `redis-on`: `PRODUCT_STOCK_REDIS_ENABLED=true`
- Redis is flushed before each phase
- The k6 setup creates a fresh product for each run and explicitly calls the experiment rebuild endpoint in the `redis-on` phase

Useful overrides:

- `STOCK_EXPERIMENT_COUNTS="1000"`
- `INITIAL_STOCK=10`
- `QUANTITY=1`
- `STOCK_EXPERIMENT_EXECUTOR=per-vu`
- `STOCK_EXPERIMENT_EXECUTOR=shared K6_VUS=200`

Run the cart CQRS before/after experiment with one k6 script.

```bash
bash loadtest/run-cart-cqrs-experiment.sh
```

This wrapper enables the `experiment` profile for `product-service` and `market-service`, then runs one k6 script that executes these scenarios sequentially:

- `sync_read`
- `cqrs_read`
- `sync_read_delay`
- `cqrs_read_delay`
- `sync_add`
- `cqrs_add`
- `sync_add_delay`
- `cqrs_add_delay`

The k6 `setup()` does all dataset preparation:

- creates fresh experiment products in `product-service`
- seeds read/add member pools in `market-service`
- syncs the cart CQRS projection for the requested product IDs

Useful overrides:

- `K6_SCENARIO_VUS=30`
- `K6_SCENARIO_DURATION=20s`
- `K6_SCENARIO_SLOT_SECONDS=25`
- `K6_PRODUCT_COUNT=3`
- `K6_PRODUCT_DELAY_MS=900`
- `K6_READ_MEMBER_COUNT=50`
- `K6_ADD_MEMBER_COUNT_PER_SCENARIO=100`
- `K6_PRODUCT_STOCK=100000`

Key output fields:

- `sync_read.avg`, `cqrs_read.avg`
- `sync_read_delay.avg`, `cqrs_read_delay.avg`
- `sync_add.avg`, `cqrs_add.avg`
- `sync_add_delay.avg`, `cqrs_add_delay.avg`
- `*_throughput`
- `*_success_rate`
- `read_avg_improvement_pct`
- `read_delay_avg_improvement_pct`
- `add_avg_improvement_pct`
- `add_delay_avg_improvement_pct`

Run the Kafka partition before/after experiment with one wrapper.

```bash
bash loadtest/run-partition-experiment.sh
```

This wrapper compares two phases under the same event volume:

- `single`: single-partition topic + listener concurrency `1`
- `multi`: multi-partition topic + listener concurrency `3`

Each phase:

- creates fresh experiment products in `product-service`
- resets the in-memory processing recorder
- publishes `RESERVE -> COMMIT` stock events with `orderNumber` as the Kafka key
- polls until all expected events are processed

Measurement notes:

- `totalDurationMillis` starts immediately before Kafka event publish and ends when the last event is processed
- product creation/setup time is excluded from the before/after comparison
- `orderingViolationCount=0` means `RESERVE -> COMMIT` order was preserved for every `orderNumber`

Useful overrides:

- `RUN_ID=1777000000`
- `EXPERIMENT_TOTAL_EVENTS=3000`
- `EXPERIMENT_PRODUCT_COUNT=12`
- `EXPERIMENT_QUANTITY=1`
- `EXPERIMENT_STOCK=100000`
- `PARTITION_EXPERIMENT_SINGLE_CONCURRENCY=1`
- `PARTITION_EXPERIMENT_MULTI_CONCURRENCY=3`
- `PARTITION_EXPERIMENT_SINGLE_TOPIC=market.order.stock.changed.experiment.single`
- `PARTITION_EXPERIMENT_MULTI_TOPIC=market.order.stock.changed.experiment.multi`

Key result fields:

- `single.summary.throughputEventsPerSecond`
- `multi.summary.throughputEventsPerSecond`
- `single.summary.totalDurationMillis`
- `multi.summary.totalDurationMillis`
- `single.summary.orderingViolationCount`
- `multi.summary.orderingViolationCount`
- `improvements.throughputEventsPerSecondPct`
- `improvements.totalDurationMsPct`

Run the Outbox recovery experiment with one wrapper.

```bash
bash loadtest/run-product-outbox-recovery-experiment.sh
```

This wrapper runs a fixed-iteration product create load while `redpanda` is stopped, then restarts `product-service` after broker recovery and measures how fast run-scoped outbox rows converge from `PENDING` to `SENT`.

Flow:

- start stack in `outbox` publish mode
- capture `product.changed` start offset
- stop `redpanda`
- run fixed-iteration product create load
- verify run-scoped outbox rows accumulated as `PENDING`
- start `redpanda` again
- restart `product-service`
- poll until run-scoped outbox rows are fully `SENT` and topic messages match created product rows

Useful overrides:

- `RUN_ID=1778000000`
- `K6_ITERATIONS=2400`
- `K6_VUS=50`
- `K6_STOCK=5`
- `PRODUCT_OUTBOX_POLLER_INTERVAL_MS=1000`
- `RECOVERY_POLL_INTERVAL_SECONDS=1`
- `RECOVERY_POLL_TIMEOUT_SECONDS=300`

Key result fields:

- `beforeRecovery.dbProductsCreated`
- `beforeRecovery.pendingCount`
- `afterRecovery.sentCount`
- `afterRecovery.matchedUniqueProducts`
- `afterRecovery.missingPublishedEvent`
- `recovery.durationMillis`
- `validations.pendingAccumulatedBeforeRecovery`
- `validations.allOutboxSentAfterRecovery`
- `validations.noEventLoss`

Run the direct-vs-outbox reliability comparison with one wrapper.

```bash
bash loadtest/run-product-outbox-before-after-experiment.sh
```

This wrapper runs two phases under the same fixed-iteration create load:

- `before`: `direct` publish mode + broker down during create + `product-service` restart before broker recovery
- `after`: `outbox` publish mode + broker down during create + broker recovery + `product-service` restart

Key comparison fields:

- `before.dbProductsCreated`
- `before.matchedUniqueProducts`
- `before.missingPublishedEvent`
- `after.beforeRecovery.pendingCount`
- `after.afterRecovery.sentCount`
- `after.afterRecovery.matchedUniqueProducts`
- `after.afterRecovery.missingPublishedEvent`
- `after.recovery.durationMillis`
- `validations.beforeLostEventsDetected`
- `validations.afterNoEventLoss`

Run the inbox duplicate-consumption comparison with one wrapper.

```bash
bash loadtest/run-product-inbox-before-after-experiment.sh
```

This wrapper runs two phases against the same duplicate `RESERVE` event workload:

- `before`: `product.inbox.enabled=false`
- `after`: `product.inbox.enabled=true`

The wrapper creates one experiment product per phase, publishes the same stock-change event repeatedly to a dedicated experiment topic, then compares reserved stock changes and inbox record counts.

Key comparison fields:

- `before.summary.processedCount`
- `before.summary.duplicateSkippedCount`
- `before.summary.reservedDelta`
- `before.summary.appliedReservationCount`
- `after.summary.processedCount`
- `after.summary.duplicateSkippedCount`
- `after.summary.reservedDelta`
- `after.summary.appliedReservationCount`
- `after.summary.inboxRecordCount`

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
