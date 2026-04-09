#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL="${API_BASE_URL:-http://localhost:8080/api/v1}"
PRODUCT_ID="${PRODUCT_ID:-}"
PRODUCT_ID_CANDIDATES="${PRODUCT_ID_CANDIDATES:-4,5,6}"
ORDER_COUNT="${ORDER_COUNT:-24}"
REFUND_COUNT="${REFUND_COUNT:-8}"
ORDER_FAILURE_COUNT="${ORDER_FAILURE_COUNT:-4}"
PAYMENT_FAILURE_UNKNOWN_ORDER_COUNT="${PAYMENT_FAILURE_UNKNOWN_ORDER_COUNT:-6}"
PAYMENT_FAILURE_INVALID_STATE_COUNT="${PAYMENT_FAILURE_INVALID_STATE_COUNT:-6}"
PAYMENT_FAILURE_LOW_BALANCE_COUNT="${PAYMENT_FAILURE_LOW_BALANCE_COUNT:-0}"
REFUND_FAILURE_UNKNOWN_ORDER_COUNT="${REFUND_FAILURE_UNKNOWN_ORDER_COUNT:-3}"
REFUND_FAILURE_INVALID_AMOUNT_COUNT="${REFUND_FAILURE_INVALID_AMOUNT_COUNT:-0}"
STOCK_RESERVE_FAILURE_COUNT="${STOCK_RESERVE_FAILURE_COUNT:-3}"
STOCK_RELEASE_FAILURE_COUNT="${STOCK_RELEASE_FAILURE_COUNT:-3}"
PRODUCT_QUANTITY="${PRODUCT_QUANTITY:-1}"
BUYER_PASSWORD="${BUYER_PASSWORD:-password123!}"
WALLET_BALANCE="${WALLET_BALANCE:-1000000}"
LOW_BALANCE_AMOUNT="${LOW_BALANCE_AMOUNT:-0}"
WAIT_MEMBER_SECONDS="${WAIT_MEMBER_SECONDS:-5}"
WAIT_API_READY_SECONDS="${WAIT_API_READY_SECONDS:-60}"
WAIT_ORDER_COMPLETE_SECONDS="${WAIT_ORDER_COMPLETE_SECONDS:-15}"
WAIT_AFTER_CANCEL_SECONDS="${WAIT_AFTER_CANCEL_SECONDS:-0.5}"
WAIT_BETWEEN_ORDERS_SECONDS="${WAIT_BETWEEN_ORDERS_SECONDS:-0.5}"
WAIT_AFTER_FAILURE_SECONDS="${WAIT_AFTER_FAILURE_SECONDS:-0.4}"
RUN_ID="${RUN_ID:-$(date +%s)}"
BUYER_EMAIL="${BUYER_EMAIL:-buyer-portfolio-${RUN_ID}@example.com}"
BUYER_NAME="${BUYER_NAME:-buyer-portfolio-${RUN_ID}}"
MYSQL_SERVICE="${MYSQL_SERVICE:-mysql}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"
PAYMENT_DB_NAME="${PAYMENT_DB_NAME:-thock_payment_db}"
PRODUCT_DB_NAME="${PRODUCT_DB_NAME:-thock_product_db}"
MARKET_DB_NAME="${MARKET_DB_NAME:-thock_market_db}"
REDPANDA_SERVICE="${REDPANDA_SERVICE:-redpanda}"

MARKET_ORDER_PAYMENT_COMPLETED_TOPIC="market.order.payment.completed"
MARKET_ORDER_PAYMENT_REQUEST_CANCELED_TOPIC="market.order.payment.request.canceled"
MARKET_ORDER_BEFORE_PAYMENT_REQUEST_CANCELED_TOPIC="market.order.before.payment.request.canceled"
MARKET_ORDER_STOCK_CHANGED_TOPIC="market.order.stock.changed"

MARKET_ORDER_PAYMENT_COMPLETED_TYPE="com.thock.back.shared.market.event.MarketOrderPaymentCompletedEvent"
MARKET_ORDER_PAYMENT_REQUEST_CANCELED_TYPE="com.thock.back.shared.market.event.MarketOrderPaymentRequestCanceledEvent"
MARKET_ORDER_BEFORE_PAYMENT_REQUEST_CANCELED_TYPE="com.thock.back.shared.market.event.MarketOrderBeforePaymentCanceledEvent"
MARKET_ORDER_STOCK_CHANGED_TYPE="com.thock.back.shared.market.event.MarketOrderStockChangedEvent"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "required command not found: $1"
    exit 1
  fi
}

require_cmd curl
require_cmd jq
require_cmd docker

required_available_stock=$((ORDER_COUNT * PRODUCT_QUANTITY))

request_json() {
  local method="$1"
  local url="$2"
  local body="${3:-}"
  local token="${4:-}"
  local response_file
  response_file="$(mktemp)"

  local headers=(-H "Content-Type: application/json")
  if [[ -n "${token}" ]]; then
    headers+=(-H "Authorization: Bearer ${token}")
  fi

  local status
  if [[ -n "${body}" ]]; then
    status="$(curl -s -o "${response_file}" -w "%{http_code}" -X "${method}" "${headers[@]}" -d "${body}" "${url}")"
  else
    status="$(curl -s -o "${response_file}" -w "%{http_code}" -X "${method}" "${headers[@]}" "${url}")"
  fi

  echo "${status}:${response_file}"
}

wait_for_public_api_ready() {
  local deadline=$((SECONDS + WAIT_API_READY_SECONDS))

  while (( SECONDS < deadline )); do
    local probe_file probe_status
    probe_file="$(mktemp)"
    probe_status="$(curl -s -o "${probe_file}" -w "%{http_code}" \
      -H "Content-Type: application/json" \
      -d '{"email":"readiness@example.com","password":"password123!"}' \
      "${API_BASE_URL}/auth/login" || true)"

    if [[ "${probe_status}" == "200" || "${probe_status}" == "400" || "${probe_status}" == "401" ]]; then
      rm -f "${probe_file}"
      return 0
    fi

    rm -f "${probe_file}"
    sleep 2
  done

  echo "public API was not ready within ${WAIT_API_READY_SECONDS}s: ${API_BASE_URL}"
  exit 1
}

expect_status() {
  local actual="$1"
  local expected="$2"
  local file="$3"

  if [[ "${actual}" != "${expected}" ]]; then
    echo "unexpected status: expected=${expected}, actual=${actual}"
    cat "${file}"
    exit 1
  fi
}

select_orderable_product() {
  local where_clause
  if [[ -n "${PRODUCT_ID}" ]]; then
    where_clause="id = ${PRODUCT_ID}"
  else
    where_clause="id IN (${PRODUCT_ID_CANDIDATES})"
  fi

  docker compose exec -T "${MYSQL_SERVICE}" mysql -N -B -uroot -p"${MYSQL_ROOT_PASSWORD}" "${PRODUCT_DB_NAME}" -e "
    SELECT id,
           name,
           COALESCE(price, 0),
           COALESCE(sale_price, 0),
           COALESCE(stock, 0),
           COALESCE(reserved_stock, 0)
    FROM products
    WHERE ${where_clause}
      AND state = 'ON_SALE'
      AND COALESCE(NULLIF(sale_price, 0), price, 0) > 0
      AND (COALESCE(stock, 0) - COALESCE(reserved_stock, 0)) >= ${required_available_stock}
    ORDER BY COALESCE(NULLIF(sale_price, 0), price, 0) DESC, id
    LIMIT 1;
  "
}

normalize_selected_product_prices() {
  if (( PRODUCT_SALE_PRICE > 0 )); then
    return 0
  fi

  echo "selected product has sale_price=0; normalizing to price=${PRODUCT_PRICE}"

  docker compose exec -T "${MYSQL_SERVICE}" mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -e "
    UPDATE ${PRODUCT_DB_NAME}.products
    SET sale_price = price
    WHERE id = ${PRODUCT_ID}
      AND COALESCE(sale_price, 0) <= 0;

    UPDATE ${MARKET_DB_NAME}.market_cart_product_views
    SET sale_price = price
    WHERE product_id = ${PRODUCT_ID}
      AND COALESCE(sale_price, 0) <= 0;
  " >/dev/null

  PRODUCT_SALE_PRICE="${PRODUCT_PRICE}"
}

clear_cart() {
  local clear_result clear_status clear_file
  clear_result="$(request_json "DELETE" "${API_BASE_URL}/carts/items" "[]" "${TOKEN}")"
  clear_status="${clear_result%%:*}"
  clear_file="${clear_result#*:}"
  expect_status "${clear_status}" "204" "${clear_file}"
}

wait_order_completed() {
  local order_id="$1"
  for _ in $(seq 1 "${WAIT_ORDER_COMPLETE_SECONDS}"); do
    local detail_result detail_status detail_file state
    detail_result="$(request_json "GET" "${API_BASE_URL}/orders/${order_id}" "" "${TOKEN}")"
    detail_status="${detail_result%%:*}"
    detail_file="${detail_result#*:}"
    if [[ "${detail_status}" == "200" ]]; then
      state="$(jq -r '.state' "${detail_file}")"
      if [[ "${state}" == "PAYMENT_COMPLETED" ]]; then
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}

wait_order_refunded() {
  local order_id="$1"
  for _ in $(seq 1 "${WAIT_ORDER_COMPLETE_SECONDS}"); do
    local detail_result detail_status detail_file state
    detail_result="$(request_json "GET" "${API_BASE_URL}/orders/${order_id}" "" "${TOKEN}")"
    detail_status="${detail_result%%:*}"
    detail_file="${detail_result#*:}"
    if [[ "${detail_status}" == "200" ]]; then
      state="$(jq -r '.state' "${detail_file}")"
      if [[ "${state}" == "REFUNDED" || "${state}" == "PARTIALLY_REFUNDED" ]]; then
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}

set_wallet_balance() {
  local balance="$1"
  docker compose exec -T "${MYSQL_SERVICE}" mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" "${PAYMENT_DB_NAME}" \
    -e "UPDATE payment_wallets SET balance=${balance} WHERE id=${WALLET_ID};" >/dev/null
}

kafka_publish_json() {
  local topic="$1"
  local type_id="$2"
  local key="$3"
  local payload="$4"

  printf '%s\n' "${payload}" \
    | docker compose exec -T "${REDPANDA_SERVICE}" rpk topic produce "${topic}" \
        -H "__TypeId__:${type_id}" \
        -k "${key}" >/dev/null
}

product_row="$(select_orderable_product)"
if [[ -z "${product_row}" ]]; then
  if [[ -n "${PRODUCT_ID}" ]]; then
    echo "PRODUCT_ID=${PRODUCT_ID} does not satisfy the scene constraints."
  else
    echo "No orderable product satisfies the scene constraints."
  fi
  echo "Required: state=ON_SALE, effective_price > 0, available_stock >= ${required_available_stock}"
  exit 1
fi

IFS=$'\t' read -r PRODUCT_ID PRODUCT_NAME PRODUCT_PRICE PRODUCT_SALE_PRICE PRODUCT_STOCK PRODUCT_RESERVED_STOCK <<< "${product_row}"
normalize_selected_product_prices
if (( PRODUCT_SALE_PRICE > 0 )); then
  PRODUCT_EFFECTIVE_PRICE="${PRODUCT_SALE_PRICE}"
else
  PRODUCT_EFFECTIVE_PRICE="${PRODUCT_PRICE}"
fi
PRODUCT_AVAILABLE_STOCK=$((PRODUCT_STOCK - PRODUCT_RESERVED_STOCK))

echo "[1/11] signup"
echo "selected product: id=${PRODUCT_ID}, name=${PRODUCT_NAME}, effectivePrice=${PRODUCT_EFFECTIVE_PRICE}, availableStock=${PRODUCT_AVAILABLE_STOCK}"
wait_for_public_api_ready
signup_result="$(request_json "POST" "${API_BASE_URL}/members/signup" "{\"email\":\"${BUYER_EMAIL}\",\"name\":\"${BUYER_NAME}\",\"password\":\"${BUYER_PASSWORD}\"}")"
signup_status="${signup_result%%:*}"
signup_file="${signup_result#*:}"
if [[ "${signup_status}" != "201" && "${signup_status}" != "409" ]]; then
  echo "signup failed: status=${signup_status}"
  cat "${signup_file}"
  exit 1
fi

echo "[2/11] login"
login_result="$(request_json "POST" "${API_BASE_URL}/auth/login" "{\"email\":\"${BUYER_EMAIL}\",\"password\":\"${BUYER_PASSWORD}\"}")"
login_status="${login_result%%:*}"
login_file="${login_result#*:}"
expect_status "${login_status}" "200" "${login_file}"
TOKEN="$(jq -r '.accessToken' "${login_file}")"
if [[ -z "${TOKEN}" || "${TOKEN}" == "null" ]]; then
  echo "login token missing"
  cat "${login_file}"
  exit 1
fi

echo "[3/11] wait for wallet projection and top up"
sleep "${WAIT_MEMBER_SECONDS}"

wallet_ready=false
for _ in $(seq 1 20); do
  wallet_result="$(request_json "GET" "${API_BASE_URL}/payments/wallet/me" "" "${TOKEN}")"
  wallet_status="${wallet_result%%:*}"
  wallet_file="${wallet_result#*:}"
  if [[ "${wallet_status}" == "200" ]]; then
    wallet_ready=true
    break
  fi
  sleep 1
done

if [[ "${wallet_ready}" != "true" ]]; then
  echo "wallet projection not ready"
  cat "${wallet_file}"
  exit 1
fi

WALLET_ID="$(jq -r '.id' "${wallet_file}")"
BUYER_ID="$(jq -r '.holderId' "${wallet_file}")"
if [[ -z "${WALLET_ID}" || "${WALLET_ID}" == "null" || -z "${BUYER_ID}" || "${BUYER_ID}" == "null" ]]; then
  echo "wallet metadata missing"
  cat "${wallet_file}"
  exit 1
fi

set_wallet_balance "${WALLET_BALANCE}"

created_order_ids=()
created_order_numbers=()

echo "[4/11] create ${ORDER_COUNT} successful orders"
for idx in $(seq 1 "${ORDER_COUNT}"); do
  clear_cart

  add_result="$(request_json "POST" "${API_BASE_URL}/carts/items" "{\"productId\":${PRODUCT_ID},\"quantity\":${PRODUCT_QUANTITY}}" "${TOKEN}")"
  add_status="${add_result%%:*}"
  add_file="${add_result#*:}"
  expect_status "${add_status}" "201" "${add_file}"

  cart_result="$(request_json "GET" "${API_BASE_URL}/carts" "" "${TOKEN}")"
  cart_status="${cart_result%%:*}"
  cart_file="${cart_result#*:}"
  expect_status "${cart_status}" "200" "${cart_file}"

  cart_item_ids="$(jq '[.items[].cartItemId]' "${cart_file}")"
  if [[ "${cart_item_ids}" == "[]" ]]; then
    echo "cart item ids empty"
    cat "${cart_file}"
    exit 1
  fi

  order_body="$(jq -nc \
    --argjson cartItemIds "${cart_item_ids}" \
    '{cartItemIds:$cartItemIds, zipCode:"06234", baseAddress:"Seoul Teheran-ro 123", detailAddress:"101-ho"}')"

  order_result="$(request_json "POST" "${API_BASE_URL}/orders" "${order_body}" "${TOKEN}")"
  order_status="${order_result%%:*}"
  order_file="${order_result#*:}"
  expect_status "${order_status}" "201" "${order_file}"

  order_id="$(jq -r '.orderId' "${order_file}")"
  order_number="$(jq -r '.orderNumber' "${order_file}")"
  if [[ -z "${order_id}" || "${order_id}" == "null" ]]; then
    echo "order id missing"
    cat "${order_file}"
    exit 1
  fi
  if [[ -z "${order_number}" || "${order_number}" == "null" ]]; then
    echo "order number missing"
    cat "${order_file}"
    exit 1
  fi

  created_order_ids+=("${order_id}")
  created_order_numbers+=("${order_number}")

  if ! wait_order_completed "${order_id}"; then
    echo "order did not reach PAYMENT_COMPLETED in time: orderId=${order_id}"
    exit 1
  fi

  sleep "${WAIT_BETWEEN_ORDERS_SECONDS}"
done

actual_refund_count="${REFUND_COUNT}"
if (( actual_refund_count > ${#created_order_ids[@]} )); then
  actual_refund_count="${#created_order_ids[@]}"
fi

echo "[5/11] refund ${actual_refund_count} orders"
refunded_order_ids=()
if (( actual_refund_count > 0 )); then
  for idx in $(seq 1 "${actual_refund_count}"); do
    order_id="${created_order_ids[$((idx - 1))]}"
    refunded_order_ids+=("${order_id}")
    cancel_result="$(request_json "POST" "${API_BASE_URL}/orders/${order_id}/cancel" "{\"cancelReasonType\":\"CHANGE_OF_MIND\",\"cancelReasonDetail\":\"portfolio scene ${idx}\"}" "${TOKEN}")"
    cancel_status="${cancel_result%%:*}"
    cancel_file="${cancel_result#*:}"
    expect_status "${cancel_status}" "204" "${cancel_file}"
    if ! wait_order_refunded "${order_id}"; then
      echo "refund did not complete in time: orderId=${order_id}"
      exit 1
    fi
    sleep "${WAIT_AFTER_CANCEL_SECONDS}"
  done
fi

completed_order_ids=()
completed_order_numbers=()
for idx in "${!created_order_ids[@]}"; do
  if (( idx >= actual_refund_count )); then
    completed_order_ids+=("${created_order_ids[$idx]}")
    completed_order_numbers+=("${created_order_numbers[$idx]}")
  fi
done

echo "[6/11] generate ${ORDER_FAILURE_COUNT} order creation failures"
for idx in $(seq 1 "${ORDER_FAILURE_COUNT}"); do
  clear_cart

  failed_order_body='{"cartItemIds":[99999999],"zipCode":"06234","baseAddress":"Seoul Teheran-ro 123","detailAddress":"101-ho"}'

  failed_order_result="$(request_json "POST" "${API_BASE_URL}/orders" "${failed_order_body}" "${TOKEN}")"
  failed_order_status="${failed_order_result%%:*}"
  failed_order_file="${failed_order_result#*:}"
  echo "order failure request ${idx} status=${failed_order_status}"
  cat "${failed_order_file}" >/dev/null
  sleep "${WAIT_AFTER_FAILURE_SECONDS}"
done

echo "[7/11] generate final payment failures"
for idx in $(seq 1 "${PAYMENT_FAILURE_UNKNOWN_ORDER_COUNT}"); do
  failure_result="$(request_json "POST" "${API_BASE_URL}/payments/confirm/toss" "{\"paymentKey\":\"portfolio-unknown-${RUN_ID}-${idx}\",\"orderId\":\"PORTFOLIO-PAYMENT-UNKNOWN-${RUN_ID}-${idx}\",\"amount\":${PRODUCT_EFFECTIVE_PRICE}}" )"
  failure_status="${failure_result%%:*}"
  failure_file="${failure_result#*:}"
  echo "payment unknown-order failure ${idx} status=${failure_status}"
  cat "${failure_file}" >/dev/null
  sleep "${WAIT_AFTER_FAILURE_SECONDS}"
done

actual_invalid_state_count="${PAYMENT_FAILURE_INVALID_STATE_COUNT}"
if (( actual_invalid_state_count > ${#completed_order_numbers[@]} )); then
  actual_invalid_state_count="${#completed_order_numbers[@]}"
fi

for idx in $(seq 1 "${actual_invalid_state_count}"); do
  order_number="${completed_order_numbers[$((idx - 1))]}"
  failure_result="$(request_json "POST" "${API_BASE_URL}/payments/confirm/toss" "{\"paymentKey\":\"portfolio-state-${RUN_ID}-${idx}\",\"orderId\":\"${order_number}\",\"amount\":${PRODUCT_EFFECTIVE_PRICE}}" )"
  failure_status="${failure_result%%:*}"
  failure_file="${failure_result#*:}"
  echo "payment invalid-state failure ${idx} status=${failure_status}"
  cat "${failure_file}" >/dev/null
  sleep "${WAIT_AFTER_FAILURE_SECONDS}"
done

if (( PAYMENT_FAILURE_LOW_BALANCE_COUNT > 0 )); then
  set_wallet_balance "${LOW_BALANCE_AMOUNT}"
  for idx in $(seq 1 "${PAYMENT_FAILURE_LOW_BALANCE_COUNT}"); do
    event_payload="$(jq -nc \
      --arg buyer_name "${BUYER_NAME}" \
      --arg order_number "PORTFOLIO-PAYMENT-LOWBAL-${RUN_ID}-${idx}" \
      --argjson buyer_id "${BUYER_ID}" \
      --argjson total_sale_price "${PRODUCT_EFFECTIVE_PRICE}" \
      --argjson order_id $((900000000 + BUYER_ID + 1000 + idx)) \
      '{order:{id:$order_id, buyerId:$buyer_id, buyerName:$buyer_name, orderNumber:$order_number, totalSalePrice:$total_sale_price}}')"
    kafka_publish_json \
      "${MARKET_ORDER_PAYMENT_COMPLETED_TOPIC}" \
      "${MARKET_ORDER_PAYMENT_COMPLETED_TYPE}" \
      "portfolio-lowbal-${RUN_ID}-${idx}" \
      "${event_payload}"
    sleep "${WAIT_AFTER_FAILURE_SECONDS}"
  done
  set_wallet_balance "${WALLET_BALANCE}"
fi

echo "[8/11] generate ${REFUND_FAILURE_UNKNOWN_ORDER_COUNT} refund failures (unknown order)"
for idx in $(seq 1 "${REFUND_FAILURE_UNKNOWN_ORDER_COUNT}"); do
  event_payload="$(jq -nc \
    --arg order_id "PORTFOLIO-REFUND-UNKNOWN-${RUN_ID}-${idx}" \
    '{dto:{orderId:$order_id}}')"
  kafka_publish_json \
    "${MARKET_ORDER_BEFORE_PAYMENT_REQUEST_CANCELED_TOPIC}" \
    "${MARKET_ORDER_BEFORE_PAYMENT_REQUEST_CANCELED_TYPE}" \
    "portfolio-refund-unknown-${RUN_ID}-${idx}" \
    "${event_payload}"
  sleep "${WAIT_AFTER_FAILURE_SECONDS}"
done

actual_refund_invalid_amount_count="${REFUND_FAILURE_INVALID_AMOUNT_COUNT}"
if (( actual_refund_invalid_amount_count > ${#completed_order_numbers[@]} )); then
  actual_refund_invalid_amount_count="${#completed_order_numbers[@]}"
fi

echo "[9/11] generate ${actual_refund_invalid_amount_count} refund failures (invalid amount)"
for idx in $(seq 1 "${actual_refund_invalid_amount_count}"); do
  order_number="${completed_order_numbers[$((idx - 1))]}"
  event_payload="$(jq -nc \
    --arg order_id "${order_number}" \
    '{dto:{orderId:$order_id, cancelReason:"portfolio refund invalid amount", amount:0}}')"
  kafka_publish_json \
    "${MARKET_ORDER_PAYMENT_REQUEST_CANCELED_TOPIC}" \
    "${MARKET_ORDER_PAYMENT_REQUEST_CANCELED_TYPE}" \
    "portfolio-refund-amount-${RUN_ID}-${idx}" \
    "${event_payload}"
  sleep "${WAIT_AFTER_FAILURE_SECONDS}"
done

echo "[10/11] generate stock failure events"
for idx in $(seq 1 "${STOCK_RESERVE_FAILURE_COUNT}"); do
  event_payload="$(jq -nc \
    --arg order_number "PORTFOLIO-STOCK-RESERVE-${RUN_ID}-${idx}" \
    '{orderNumber:$order_number, eventType:"RESERVE", items:[{productId:999999999, quantity:1}]}' )"
  kafka_publish_json \
    "${MARKET_ORDER_STOCK_CHANGED_TOPIC}" \
    "${MARKET_ORDER_STOCK_CHANGED_TYPE}" \
    "portfolio-stock-reserve-${RUN_ID}-${idx}" \
    "${event_payload}"
  sleep "${WAIT_AFTER_FAILURE_SECONDS}"
done

for idx in $(seq 1 "${STOCK_RELEASE_FAILURE_COUNT}"); do
  event_payload="$(jq -nc \
    --arg order_number "PORTFOLIO-STOCK-RELEASE-${RUN_ID}-${idx}" \
    '{orderNumber:$order_number, eventType:"RELEASE", items:[{productId:999999998, quantity:1}]}' )"
  kafka_publish_json \
    "${MARKET_ORDER_STOCK_CHANGED_TOPIC}" \
    "${MARKET_ORDER_STOCK_CHANGED_TYPE}" \
    "portfolio-stock-release-${RUN_ID}-${idx}" \
    "${event_payload}"
  sleep "${WAIT_AFTER_FAILURE_SECONDS}"
done

echo "[11/11] done"
echo
echo "portfolio dashboard scene completed"
echo "buyer_email=${BUYER_EMAIL}"
echo "buyer_password=${BUYER_PASSWORD}"
echo "product_id=${PRODUCT_ID}"
echo "order_count=${ORDER_COUNT}"
echo "refund_count=${actual_refund_count}"
echo "order_failure_count=${ORDER_FAILURE_COUNT}"
echo "payment_failure_unknown_order_count=${PAYMENT_FAILURE_UNKNOWN_ORDER_COUNT}"
echo "payment_failure_invalid_state_count=${actual_invalid_state_count}"
echo "payment_failure_low_balance_count=${PAYMENT_FAILURE_LOW_BALANCE_COUNT}"
echo "refund_failure_unknown_order_count=${REFUND_FAILURE_UNKNOWN_ORDER_COUNT}"
echo "refund_failure_invalid_amount_count=${actual_refund_invalid_amount_count}"
echo "stock_reserve_failure_count=${STOCK_RESERVE_FAILURE_COUNT}"
echo "stock_release_failure_count=${STOCK_RELEASE_FAILURE_COUNT}"
echo
echo "recommended grafana settings:"
echo "  time range: Last 15 minutes"
echo "  refresh: 5s"
echo "  capture window: 30-60s after script completion"
