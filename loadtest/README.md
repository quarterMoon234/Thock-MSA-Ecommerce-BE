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
