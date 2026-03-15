# Product Event Experiment Guide

`product-service` can now switch the product event publishing strategy at runtime.

## Publish modes

- `outbox`
  - Default mode.
  - Product changes are stored in `product_outbox_event`, then published by the poller.
- `direct`
  - Experimental baseline mode.
  - Product changes are sent to Kafka directly after the DB transaction commits.
  - This mode is intended only for before/after reliability experiments.

## Properties

- `product.event.publish-mode=outbox|direct`
- `product.outbox.enabled=true|false`
- `product.outbox.poller.interval-ms=3000`
- `product.outbox.poller.after-send-delay-ms=0`

## Docker examples

Run the default reliable mode:

```bash
PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
PRODUCT_EVENT_PUBLISH_MODE=outbox \
docker compose up -d --build product-service
```

Run the direct baseline mode:

```bash
PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
PRODUCT_EVENT_PUBLISH_MODE=direct \
docker compose up -d --build product-service
```

Make the outbox poller easier to interrupt after Kafka ack:

```bash
PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
PRODUCT_EVENT_PUBLISH_MODE=outbox \
PRODUCT_OUTBOX_POLLER_INTERVAL_MS=15000 \
PRODUCT_OUTBOX_POLLER_AFTER_SEND_DELAY_MS=10000 \
docker compose up -d --build product-service
```

## Recommended experiments

1. `direct` + broker down
   - Compare created product rows against actual `product.changed` messages.
2. `outbox` + broker down
   - Verify `product_outbox_event` stays `PENDING`, then converges to `SENT`.
3. `outbox` + delayed SENT update + app stop
   - Stop `product-service` while the poller is sleeping after Kafka ack.
   - Restart and confirm the same `PENDING` row is retried.

## Grafana

- Dashboard file: `monitoring/grafana/dashboards/product-outbox-dashboard.json`
- Key metrics:
  - `product_outbox_total_count`
  - `product_outbox_status_count{status="PENDING|SENT"}`
  - `product_outbox_pending_ratio_percent`
  - `product_outbox_publish_success_total`
  - `product_outbox_publish_failure_total`

## k6

- Docker service: `docker compose --profile loadtest run --rm k6 run /scripts/product-create-outbox.js`
- Script: `loadtest/product-create-outbox.js`
- Detailed usage: `loadtest/README.md`
- Topic count helper: `loadtest/topic-message-count.sh`
- Full wrapper: `loadtest/run-product-create-experiment.sh`
