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
EXPERIMENT_NAME="${EXPERIMENT_NAME:-product-create-load}"
TOPIC="${TOPIC:-product.changed}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"
SUMMARY_PATH="${SUMMARY_PATH:-/results/${EXPERIMENT_NAME}-${RUN_ID}.json}"
GATEWAY_HEALTH_URL="${GATEWAY_HEALTH_URL:-http://localhost:8080/actuator/health}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-30}"
WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS:-2}"
PRODUCT_EVENT_PUBLISH_MODE="${PRODUCT_EVENT_PUBLISH_MODE:-}"
PRODUCT_SERVICE_PROFILES_ACTIVE="${PRODUCT_SERVICE_PROFILES_ACTIVE:-docker,experiment}"

if [[ -z "${PRODUCT_EVENT_PUBLISH_MODE}" ]]; then
  echo "PRODUCT_EVENT_PUBLISH_MODE must be explicitly set to 'direct' or 'outbox'." >&2
  exit 1
fi

export PRODUCT_EVENT_PUBLISH_MODE
export PRODUCT_SERVICE_PROFILES_ACTIVE

echo "product_event_publish_mode=${PRODUCT_EVENT_PUBLISH_MODE}"
echo "product_service_profiles_active=${PRODUCT_SERVICE_PROFILES_ACTIVE}"

docker compose up -d mysql redpanda member-service product-service api-gateway

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

start_count="$(bash loadtest/topic-message-count.sh "${TOPIC}")"

echo "run_id=${RUN_ID}"
echo "topic=${TOPIC}"
echo "start_topic_count=${start_count}"

docker compose --profile loadtest run --rm \
  -e RUN_ID="${RUN_ID}" \
  -e EXPERIMENT_NAME="${EXPERIMENT_NAME}" \
  -e SUMMARY_PATH="${SUMMARY_PATH}" \
  -e BASE_URL="${K6_BASE_URL:-http://api-gateway:8080}" \
  -e PASSWORD="${K6_PASSWORD:-password123!}" \
  -e RATE="${K6_RATE:-10}" \
  -e DURATION="${K6_DURATION:-2m}" \
  -e PRE_ALLOCATED_VUS="${K6_PRE_ALLOCATED_VUS:-20}" \
  -e MAX_VUS="${K6_MAX_VUS:-100}" \
  -e CATEGORY="${K6_CATEGORY:-KEYBOARD}" \
  -e PRICE="${K6_PRICE:-1000}" \
  -e SALE_PRICE="${K6_SALE_PRICE:-0}" \
  -e STOCK="${K6_STOCK:-5}" \
  -e BANK_CODE="${K6_BANK_CODE:-088}" \
  -e ACCOUNT_NUMBER="${K6_ACCOUNT_NUMBER:-1234567890}" \
  -e ACCOUNT_HOLDER="${K6_ACCOUNT_HOLDER:-k6-seller}" \
  -e SLEEP_BETWEEN="${K6_SLEEP_BETWEEN:-0}" \
  k6 run /scripts/product-create-outbox.js

end_count="$(bash loadtest/topic-message-count.sh "${TOPIC}")"
topic_delta="$((end_count - start_count))"

MATCHED_TOPIC_MESSAGES=0
MATCHED_UNIQUE_PRODUCTS=0
DUPLICATE_TOPIC_MESSAGES=0
UNRELATED_TOPIC_MESSAGES=0

while IFS='=' read -r key value; do
  case "${key}" in
    matched_topic_messages)
      MATCHED_TOPIC_MESSAGES="${value}"
      ;;
    matched_unique_products)
      MATCHED_UNIQUE_PRODUCTS="${value}"
      ;;
    duplicate_topic_messages)
      DUPLICATE_TOPIC_MESSAGES="${value}"
      ;;
    unrelated_topic_messages)
      UNRELATED_TOPIC_MESSAGES="${value}"
      ;;
  esac
done < <(bash loadtest/run-scoped-topic-stats.sh "${RUN_ID}" "${start_count}" "${TOPIC}")

db_product_count="$(
  docker compose exec -T mysql mysql -uroot "-p${MYSQL_ROOT_PASSWORD}" -Nse \
  "SELECT COUNT(*) FROM thock_product_db.products WHERE name LIKE 'k6-${RUN_ID}-%';"
)"

cat <<EOF
experiment=${EXPERIMENT_NAME}
run_id=${RUN_ID}
topic=${TOPIC}
start_topic_count=${start_count}
end_topic_count=${end_count}
topic_new_messages=${topic_delta}
matched_topic_messages=${MATCHED_TOPIC_MESSAGES}
matched_unique_products=${MATCHED_UNIQUE_PRODUCTS}
duplicate_topic_messages=${DUPLICATE_TOPIC_MESSAGES}
unrelated_topic_messages=${UNRELATED_TOPIC_MESSAGES}
db_products_created=${db_product_count}
missing_published_event=$((db_product_count - MATCHED_UNIQUE_PRODUCTS))
EOF
