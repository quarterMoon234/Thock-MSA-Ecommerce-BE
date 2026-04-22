#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL="${API_BASE_URL:-http://localhost:8080/api/v1}"
PRODUCT_IDS_CSV="${PRODUCT_IDS:-1,2,3}"
BUYER_PASSWORD="${BUYER_PASSWORD:-password123!}"
RUN_ID="${RUN_ID:-$(date +%s)}"
BUYER_EMAIL="${BUYER_EMAIL:-buyer-cb-${RUN_ID}@example.com}"
BUYER_NAME="${BUYER_NAME:-buyer-cb-${RUN_ID}}"
RESULT_DIR="${RESULT_DIR:-/tmp/circuit-breaker-${RUN_ID}}"
FAIL_REQUESTS="${FAIL_REQUESTS:-3}"
SETUP_WAIT_SECONDS="${SETUP_WAIT_SECONDS:-5}"
RECOVERY_WAIT_SECONDS="${RECOVERY_WAIT_SECONDS:-6}"
RECOVERY_ATTEMPTS="${RECOVERY_ATTEMPTS:-3}"
MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT="${MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT:-100}"
MARKET_FEIGN_DEFAULT_READ_TIMEOUT="${MARKET_FEIGN_DEFAULT_READ_TIMEOUT:-150}"
MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE="${MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE:-1}"
MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS="${MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS:-1}"
MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD="${MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD:-1}"
MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE="${MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE:-3s}"
MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN="${MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN:-1}"

mkdir -p "${RESULT_DIR}"

START_MARK="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
COMPOSE_CB_ENV_FILE="${RESULT_DIR}/compose-cb.env"
COMPOSE_CB_OVERRIDE_FILE="${RESULT_DIR}/docker-compose.cb.override.yml"

cat > "${COMPOSE_CB_ENV_FILE}" <<EOF
MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT=${MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT}
MARKET_FEIGN_DEFAULT_READ_TIMEOUT=${MARKET_FEIGN_DEFAULT_READ_TIMEOUT}
MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE=${MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE}
MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS=${MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS}
MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD=${MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD}
MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE=${MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE}
MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN=${MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN}
EOF

cat > "${COMPOSE_CB_OVERRIDE_FILE}" <<EOF
services:
  market-service:
    environment:
      MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT: "${MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT}"
      MARKET_FEIGN_DEFAULT_READ_TIMEOUT: "${MARKET_FEIGN_DEFAULT_READ_TIMEOUT}"
      MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE: "${MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE}"
      MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS: "${MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS}"
      MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD: "${MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD}"
      MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE: "${MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE}"
      MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN: "${MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN}"
EOF

echo "[1/8] starting required services"
docker compose up -d member-service product-service payment-service >/dev/null

docker compose \
  -f docker-compose.yml \
  -f "${COMPOSE_CB_OVERRIDE_FILE}" \
  up -d --build --force-recreate market-service >/dev/null

docker compose up -d --no-deps api-gateway >/dev/null

echo "[1.1/8] verifying market-service circuit breaker env"
docker compose exec -T market-service printenv \
  | grep '^MARKET_CB_PAYMENT\|^MARKET_FEIGN_DEFAULT' \
  > "${RESULT_DIR}/00-market-cb-env.txt"

assert_env_value() {
  local key="$1"
  local expected="$2"
  local actual
  actual="$(grep "^${key}=" "${RESULT_DIR}/00-market-cb-env.txt" | cut -d= -f2- || true)"

  if [ "${actual}" != "${expected}" ]; then
    echo "market-service env mismatch: ${key} expected=${expected} actual=${actual:-<missing>}"
    cat "${RESULT_DIR}/00-market-cb-env.txt"
    exit 1
  fi
}

assert_env_value "MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT" "${MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT}"
assert_env_value "MARKET_FEIGN_DEFAULT_READ_TIMEOUT" "${MARKET_FEIGN_DEFAULT_READ_TIMEOUT}"
assert_env_value "MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE" "${MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE}"
assert_env_value "MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS" "${MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS}"
assert_env_value "MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD" "${MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD}"
assert_env_value "MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE" "${MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE}"
assert_env_value "MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN" "${MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN}"

echo "[2/8] signing up fresh buyer: ${BUYER_EMAIL}"
curl -s -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${BUYER_EMAIL}\",\"name\":\"${BUYER_NAME}\",\"password\":\"${BUYER_PASSWORD}\"}" \
  "${API_BASE_URL}/members/signup" \
  > "${RESULT_DIR}/01-signup.json"

echo "[3/8] logging in buyer"
curl -s -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${BUYER_EMAIL}\",\"password\":\"${BUYER_PASSWORD}\"}" \
  "${API_BASE_URL}/auth/login" \
  > "${RESULT_DIR}/02-login.json"

TOKEN="$(jq -r '.accessToken' "${RESULT_DIR}/02-login.json")"
if [ -z "${TOKEN}" ] || [ "${TOKEN}" = "null" ]; then
  echo "login failed"
  cat "${RESULT_DIR}/02-login.json"
  exit 1
fi

echo "[4/8] waiting for member/wallet projections to settle (${SETUP_WAIT_SECONDS}s)"
sleep "${SETUP_WAIT_SECONDS}"

echo "[5/8] filling cart"
IFS=',' read -r -a PRODUCT_IDS <<< "${PRODUCT_IDS_CSV}"
for product_id in "${PRODUCT_IDS[@]}"; do
  added=false
  for attempt in $(seq 1 10); do
    status="$(curl -s -o "${RESULT_DIR}/cart-add-${product_id}.json" -w "%{http_code}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H 'Content-Type: application/json' \
      -d "{\"productId\":${product_id},\"quantity\":1}" \
      "${API_BASE_URL}/carts/items")"

    if [ "${status}" = "201" ]; then
      added=true
      break
    fi

    sleep 1
  done

  if [ "${added}" != "true" ]; then
    echo "failed to add product ${product_id} to cart"
    cat "${RESULT_DIR}/cart-add-${product_id}.json"
    exit 1
  fi
done

curl -s \
  -H "Authorization: Bearer ${TOKEN}" \
  "${API_BASE_URL}/carts" \
  > "${RESULT_DIR}/03-cart.json"

jq '{cartItemIds: [.items[].cartItemId], zipCode: "06234", baseAddress: "서울특별시 강남구 테헤란로 123", detailAddress: "101호"}' \
  "${RESULT_DIR}/03-cart.json" \
  > "${RESULT_DIR}/04-order-create.json"

if [ "$(jq '.cartItemIds | length' "${RESULT_DIR}/04-order-create.json")" -eq 0 ]; then
  echo "cart item ids are empty"
  cat "${RESULT_DIR}/03-cart.json"
  exit 1
fi

echo "[6/8] stopping payment-service and triggering circuit breaker"
docker compose stop payment-service >/dev/null

: > "${RESULT_DIR}/05-failure-results.txt"
for i in $(seq 1 "${FAIL_REQUESTS}"); do
  curl -s -o "${RESULT_DIR}/cb-fail-${i}.json" \
    -w "REQ=${i} HTTP_STATUS=%{http_code} TIME=%{time_total}\n" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d @"${RESULT_DIR}/04-order-create.json" \
    "${API_BASE_URL}/orders" \
    >> "${RESULT_DIR}/05-failure-results.txt"
done

jq '{code, message, path}' "${RESULT_DIR}/cb-fail-${FAIL_REQUESTS}.json" \
  > "${RESULT_DIR}/06-failure-body.json"

{
  cat "${RESULT_DIR}/05-failure-results.txt"
  echo
  cat "${RESULT_DIR}/06-failure-body.json"
} > "${RESULT_DIR}/05-failure-summary.txt"

docker compose logs --since="${START_MARK}" market-service \
  | grep 'payment-wallet-client' \
  | grep -E 'event=ERROR|transition=State transition from CLOSED to OPEN|event=CALL_NOT_PERMITTED|changed state from CLOSED to OPEN|recorded a call which was not permitted|exceeded failure rate threshold' \
  > "${RESULT_DIR}/07-failure-cb.log" || true

echo "[7/8] recovering payment-service"
docker compose up -d payment-service >/dev/null
sleep "${RECOVERY_WAIT_SECONDS}"

echo "[7.1/8] waiting until payment-service wallet endpoint is healthy"
wallet_ready=false
for _ in $(seq 1 20); do
  wallet_status="$(curl -s -o /tmp/cb-wallet-ready.json -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" \
    "${API_BASE_URL}/payments/wallet/me")"

  if [ "${wallet_status}" = "200" ]; then
    wallet_ready=true
    break
  fi

  sleep 1
done

if [ "${wallet_ready}" != "true" ]; then
  echo "payment-service wallet endpoint did not recover in time"
  cat /tmp/cb-wallet-ready.json || true
  exit 1
fi

recovery_success=false
: > "${RESULT_DIR}/08-recovery-attempts.txt"

for attempt in $(seq 1 "${RECOVERY_ATTEMPTS}"); do
  STATUS="$(curl -s -o "${RESULT_DIR}/08-recovery-response-raw.json" -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d @"${RESULT_DIR}/04-order-create.json" \
    "${API_BASE_URL}/orders")"

  printf 'ATTEMPT=%s HTTP_STATUS=%s\n' "${attempt}" "${STATUS}" >> "${RESULT_DIR}/08-recovery-attempts.txt"

  if [ "${STATUS}" = "201" ]; then
    recovery_success=true
    break
  fi

  sleep 4
done

printf 'HTTP_STATUS=%s\n' "${STATUS}" > "${RESULT_DIR}/08-recovery-status.txt"
jq '{orderId, orderNumber, state, pgAmount}' "${RESULT_DIR}/08-recovery-response-raw.json" \
  > "${RESULT_DIR}/09-recovery-body.json"

{
  cat "${RESULT_DIR}/08-recovery-attempts.txt"
  cat "${RESULT_DIR}/08-recovery-status.txt"
  cat "${RESULT_DIR}/09-recovery-body.json"
} > "${RESULT_DIR}/08-recovery-summary.txt"

docker compose logs --since="${START_MARK}" market-service \
  | grep 'payment-wallet-client' \
  | grep -E 'transition=State transition from OPEN to HALF_OPEN|transition=State transition from HALF_OPEN to CLOSED|transition=State transition from HALF_OPEN to OPEN|changed state from OPEN to HALF_OPEN|changed state from HALF_OPEN to CLOSED|changed state from HALF_OPEN to OPEN|event=ERROR' \
  > "${RESULT_DIR}/10-recovery-cb.log" || true

echo "[8/8] done"
echo "result_dir=${RESULT_DIR}"
echo "buyer_email=${BUYER_EMAIL}"
echo "buyer_password=${BUYER_PASSWORD}"
echo "access_token=${TOKEN}"
echo "market_cb_payment_sliding_window_size=${MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE}"
echo "market_cb_payment_minimum_number_of_calls=${MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS}"
echo "market_cb_payment_failure_rate_threshold=${MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD}"
echo "market_cb_payment_wait_duration_in_open_state=${MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE}"
echo "capture_files:"
echo "  ${RESULT_DIR}/05-failure-summary.txt"
echo "  ${RESULT_DIR}/07-failure-cb.log"
echo "  ${RESULT_DIR}/08-recovery-summary.txt"
echo "  ${RESULT_DIR}/10-recovery-cb.log"
