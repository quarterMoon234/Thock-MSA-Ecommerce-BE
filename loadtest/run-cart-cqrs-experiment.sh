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
PRODUCT_EXPERIMENT_BASE_URL="${PRODUCT_EXPERIMENT_BASE_URL:-http://product-service:8082}"
MARKET_EXPERIMENT_BASE_URL="${MARKET_EXPERIMENT_BASE_URL:-http://market-service:8083}"
PRODUCT_HEALTH_URL="${PRODUCT_HEALTH_URL:-${PRODUCT_EXPERIMENT_BASE_URL}/actuator/health}"
MARKET_HEALTH_URL="${MARKET_HEALTH_URL:-${MARKET_EXPERIMENT_BASE_URL}/actuator/health}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-40}"
WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS:-2}"
WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES:-3}"
SUMMARY_PATH="${SUMMARY_PATH:-/results/cart-cqrs-experiment-${RUN_ID}.json}"

wait_for_http() {
  local url="$1"

  docker compose --profile loadtest run --no-deps --rm \
    -e WAIT_URL="${url}" \
    -e WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS}" \
    -e WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS}" \
    -e WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES}" \
    k6 run /scripts/wait-http.js
}

docker compose up -d mysql redis redpanda payment-service member-service api-gateway

PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
MARKET_SERVICE_PROFILES_ACTIVE=docker,experiment \
docker compose up -d --build product-service market-service

wait_for_http "${PRODUCT_HEALTH_URL}"
wait_for_http "${MARKET_HEALTH_URL}"

cat <<EOF
experiment=cart-cqrs
run_id=${RUN_ID}
scenario_vus=${K6_SCENARIO_VUS:-30}
scenario_duration=${K6_SCENARIO_DURATION:-20s}
scenario_slot_seconds=${K6_SCENARIO_SLOT_SECONDS:-25}
product_count=${K6_PRODUCT_COUNT:-3}
product_delay_ms=${K6_PRODUCT_DELAY_MS:-900}
read_member_count=${K6_READ_MEMBER_COUNT:-50}
add_member_count_per_scenario=${K6_ADD_MEMBER_COUNT_PER_SCENARIO:-100}
summary_path=${SUMMARY_PATH}
EOF

docker compose --profile loadtest run --no-deps --rm \
  -e RUN_ID="${RUN_ID}" \
  -e PRODUCT_EXPERIMENT_BASE_URL="${PRODUCT_EXPERIMENT_BASE_URL}" \
  -e MARKET_EXPERIMENT_BASE_URL="${MARKET_EXPERIMENT_BASE_URL}" \
  -e SCENARIO_VUS="${K6_SCENARIO_VUS:-30}" \
  -e SCENARIO_DURATION="${K6_SCENARIO_DURATION:-20s}" \
  -e SCENARIO_SLOT_SECONDS="${K6_SCENARIO_SLOT_SECONDS:-25}" \
  -e PRODUCT_COUNT="${K6_PRODUCT_COUNT:-3}" \
  -e PRODUCT_STOCK="${K6_PRODUCT_STOCK:-100000}" \
  -e PRODUCT_PRICE="${K6_PRODUCT_PRICE:-10000}" \
  -e PRODUCT_SALE_PRICE="${K6_PRODUCT_SALE_PRICE:-9000}" \
  -e READ_MEMBER_COUNT="${K6_READ_MEMBER_COUNT:-50}" \
  -e ADD_MEMBER_COUNT_PER_SCENARIO="${K6_ADD_MEMBER_COUNT_PER_SCENARIO:-100}" \
  -e READ_ITEM_QUANTITY="${K6_READ_ITEM_QUANTITY:-1}" \
  -e ADD_ITEM_QUANTITY="${K6_ADD_ITEM_QUANTITY:-1}" \
  -e PRODUCT_DELAY_MS="${K6_PRODUCT_DELAY_MS:-900}" \
  -e BASE_MEMBER_ID="${K6_BASE_MEMBER_ID:-980000}" \
  -e SUMMARY_PATH="${SUMMARY_PATH}" \
  k6 run /scripts/cart-cqrs-experiment.js
