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
PRODUCT_SERVICE_BASE_URL="${PRODUCT_SERVICE_BASE_URL:-http://product-service:8082}"
PRODUCT_SERVICE_HEALTH_URL="${PRODUCT_SERVICE_HEALTH_URL:-${PRODUCT_SERVICE_BASE_URL}/actuator/health}"
COUNTS="${STOCK_EXPERIMENT_COUNTS:-100 500 1000}"
INITIAL_STOCK="${INITIAL_STOCK:-10}"
QUANTITY="${QUANTITY:-1}"
EXECUTOR="${STOCK_EXPERIMENT_EXECUTOR:-per-vu}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-40}"
WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS:-2}"
WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES:-3}"
RESTORE_PRODUCT_STOCK_REDIS_ENABLED="${RESTORE_PRODUCT_STOCK_REDIS_ENABLED:-true}"
CURRENT_STOCK_REDIS_ENABLED=""

compute_vus() {
  local iterations="$1"

  if [[ "${EXECUTOR}" == "per-vu" ]]; then
    echo "${iterations}"
    return 0
  fi

  if [[ -n "${K6_VUS:-}" ]]; then
    echo "${K6_VUS}"
    return 0
  fi

  if (( iterations <= 100 )); then
    echo "${iterations}"
    return 0
  fi

  echo 200
}

if [[ "${EXECUTOR}" != "per-vu" && "${EXECUTOR}" != "shared" ]]; then
  echo "STOCK_EXPERIMENT_EXECUTOR must be one of per-vu, shared. executor=${EXECUTOR}" >&2
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
  local stock_redis_enabled="$1"

  echo "product_stock_redis_enabled=${stock_redis_enabled}"
  PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
  PRODUCT_CACHE_ENABLED=false \
  PRODUCT_STOCK_REDIS_ENABLED="${stock_redis_enabled}" \
  docker compose up -d --build product-service

  CURRENT_STOCK_REDIS_ENABLED="${stock_redis_enabled}"
  wait_for_product_service
}

restore_product_service() {
  if [[ "${RESTORE_PRODUCT_STOCK_REDIS_ENABLED}" == "true" && "${CURRENT_STOCK_REDIS_ENABLED}" == "true" ]]; then
    echo "restore_product_stock_redis_enabled=false"
    PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
    PRODUCT_CACHE_ENABLED=false \
    PRODUCT_STOCK_REDIS_ENABLED=false \
    docker compose up -d --build product-service >/dev/null
  fi
}

flush_redis() {
  echo "redis_flushdb=true"
  docker compose exec -T redis redis-cli FLUSHDB >/dev/null
}

run_phase() {
  local phase_label="$1"
  local stock_redis_enabled="$2"
  local rebuild_redis_stock="$3"

  restart_product_service "${stock_redis_enabled}"

  for iterations in ${COUNTS}; do
    local vus
    local experiment_name
    local summary_path

    vus="$(compute_vus "${iterations}")"
    experiment_name="stock-redis-${phase_label}-${iterations}"
    summary_path="/results/${experiment_name}-${RUN_ID}.json"

    flush_redis

    cat <<EOF
experiment=${experiment_name}
run_id=${RUN_ID}
initial_stock=${INITIAL_STOCK}
quantity=${QUANTITY}
executor=${EXECUTOR}
iterations=${iterations}
vus=${vus}
product_cache_enabled=false
product_stock_redis_enabled=${stock_redis_enabled}
rebuild_redis_stock=${rebuild_redis_stock}
summary_path=${summary_path}
EOF

    docker compose --profile loadtest run --no-deps --rm \
      -e RUN_ID="${RUN_ID}" \
      -e EXPERIMENT_NAME="${experiment_name}" \
      -e SUMMARY_PATH="${summary_path}" \
      -e PRODUCT_SERVICE_BASE_URL="${PRODUCT_SERVICE_BASE_URL}" \
      -e PRODUCT_STOCK_REDIS_ENABLED="${stock_redis_enabled}" \
      -e REBUILD_REDIS_STOCK="${rebuild_redis_stock}" \
      -e INITIAL_STOCK="${INITIAL_STOCK}" \
      -e QUANTITY="${QUANTITY}" \
      -e STOCK_EXECUTOR="${EXECUTOR}" \
      -e ATTEMPTS="${iterations}" \
      -e ITERATIONS="${iterations}" \
      -e VUS="${vus}" \
      -e MAX_DURATION="${K6_MAX_DURATION:-5m}" \
      k6 run /scripts/product-stock-pessimistic-baseline.js
  done
}

trap restore_product_service EXIT

docker compose up -d mysql redpanda redis

run_phase "redis-off" "false" "false"
run_phase "redis-on" "true" "true"

cat <<EOF
result_prefix_off=loadtest/results/stock-redis-redis-off
result_prefix_on=loadtest/results/stock-redis-redis-on
run_id=${RUN_ID}
counts=${COUNTS}
EOF
