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

EXPERIMENT_TOTAL_EVENTS="${EXPERIMENT_TOTAL_EVENTS:-3000}"
EXPERIMENT_PRODUCT_COUNT="${EXPERIMENT_PRODUCT_COUNT:-12}"
EXPERIMENT_QUANTITY="${EXPERIMENT_QUANTITY:-1}"
EXPERIMENT_STOCK="${EXPERIMENT_STOCK:-100000}"
EXPERIMENT_POLL_INTERVAL_MS="${EXPERIMENT_POLL_INTERVAL_MS:-1000}"
EXPERIMENT_POLL_TIMEOUT_SECONDS="${EXPERIMENT_POLL_TIMEOUT_SECONDS:-180}"

SINGLE_TOPIC="${PARTITION_EXPERIMENT_SINGLE_TOPIC:-market.order.stock.changed.experiment.single}"
MULTI_TOPIC="${PARTITION_EXPERIMENT_MULTI_TOPIC:-market.order.stock.changed.experiment.multi}"
SINGLE_CONCURRENCY="${PARTITION_EXPERIMENT_SINGLE_CONCURRENCY:-1}"
MULTI_CONCURRENCY="${PARTITION_EXPERIMENT_MULTI_CONCURRENCY:-3}"

RESULT_SINGLE="loadtest/results/partition-experiment-single-${RUN_ID}.json"
RESULT_MULTI="loadtest/results/partition-experiment-multi-${RUN_ID}.json"
RESULT_COMBINED="loadtest/results/partition-experiment-${RUN_ID}.json"

if (( EXPERIMENT_TOTAL_EVENTS % 2 != 0 )); then
  echo "EXPERIMENT_TOTAL_EVENTS must be even. totalEvents=${EXPERIMENT_TOTAL_EVENTS}" >&2
  exit 1
fi

wait_for_product_service() {
  docker compose --profile loadtest run --no-deps --rm \
    -e WAIT_URL="${PRODUCT_SERVICE_HEALTH_URL}" \
    -e WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS}" \
    -e WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS}" \
    -e WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES}" \
    k6 run /scripts/wait-http.js
}

restart_product_service() {
  local phase="$1"
  local concurrency="$2"
  local topic="$3"

  echo "phase=${phase}"
  echo "product_kafka_listener_concurrency=${concurrency}"
  echo "product_partition_experiment_topic=${topic}"

  PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
  PRODUCT_CACHE_ENABLED=false \
  PRODUCT_STOCK_REDIS_ENABLED=false \
  PRODUCT_KAFKA_LISTENER_CONCURRENCY="${concurrency}" \
  PRODUCT_PARTITION_EXPERIMENT_TOPIC="${topic}" \
  PRODUCT_KAFKA_CONSUMER_GROUP="product-partition-experiment-${RUN_ID}-${phase}" \
  docker compose up -d --build product-service

  wait_for_product_service
}

run_phase() {
  local phase="$1"
  local concurrency="$2"
  local topic="$3"
  local result_path="$4"
  local phase_run_id="${RUN_ID}-${phase}"
  local summary_json

  restart_product_service "${phase}" "${concurrency}" "${topic}"

  docker compose --profile loadtest run --no-deps --rm \
    -e RUN_ID="${RUN_ID}" \
    -e EXPERIMENT_PHASE="${phase}" \
    -e PRODUCT_SERVICE_BASE_URL="${PRODUCT_SERVICE_BASE_URL}" \
    -e EXPERIMENT_TOPIC="${topic}" \
    -e EXPERIMENT_TOTAL_EVENTS="${EXPERIMENT_TOTAL_EVENTS}" \
    -e EXPERIMENT_PRODUCT_COUNT="${EXPERIMENT_PRODUCT_COUNT}" \
    -e EXPERIMENT_QUANTITY="${EXPERIMENT_QUANTITY}" \
    -e EXPERIMENT_STOCK="${EXPERIMENT_STOCK}" \
    -e EXPERIMENT_POLL_INTERVAL_MS="${EXPERIMENT_POLL_INTERVAL_MS}" \
    -e EXPERIMENT_POLL_TIMEOUT_SECONDS="${EXPERIMENT_POLL_TIMEOUT_SECONDS}" \
    k6 run /scripts/partition-throughput-experiment.js

  summary_json="$(docker compose exec -T product-service \
    curl -s "http://localhost:8082/api/v1/experiments/partition/runs/${phase_run_id}/summary")"

  jq -n \
    --arg runId "${RUN_ID}" \
    --arg phase "${phase}" \
    --arg phaseRunId "${phase_run_id}" \
    --arg topic "${topic}" \
    --argjson productCount "${EXPERIMENT_PRODUCT_COUNT}" \
    --argjson totalEvents "${EXPERIMENT_TOTAL_EVENTS}" \
    --argjson quantity "${EXPERIMENT_QUANTITY}" \
    --argjson stock "${EXPERIMENT_STOCK}" \
    --argjson summary "${summary_json}" \
    '
    {
      runId: $runId,
      phase: $phase,
      phaseRunId: $phaseRunId,
      topic: $topic,
      consumerConcurrency: '"${concurrency}"',
      config: {
        productCount: $productCount,
        totalEvents: $totalEvents,
        expectedOrderCount: ($totalEvents / 2),
        quantity: $quantity,
        stock: $stock
      },
      summary: $summary
    }
    ' > "${result_path}"
}

docker compose up -d mysql redpanda redis

run_phase "single" "${SINGLE_CONCURRENCY}" "${SINGLE_TOPIC}" "${RESULT_SINGLE}"
run_phase "multi" "${MULTI_CONCURRENCY}" "${MULTI_TOPIC}" "${RESULT_MULTI}"

jq -n \
  --arg runId "${RUN_ID}" \
  --arg singleTopic "${SINGLE_TOPIC}" \
  --arg multiTopic "${MULTI_TOPIC}" \
  --argjson totalEvents "${EXPERIMENT_TOTAL_EVENTS}" \
  --argjson productCount "${EXPERIMENT_PRODUCT_COUNT}" \
  --argjson quantity "${EXPERIMENT_QUANTITY}" \
  --argjson stock "${EXPERIMENT_STOCK}" \
  --slurpfile single "${RESULT_SINGLE}" \
  --slurpfile multi "${RESULT_MULTI}" \
  '
  {
    runId: $runId,
    config: {
      totalEvents: $totalEvents,
      expectedOrderCount: ($totalEvents / 2),
      productCount: $productCount,
      quantity: $quantity,
      stock: $stock,
      singleTopic: $singleTopic,
      multiTopic: $multiTopic,
      singleConcurrency: $single[0].consumerConcurrency,
      multiConcurrency: $multi[0].consumerConcurrency
    },
    single: $single[0],
    multi: $multi[0],
    improvements: {
      throughputEventsPerSecondPct: (
        if ($single[0].summary.throughputEventsPerSecond == null or $single[0].summary.throughputEventsPerSecond == 0)
        then null
        else ((($multi[0].summary.throughputEventsPerSecond - $single[0].summary.throughputEventsPerSecond) / $single[0].summary.throughputEventsPerSecond) * 100)
        end
      ),
      totalDurationMsPct: (
        if ($single[0].summary.totalDurationMillis == null or $single[0].summary.totalDurationMillis == 0)
        then null
        else ((($single[0].summary.totalDurationMillis - $multi[0].summary.totalDurationMillis) / $single[0].summary.totalDurationMillis) * 100)
        end
      )
    }
  }
  ' > "${RESULT_COMBINED}"

cat <<EOF
run_id=${RUN_ID}
single_result=${RESULT_SINGLE}
multi_result=${RESULT_MULTI}
combined_result=${RESULT_COMBINED}
EOF
