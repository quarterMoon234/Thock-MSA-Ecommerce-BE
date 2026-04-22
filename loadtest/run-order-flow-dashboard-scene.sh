#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL="${API_BASE_URL:-http://localhost:8080/api/v1}"
PRODUCT_ID="${PRODUCT_ID:-}"
PRODUCT_ID_CANDIDATES="${PRODUCT_ID_CANDIDATES:-4,5,6}"
ORDER_COUNT="${ORDER_COUNT:-5}"
REFUND_COUNT="${REFUND_COUNT:-2}"
FAILURE_COUNT="${FAILURE_COUNT:-3}"
PRODUCT_QUANTITY="${PRODUCT_QUANTITY:-1}"
BUYER_PASSWORD="${BUYER_PASSWORD:-password123!}"
WALLET_BALANCE="${WALLET_BALANCE:-1000000}"
WAIT_MEMBER_SECONDS="${WAIT_MEMBER_SECONDS:-5}"
WAIT_API_READY_SECONDS="${WAIT_API_READY_SECONDS:-60}"
WAIT_ORDER_COMPLETE_SECONDS="${WAIT_ORDER_COMPLETE_SECONDS:-15}"
WAIT_AFTER_CANCEL_SECONDS="${WAIT_AFTER_CANCEL_SECONDS:-5}"
WAIT_BETWEEN_ORDERS_SECONDS="${WAIT_BETWEEN_ORDERS_SECONDS:-1}"
WAIT_AFTER_FAILURE_SECONDS="${WAIT_AFTER_FAILURE_SECONDS:-1}"
RUN_ID="${RUN_ID:-$(date +%s)}"
BUYER_EMAIL="${BUYER_EMAIL:-buyer-dashboard-${RUN_ID}@example.com}"
BUYER_NAME="${BUYER_NAME:-buyer-dashboard-${RUN_ID}}"
MYSQL_SERVICE="${MYSQL_SERVICE:-mysql}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"
PAYMENT_DB_NAME="${PAYMENT_DB_NAME:-thock_payment_db}"
PRODUCT_DB_NAME="${PRODUCT_DB_NAME:-thock_product_db}"
MARKET_DB_NAME="${MARKET_DB_NAME:-thock_market_db}"

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
EXPECTED_ORDER_CREATED_COUNT=$((ORDER_COUNT + FAILURE_COUNT))
EXPECTED_PAYMENT_COMPLETED_COUNT=${ORDER_COUNT}
EXPECTED_PAYMENT_FAILED_COUNT=${FAILURE_COUNT}
EXPECTED_STOCK_RESERVED_COUNT=${ORDER_COUNT}
EXPECTED_STOCK_RELEASED_COUNT=${REFUND_COUNT}

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

  echo "selected product has sale_price=0; normalizing test data to use price=${PRODUCT_PRICE}"

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

product_row="$(select_orderable_product)"
if [[ -z "${product_row}" ]]; then
  if [[ -n "${PRODUCT_ID}" ]]; then
    echo "PRODUCT_ID=${PRODUCT_ID} 상품이 연출용 주문 조건을 만족하지 않습니다."
  else
    echo "연출용 주문 조건을 만족하는 상품을 찾지 못했습니다."
  fi
  echo "조건: state=ON_SALE, effective_price > 0, available_stock >= ${required_available_stock}"
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

created_order_ids=()
created_failure_order_numbers=()

clear_cart() {
  local token="$1"
  local clear_result
  clear_result="$(request_json "DELETE" "${API_BASE_URL}/carts/items" "[]" "${token}")"
  local clear_status="${clear_result%%:*}"
  local clear_file="${clear_result#*:}"
  expect_status "${clear_status}" "204" "${clear_file}"
}

wait_order_completed() {
  local token="$1"
  local order_id="$2"
  for _ in $(seq 1 "${WAIT_ORDER_COMPLETE_SECONDS}"); do
    local detail_result detail_status detail_file state
    detail_result="$(request_json "GET" "${API_BASE_URL}/orders/${order_id}" "" "${token}")"
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
  local token="$1"
  local order_id="$2"
  for _ in $(seq 1 "${WAIT_ORDER_COMPLETE_SECONDS}"); do
    local detail_result detail_status detail_file state
    detail_result="$(request_json "GET" "${API_BASE_URL}/orders/${order_id}" "" "${token}")"
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

wait_wallet_id() {
  local token="$1"
  for _ in $(seq 1 20); do
    local wallet_result wallet_status wallet_file
    wallet_result="$(request_json "GET" "${API_BASE_URL}/payments/wallet/me" "" "${token}")"
    wallet_status="${wallet_result%%:*}"
    wallet_file="${wallet_result#*:}"
    if [[ "${wallet_status}" == "200" ]]; then
      jq -r '.id' "${wallet_file}"
      return 0
    fi
    sleep 1
  done
  return 1
}

signup_and_login_buyer() {
  local email="$1"
  local name="$2"

  local signup_result signup_status signup_file
  signup_result="$(request_json "POST" "${API_BASE_URL}/members/signup" "{\"email\":\"${email}\",\"name\":\"${name}\",\"password\":\"${BUYER_PASSWORD}\"}")"
  signup_status="${signup_result%%:*}"
  signup_file="${signup_result#*:}"
  if [[ "${signup_status}" != "201" && "${signup_status}" != "409" ]]; then
    echo "signup failed: status=${signup_status}"
    cat "${signup_file}"
    exit 1
  fi

  local login_result login_status login_file token
  login_result="$(request_json "POST" "${API_BASE_URL}/auth/login" "{\"email\":\"${email}\",\"password\":\"${BUYER_PASSWORD}\"}")"
  login_status="${login_result%%:*}"
  login_file="${login_result#*:}"
  expect_status "${login_status}" "200" "${login_file}"
  token="$(jq -r '.accessToken' "${login_file}")"
  if [[ -z "${token}" || "${token}" == "null" ]]; then
    echo "login token missing"
    cat "${login_file}"
    exit 1
  fi
  echo "${token}"
}

topup_wallet() {
  local wallet_id="$1"
  local balance="$2"
  docker compose exec -T "${MYSQL_SERVICE}" mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" "${PAYMENT_DB_NAME}" \
    -e "UPDATE payment_wallets SET balance=${balance} WHERE id=${wallet_id};" >/dev/null
}

create_order_for_token() {
  local token="$1"
  clear_cart "${token}"

  local add_result add_status add_file
  add_result="$(request_json "POST" "${API_BASE_URL}/carts/items" "{\"productId\":${PRODUCT_ID},\"quantity\":${PRODUCT_QUANTITY}}" "${token}")"
  add_status="${add_result%%:*}"
  add_file="${add_result#*:}"
  expect_status "${add_status}" "201" "${add_file}"

  local cart_result cart_status cart_file cart_item_ids
  cart_result="$(request_json "GET" "${API_BASE_URL}/carts" "" "${token}")"
  cart_status="${cart_result%%:*}"
  cart_file="${cart_result#*:}"
  expect_status "${cart_status}" "200" "${cart_file}"

  cart_item_ids="$(jq '[.items[].cartItemId]' "${cart_file}")"
  if [[ "${cart_item_ids}" == "[]" ]]; then
    echo "cart item ids empty"
    cat "${cart_file}"
    exit 1
  fi

  local order_body order_result order_status order_file
  order_body="$(jq -nc \
    --argjson cartItemIds "${cart_item_ids}" \
    '{cartItemIds:$cartItemIds, zipCode:"06234", baseAddress:"서울특별시 강남구 테헤란로 123", detailAddress:"101호"}')"

  order_result="$(request_json "POST" "${API_BASE_URL}/orders" "${order_body}" "${token}")"
  order_status="${order_result%%:*}"
  order_file="${order_result#*:}"
  expect_status "${order_status}" "201" "${order_file}"
  jq -r '[.orderId, .orderNumber, .pgAmount, .totalSalePrice] | @tsv' "${order_file}"
}

wait_payment_requested() {
  local order_number="$1"
  for _ in $(seq 1 "${WAIT_ORDER_COMPLETE_SECONDS}"); do
    local status
    status="$(docker compose exec -T "${MYSQL_SERVICE}" mysql -N -B -uroot -p"${MYSQL_ROOT_PASSWORD}" "${PAYMENT_DB_NAME}" \
      -e "SELECT status FROM payment_payments WHERE order_id='${order_number}' LIMIT 1;" 2>/dev/null || true)"
    if [[ "${status}" == "REQUESTED" || "${status}" == "PG_PENDING" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

echo "[1/6] prepare success buyer"
echo "selected product: id=${PRODUCT_ID}, name=${PRODUCT_NAME}, salePrice=${PRODUCT_EFFECTIVE_PRICE}, availableStock=${PRODUCT_AVAILABLE_STOCK}"
wait_for_public_api_ready
TOKEN="$(signup_and_login_buyer "${BUYER_EMAIL}" "${BUYER_NAME}")"

echo "[2/6] wait for wallet projection and top up wallet"
sleep "${WAIT_MEMBER_SECONDS}"

WALLET_ID="$(wait_wallet_id "${TOKEN}" || true)"
if [[ -z "${WALLET_ID}" ]]; then
  echo "wallet projection not ready"
  exit 1
fi
topup_wallet "${WALLET_ID}" "${WALLET_BALANCE}"

echo "[3/6] create ${ORDER_COUNT} successful orders"
for idx in $(seq 1 "${ORDER_COUNT}"); do
  IFS=$'\t' read -r order_id _success_order_number _success_pg_amount _success_total_sale_price <<< "$(create_order_for_token "${TOKEN}")"

  created_order_ids+=("${order_id}")

  if ! wait_order_completed "${TOKEN}" "${order_id}"; then
    echo "order did not reach PAYMENT_COMPLETED in time: orderId=${order_id}"
    exit 1
  fi

  sleep "${WAIT_BETWEEN_ORDERS_SECONDS}"
done

actual_refund_count="${REFUND_COUNT}"
if (( actual_refund_count > ${#created_order_ids[@]} )); then
  actual_refund_count="${#created_order_ids[@]}"
fi

echo "[4/6] cancel ${actual_refund_count} completed orders to generate refund/release"
if (( actual_refund_count > 0 )); then
  for idx in $(seq 1 "${actual_refund_count}"); do
    order_id="${created_order_ids[$((idx - 1))]}"
    cancel_result="$(request_json "POST" "${API_BASE_URL}/orders/${order_id}/cancel" "{\"cancelReasonType\":\"CHANGE_OF_MIND\",\"cancelReasonDetail\":\"portfolio dashboard scene ${idx}\"}" "${TOKEN}")"
    cancel_status="${cancel_result%%:*}"
    cancel_file="${cancel_result#*:}"
    expect_status "${cancel_status}" "204" "${cancel_file}"
    if ! wait_order_refunded "${TOKEN}" "${order_id}"; then
      echo "refund did not complete in time: orderId=${order_id}"
      exit 1
    fi
    sleep "${WAIT_AFTER_CANCEL_SECONDS}"
  done
fi

echo "[5/6] create ${FAILURE_COUNT} real failed payment orders"
for idx in $(seq 1 "${FAILURE_COUNT}"); do
  failure_email="buyer-dashboard-fail-${RUN_ID}-${idx}@example.com"
  failure_name="buyer-dashboard-fail-${RUN_ID}-${idx}"
  failure_token="$(signup_and_login_buyer "${failure_email}" "${failure_name}")"

  sleep "${WAIT_MEMBER_SECONDS}"
  failure_wallet_id="$(wait_wallet_id "${failure_token}" || true)"
  if [[ -z "${failure_wallet_id}" ]]; then
    echo "failure buyer wallet projection not ready: idx=${idx}"
    exit 1
  fi

  IFS=$'\t' read -r failure_order_id failure_order_number failure_pg_amount failure_total_sale_price <<< "$(create_order_for_token "${failure_token}")"

  if [[ -z "${failure_order_number}" || "${failure_order_number}" == "null" ]]; then
    echo "failure order number missing: idx=${idx}"
    exit 1
  fi

  if [[ -z "${failure_pg_amount}" || "${failure_pg_amount}" == "null" || "${failure_pg_amount}" == "0" ]]; then
    echo "failure scenario requires pgAmount > 0: orderNumber=${failure_order_number}, totalSalePrice=${failure_total_sale_price}, pgAmount=${failure_pg_amount}"
    exit 1
  fi

  if ! wait_payment_requested "${failure_order_number}"; then
    echo "payment request record not ready in time: orderNumber=${failure_order_number}"
    exit 1
  fi

  created_failure_order_numbers+=("${failure_order_number}")

  failure_result="$(request_json "POST" "${API_BASE_URL}/payments/confirm/toss" "{\"paymentKey\":\"portfolio-fail-${RUN_ID}-${idx}\",\"orderId\":\"${failure_order_number}\",\"amount\":${failure_pg_amount}}")"
  failure_status="${failure_result%%:*}"
  failure_file="${failure_result#*:}"
  echo "payment failure request ${idx} status=${failure_status}, orderNumber=${failure_order_number}, pgAmount=${failure_pg_amount}"
  cat "${failure_file}" >/dev/null
  sleep "${WAIT_AFTER_FAILURE_SECONDS}"
done

echo "[6/6] done"
echo
echo "dashboard scene completed"
echo "buyer_email=${BUYER_EMAIL}"
echo "buyer_password=${BUYER_PASSWORD}"
echo "product_id=${PRODUCT_ID}"
echo "created_order_ids=${created_order_ids[*]}"
echo "failed_order_numbers=${created_failure_order_numbers[*]}"
echo "refund_count=${actual_refund_count}"
echo "failure_count=${FAILURE_COUNT}"
echo
echo "expected overview counts:"
echo "  주문 생성 완료=${EXPECTED_ORDER_CREATED_COUNT}"
echo "  환불 처리 완료=${actual_refund_count}"
echo "  최종 결제 완료=${EXPECTED_PAYMENT_COMPLETED_COUNT}"
echo "  최종 결제 실패=${EXPECTED_PAYMENT_FAILED_COUNT}"
echo "  재고 예약 완료=${EXPECTED_STOCK_RESERVED_COUNT}"
echo "  재고 복구 완료=${actual_refund_count}"
echo
echo "recommended grafana settings:"
echo "  time range: Last 15 minutes"
echo "  refresh: 5s"
