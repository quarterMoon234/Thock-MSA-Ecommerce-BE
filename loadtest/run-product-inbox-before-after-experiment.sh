#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "required command not found: $1" >&2
    exit 1
  fi
}

require_cmd docker
require_cmd jq

RUN_ID="${RUN_ID:-$(date +%s)}"
PRODUCT_SERVICE_BASE_URL="${PRODUCT_SERVICE_BASE_URL:-http://product-service:8082}"
PRODUCT_SERVICE_HEALTH_URL="${PRODUCT_SERVICE_HEALTH_URL:-${PRODUCT_SERVICE_BASE_URL}/actuator/health}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-40}"
WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS:-2}"
WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES:-3}"

EXPERIMENT_DUPLICATE_COUNT="${EXPERIMENT_DUPLICATE_COUNT:-100}"
EXPERIMENT_QUANTITY="${EXPERIMENT_QUANTITY:-1}"
EXPERIMENT_STOCK="${EXPERIMENT_STOCK:-100}"
EXPERIMENT_POLL_INTERVAL_MS="${EXPERIMENT_POLL_INTERVAL_MS:-1000}"
EXPERIMENT_POLL_TIMEOUT_SECONDS="${EXPERIMENT_POLL_TIMEOUT_SECONDS:-120}"
INBOX_EXPERIMENT_TOPIC="${PRODUCT_INBOX_EXPERIMENT_TOPIC:-market.order.stock.changed.experiment.inbox}"

BEFORE_CONSUMER_GROUP="product-inbox-experiment-${RUN_ID}-before"
AFTER_CONSUMER_GROUP="product-inbox-experiment-${RUN_ID}-after"

RESULT_BEFORE="loadtest/results/product-inbox-before-${RUN_ID}.json"
RESULT_AFTER="loadtest/results/product-inbox-after-${RUN_ID}.json"
RESULT_COMBINED="loadtest/results/product-inbox-before-after-${RUN_ID}.json"

wait_for_product_service() {
  docker compose --profile loadtest run --no-deps --rm \
    -e WAIT_URL="${PRODUCT_SERVICE_HEALTH_URL}" \
    -e WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS}" \
    -e WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS}" \
    -e WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES}" \
    k6 run /scripts/wait-http.js >/dev/null
}

restart_product_service() {
  local phase="$1"
  local inbox_enabled="$2"
  local consumer_group="$3"

  echo "phase=${phase}"
  echo "product_inbox_enabled=${inbox_enabled}"
  echo "product_inbox_experiment_topic=${INBOX_EXPERIMENT_TOPIC}"
  echo "product_inbox_experiment_consumer_group=${consumer_group}"

  PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
  PRODUCT_INBOX_ENABLED="${inbox_enabled}" \
  PRODUCT_STOCK_REDIS_ENABLED=false \
  PRODUCT_KAFKA_LISTENER_CONCURRENCY=1 \
  PRODUCT_INBOX_EXPERIMENT_TOPIC="${INBOX_EXPERIMENT_TOPIC}" \
  PRODUCT_INBOX_EXPERIMENT_CONSUMER_GROUP="${consumer_group}" \
  docker compose up -d --build product-service

  wait_for_product_service
}

run_phase() {
  local phase="$1"
  local inbox_enabled="$2"
  local consumer_group="$3"
  local result_path="$4"
  local phase_run_id="${RUN_ID}-${phase}"
  local summary_json

  restart_product_service "${phase}" "${inbox_enabled}" "${consumer_group}"

  docker compose --profile loadtest run --no-deps --rm \
    -e RUN_ID="${RUN_ID}" \
    -e EXPERIMENT_PHASE="${phase}" \
    -e PRODUCT_SERVICE_BASE_URL="${PRODUCT_SERVICE_BASE_URL}" \
    -e EXPERIMENT_TOPIC="${INBOX_EXPERIMENT_TOPIC}" \
    -e EXPERIMENT_CONSUMER_GROUP="${consumer_group}" \
    -e EXPERIMENT_DUPLICATE_COUNT="${EXPERIMENT_DUPLICATE_COUNT}" \
    -e EXPERIMENT_QUANTITY="${EXPERIMENT_QUANTITY}" \
    -e EXPERIMENT_STOCK="${EXPERIMENT_STOCK}" \
    -e EXPERIMENT_POLL_INTERVAL_MS="${EXPERIMENT_POLL_INTERVAL_MS}" \
    -e EXPERIMENT_POLL_TIMEOUT_SECONDS="${EXPERIMENT_POLL_TIMEOUT_SECONDS}" \
    k6 run /scripts/product-inbox-before-after-experiment.js

  summary_json="$(docker compose exec -T product-service \
    curl -s "http://localhost:8082/api/v1/experiments/inbox/runs/${phase_run_id}/summary")"

  jq -n \
    --arg runId "${RUN_ID}" \
    --arg phase "${phase}" \
    --arg phaseRunId "${phase_run_id}" \
    --arg inboxEnabled "${inbox_enabled}" \
    --arg topic "${INBOX_EXPERIMENT_TOPIC}" \
    --arg consumerGroup "${consumer_group}" \
    --argjson duplicateCount "${EXPERIMENT_DUPLICATE_COUNT}" \
    --argjson quantity "${EXPERIMENT_QUANTITY}" \
    --argjson stock "${EXPERIMENT_STOCK}" \
    --argjson summary "${summary_json}" \
    '
    {
      runId: $runId,
      phase: $phase,
      phaseRunId: $phaseRunId,
      inboxEnabled: ($inboxEnabled == "true"),
      topic: $topic,
      consumerGroup: $consumerGroup,
      config: {
        duplicateCount: $duplicateCount,
        quantity: $quantity,
        stock: $stock
      },
      summary: $summary
    }
    ' > "${result_path}"
}

docker compose up -d mysql redpanda redis

run_phase "before" "false" "${BEFORE_CONSUMER_GROUP}" "${RESULT_BEFORE}"
run_phase "after" "true" "${AFTER_CONSUMER_GROUP}" "${RESULT_AFTER}"

jq -n \
  --arg runId "${RUN_ID}" \
  --arg topic "${INBOX_EXPERIMENT_TOPIC}" \
  --arg beforeConsumerGroup "${BEFORE_CONSUMER_GROUP}" \
  --arg afterConsumerGroup "${AFTER_CONSUMER_GROUP}" \
  --argjson duplicateCount "${EXPERIMENT_DUPLICATE_COUNT}" \
  --argjson quantity "${EXPERIMENT_QUANTITY}" \
  --argjson stock "${EXPERIMENT_STOCK}" \
  --slurpfile before "${RESULT_BEFORE}" \
  --slurpfile after "${RESULT_AFTER}" \
  '
  {
    runId: $runId,
    config: {
      duplicateCount: $duplicateCount,
      quantity: $quantity,
      stock: $stock,
      topic: $topic,
      beforeConsumerGroup: $beforeConsumerGroup,
      afterConsumerGroup: $afterConsumerGroup
    },
    before: $before[0],
    after: $after[0]
  }
  ' > "${RESULT_COMBINED}"

cat <<EOF
run_id=${RUN_ID}
before_result=${RESULT_BEFORE}
after_result=${RESULT_AFTER}
combined_result=${RESULT_COMBINED}
EOF
