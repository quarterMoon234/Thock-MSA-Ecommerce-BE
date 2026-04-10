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
TARGET="${TARGET:-${K6_TARGET:-internal}}"
PRODUCT_IDS="${PRODUCT_IDS:-${K6_PRODUCT_IDS:-1,2,3}}"
PRODUCT_BATCH_SIZE="${PRODUCT_BATCH_SIZE:-${K6_PRODUCT_BATCH_SIZE:-3}}"
WARM_CACHE="${WARM_CACHE:-${K6_WARM_CACHE:-true}}"
GATEWAY_HEALTH_URL="${GATEWAY_HEALTH_URL:-http://localhost:8080/actuator/health}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-30}"
WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS:-2}"
WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES:-3}"
BASE_URL="${K6_BASE_URL:-http://api-gateway:8080}"
DETAIL_BASE_URL="${K6_DETAIL_BASE_URL:-http://product-service:8082}"
INTERNAL_BASE_URL="${K6_INTERNAL_BASE_URL:-http://product-service:8082}"
INTERNAL_AUTH_SECRET="${INTERNAL_AUTH_SECRET:-${SECURITY_SERVICE_INTERNAL_SECRET:-${SECURITY_GATEWAY_INTERNAL_SECRET:-}}}"
EXPERIMENT_PREFIX="${EXPERIMENT_PREFIX:-product-cache-${TARGET}}"
EXECUTOR="${EXECUTOR:-${K6_EXECUTOR:-iterations}}"
ITERATIONS="${ITERATIONS:-${K6_ITERATIONS:-3000}}"
VUS="${VUS:-${K6_VUS:-50}}"
CURRENT_CACHE_ENABLED=""

if [[ "${TARGET}" != "detail" && "${TARGET}" != "internal" && "${TARGET}" != "mixed" ]]; then
  echo "TARGET must be one of detail, internal, mixed. target=${TARGET}" >&2
  exit 1
fi

if [[ "${EXECUTOR}" != "iterations" && "${EXECUTOR}" != "rate" ]]; then
  echo "EXECUTOR must be one of iterations, rate. executor=${EXECUTOR}" >&2
  exit 1
fi

if [[ "${TARGET}" != "detail" && -z "${INTERNAL_AUTH_SECRET}" ]]; then
  echo "SECURITY_SERVICE_INTERNAL_SECRET or INTERNAL_AUTH_SECRET is required for target=${TARGET}." >&2
  exit 1
fi

wait_for_gateway() {
  local attempt=1

  while (( attempt <= WAIT_MAX_ATTEMPTS )); do
    if command -v curl >/dev/null 2>&1; then
      if curl -fsS "${GATEWAY_HEALTH_URL}" >/dev/null 2>&1; then
        echo "gateway_ready=true"
        return 0
      fi
    elif command -v wget >/dev/null 2>&1; then
      if wget -qO- "${GATEWAY_HEALTH_URL}" >/dev/null 2>&1; then
        echo "gateway_ready=true"
        return 0
      fi
    else
      echo "Neither curl nor wget is available on the host." >&2
      return 1
    fi

    echo "gateway_ready=false attempt=${attempt} url=${GATEWAY_HEALTH_URL}"
    sleep "${WAIT_SLEEP_SECONDS}"
    attempt=$((attempt + 1))
  done

  echo "Gateway did not become ready: ${GATEWAY_HEALTH_URL}" >&2
  return 1
}

wait_for_http_from_k6_network() {
  local url="$1"

  echo "wait_http_url=${url}"
  docker compose --profile loadtest run --no-deps --rm \
    -e WAIT_URL="${url}" \
    -e WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS}" \
    -e WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS}" \
    -e WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES}" \
    k6 run /scripts/wait-http.js
}

wait_for_target_service() {
  if [[ "${TARGET}" == "detail" || "${TARGET}" == "mixed" ]]; then
    wait_for_http_from_k6_network "${DETAIL_BASE_URL}/actuator/health"
  fi

  if [[ "${TARGET}" == "internal" || "${TARGET}" == "mixed" ]]; then
    if [[ "${INTERNAL_BASE_URL}" != "${DETAIL_BASE_URL}" || "${TARGET}" == "internal" ]]; then
      wait_for_http_from_k6_network "${INTERNAL_BASE_URL}/actuator/health"
    fi
  fi
}

restart_product_service() {
  local cache_enabled="$1"

  echo "product_cache_enabled=${cache_enabled}"
  PRODUCT_CACHE_ENABLED="${cache_enabled}" docker compose up -d --build product-service
  CURRENT_CACHE_ENABLED="${cache_enabled}"
  wait_for_gateway
  wait_for_target_service
}

restore_cache_enabled() {
  if [[ "${RESTORE_CACHE_ENABLED:-true}" == "true" && "${CURRENT_CACHE_ENABLED}" == "false" ]]; then
    echo "restore_product_cache_enabled=true"
    PRODUCT_CACHE_ENABLED=true docker compose up -d --build product-service >/dev/null
  fi
}

flush_redis() {
  echo "redis_flushdb=true"
  docker compose exec -T redis redis-cli FLUSHDB >/dev/null
}

run_k6_phase() {
  local cache_label="$1"
  local cache_enabled="$2"
  local experiment_name="${EXPERIMENT_PREFIX}-${cache_label}"
  local summary_path="/results/${experiment_name}-${RUN_ID}.json"

  restart_product_service "${cache_enabled}"
  flush_redis
  wait_for_target_service

  cat <<EOF
experiment=${experiment_name}
run_id=${RUN_ID}
target=${TARGET}
warm_cache=${WARM_CACHE}
product_ids=${PRODUCT_IDS}
product_batch_size=${PRODUCT_BATCH_SIZE}
executor=${EXECUTOR}
iterations=${ITERATIONS}
vus=${VUS}
rate=${K6_RATE:-50}
duration=${K6_DURATION:-1m}
detail_base_url=${DETAIL_BASE_URL}
internal_base_url=${INTERNAL_BASE_URL}
summary_path=${summary_path}
EOF

  docker compose --profile loadtest run --rm \
    -e RUN_ID="${RUN_ID}" \
    -e EXPERIMENT_NAME="${experiment_name}" \
    -e SUMMARY_PATH="${summary_path}" \
    -e BASE_URL="${BASE_URL}" \
    -e DETAIL_BASE_URL="${DETAIL_BASE_URL}" \
    -e INTERNAL_BASE_URL="${INTERNAL_BASE_URL}" \
    -e INTERNAL_AUTH_SECRET="${INTERNAL_AUTH_SECRET}" \
    -e TARGET="${TARGET}" \
    -e WARM_CACHE="${WARM_CACHE}" \
    -e PRODUCT_IDS="${PRODUCT_IDS}" \
    -e PRODUCT_BATCH_SIZE="${PRODUCT_BATCH_SIZE}" \
    -e EXECUTOR="${EXECUTOR}" \
    -e ITERATIONS="${ITERATIONS}" \
    -e VUS="${VUS}" \
    -e RATE="${K6_RATE:-50}" \
    -e DURATION="${K6_DURATION:-1m}" \
    -e PRE_ALLOCATED_VUS="${K6_PRE_ALLOCATED_VUS:-50}" \
    -e MAX_VUS="${K6_MAX_VUS:-100}" \
    -e SLEEP_BETWEEN="${K6_SLEEP_BETWEEN:-0}" \
    k6 run /scripts/product-cache-read.js
}

trap restore_cache_enabled EXIT

docker compose up -d mysql redpanda redis member-service product-service market-service api-gateway
wait_for_gateway

run_k6_phase "cache-off" "false"
run_k6_phase "cache-on" "true"

cat <<EOF
result_cache_off=loadtest/results/${EXPERIMENT_PREFIX}-cache-off-${RUN_ID}.json
result_cache_on=loadtest/results/${EXPERIMENT_PREFIX}-cache-on-${RUN_ID}.json
EOF
