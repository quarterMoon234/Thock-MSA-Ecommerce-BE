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

RUN_ID="${RUN_ID:-$(date +%s)}"
MARKET_SERVICE_BASE_URL="${MARKET_SERVICE_BASE_URL:-http://market-service:8083}"
MARKET_SERVICE_HEALTH_URL="${MARKET_SERVICE_HEALTH_URL:-${MARKET_SERVICE_BASE_URL}/actuator/health}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-40}"
WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS:-2}"
WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES:-3}"
ORDER_COUNT="${ORDER_QUERY_ORDER_COUNT:-100}"
ITEMS_PER_ORDER="${ORDER_QUERY_ITEMS_PER_ORDER:-5}"
ITERATIONS="${K6_ITERATIONS:-300}"
VUS="${K6_VUS:-20}"

wait_for_market_service() {
  docker compose --profile loadtest run --no-deps --rm \
    -e WAIT_URL="${MARKET_SERVICE_HEALTH_URL}" \
    -e WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS}" \
    -e WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS}" \
    -e WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES}" \
    k6 run /scripts/wait-http.js
}

docker compose up -d mysql redpanda product-service payment-service

MARKET_SERVICE_PROFILES_ACTIVE=docker,experiment \
docker compose up -d --build market-service

wait_for_market_service

for mode in baseline optimized; do
  experiment_name="order-query-${mode}"
  summary_path="/results/${experiment_name}-${RUN_ID}.json"

  cat <<EOF
experiment=${experiment_name}
run_id=${RUN_ID}
order_count=${ORDER_COUNT}
items_per_order=${ITEMS_PER_ORDER}
iterations=${ITERATIONS}
vus=${VUS}
summary_path=${summary_path}
EOF

  docker compose --profile loadtest run --no-deps --rm \
    -e RUN_ID="${RUN_ID}" \
    -e EXPERIMENT_NAME="${experiment_name}" \
    -e SUMMARY_PATH="${summary_path}" \
    -e MARKET_SERVICE_BASE_URL="${MARKET_SERVICE_BASE_URL}" \
    -e ORDER_QUERY_MODE="${mode}" \
    -e ORDER_QUERY_ORDER_COUNT="${ORDER_COUNT}" \
    -e ORDER_QUERY_ITEMS_PER_ORDER="${ITEMS_PER_ORDER}" \
    -e ITERATIONS="${ITERATIONS}" \
    -e VUS="${VUS}" \
    -e MAX_DURATION="${K6_MAX_DURATION:-3m}" \
    k6 run /scripts/order-query-read.js
done

cat <<EOF
result_prefix=loadtest/results/order-query
run_id=${RUN_ID}
order_count=${ORDER_COUNT}
items_per_order=${ITEMS_PER_ORDER}
EOF
