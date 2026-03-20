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
EXPERIMENT_NAME="${EXPERIMENT_NAME:-cart-read-load}"
SUMMARY_PATH="${SUMMARY_PATH:-/results/${EXPERIMENT_NAME}-${RUN_ID}.json}"
GATEWAY_HEALTH_URL="${GATEWAY_HEALTH_URL:-http://localhost:8080/actuator/health}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-30}"
WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS:-2}"
BUYER_EMAIL="${BUYER_EMAIL:-buyer-k6-${RUN_ID}@example.com}"
BUYER_NAME="${BUYER_NAME:-buyer-k6-${RUN_ID}}"

docker compose up -d mysql redpanda member-service product-service market-service api-gateway

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

wait_for_gateway

cat <<EOF
experiment=${EXPERIMENT_NAME}
run_id=${RUN_ID}
buyer_email=${BUYER_EMAIL}
buyer_password=${K6_PASSWORD:-password123!}
product_ids=${PRODUCT_IDS:-1,2,3}
rate=${K6_RATE:-50}
duration=${K6_DURATION:-1m}
EOF

docker compose --profile loadtest run --rm \
  -e RUN_ID="${RUN_ID}" \
  -e EXPERIMENT_NAME="${EXPERIMENT_NAME}" \
  -e SUMMARY_PATH="${SUMMARY_PATH}" \
  -e BASE_URL="${K6_BASE_URL:-http://api-gateway:8080}" \
  -e PASSWORD="${K6_PASSWORD:-password123!}" \
  -e BUYER_EMAIL="${BUYER_EMAIL}" \
  -e BUYER_NAME="${BUYER_NAME}" \
  -e BUYER_ACCESS_TOKEN="${BUYER_ACCESS_TOKEN:-}" \
  -e RATE="${K6_RATE:-50}" \
  -e DURATION="${K6_DURATION:-1m}" \
  -e PRE_ALLOCATED_VUS="${K6_PRE_ALLOCATED_VUS:-50}" \
  -e MAX_VUS="${K6_MAX_VUS:-100}" \
  -e PRODUCT_IDS="${PRODUCT_IDS:-1,2,3}" \
  -e CART_ITEM_QUANTITY="${K6_CART_ITEM_QUANTITY:-1}" \
  -e SLEEP_BETWEEN="${K6_SLEEP_BETWEEN:-0}" \
  k6 run /scripts/cart-read-cqrs.js
