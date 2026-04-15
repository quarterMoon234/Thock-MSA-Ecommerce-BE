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
API_BASE_URL="${API_BASE_URL:-http://localhost:8080/api/v1}"
GATEWAY_HEALTH_URL="${GATEWAY_HEALTH_URL:-http://localhost:8080/actuator/health}"
MARKET_INTERNAL_HEALTH_URL="${MARKET_INTERNAL_HEALTH_URL:-http://market-service:8083/actuator/health}"
PRODUCT_INTERNAL_HEALTH_URL="${PRODUCT_INTERNAL_HEALTH_URL:-http://product-service:8082/actuator/health}"
RESULT_DIR="${RESULT_DIR:-${ROOT_DIR}/loadtest/results/circuit-breaker-${RUN_ID}}"
SUMMARY_JSON="${SUMMARY_JSON:-${ROOT_DIR}/loadtest/results/circuit-breaker-before-after-${RUN_ID}.json}"

PRODUCT_IDS_CSV="${PRODUCT_IDS:-1,2,3}"
FAIL_REQUESTS="${FAIL_REQUESTS:-12}"
SETUP_WAIT_SECONDS="${SETUP_WAIT_SECONDS:-5}"
RECOVERY_WAIT_SECONDS="${RECOVERY_WAIT_SECONDS:-11}"
RECOVERY_ATTEMPTS="${RECOVERY_ATTEMPTS:-4}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-40}"
WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS:-2}"

BUYER_PASSWORD="${BUYER_PASSWORD:-password123!}"

MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT="${MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT:-700}"
MARKET_FEIGN_DEFAULT_READ_TIMEOUT="${MARKET_FEIGN_DEFAULT_READ_TIMEOUT:-1200}"
MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE="${MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE:-20}"
MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS="${MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS:-10}"
MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD="${MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD:-50}"
MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE="${MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE:-10s}"
MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN="${MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN:-1}"

mkdir -p "${RESULT_DIR}"

wait_for_http() {
  local url="$1"
  local label="$2"

  for _ in $(seq 1 "${WAIT_MAX_ATTEMPTS}"); do
    status="$(curl -s -o /dev/null -w "%{http_code}" "${url}" || true)"
    if [[ "${status}" == "200" ]]; then
      return 0
    fi
    sleep "${WAIT_SLEEP_SECONDS}"
  done

  echo "failed to wait for ${label}: url=${url}" >&2
  exit 1
}

wait_for_internal_http() {
  local url="$1"

  docker compose --profile loadtest run --no-deps --rm \
    -e WAIT_URL="${url}" \
    -e WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS}" \
    -e WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS}" \
    -e WAIT_STABLE_SUCCESSES=2 \
    k6 run /scripts/wait-http.js >/dev/null
}

write_market_override() {
  local phase="$1"
  local circuit_breaker_enabled="$2"
  local override_file="${RESULT_DIR}/${phase}-market.override.yml"

  cat > "${override_file}" <<EOF
services:
  market-service:
    environment:
      SPRING_PROFILES_ACTIVE: "docker,experiment"
      SPRING_CLOUD_OPENFEIGN_CIRCUITBREAKER_ENABLED: "${circuit_breaker_enabled}"
      MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT: "${MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT}"
      MARKET_FEIGN_DEFAULT_READ_TIMEOUT: "${MARKET_FEIGN_DEFAULT_READ_TIMEOUT}"
      MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE: "${MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE}"
      MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS: "${MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS}"
      MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD: "${MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD}"
      MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE: "${MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE}"
      MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN: "${MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN}"
EOF

  echo "${override_file}"
}

start_common_services() {
  docker compose up -d mysql redpanda redis member-service payment-service >/dev/null

  PRODUCT_SERVICE_PROFILES_ACTIVE=docker,experiment \
  docker compose up -d --build --force-recreate product-service >/dev/null
}

start_market_phase() {
  local phase="$1"
  local circuit_breaker_enabled="$2"
  local override_file

  override_file="$(write_market_override "${phase}" "${circuit_breaker_enabled}")"

  docker compose \
    -f docker-compose.yml \
    -f "${override_file}" \
    up -d --build --force-recreate --no-deps market-service >/dev/null

  docker compose up -d --no-deps api-gateway >/dev/null

  wait_for_internal_http "${PRODUCT_INTERNAL_HEALTH_URL}"
  wait_for_internal_http "${MARKET_INTERNAL_HEALTH_URL}"
  wait_for_http "${GATEWAY_HEALTH_URL}" "api-gateway"
}

signup_and_login() {
  local phase="$1"
  local email="buyer-cb-${RUN_ID}-${phase}@example.com"
  local name="buyer-cb-${RUN_ID}-${phase}"

  curl -s -X POST \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"name\":\"${name}\",\"password\":\"${BUYER_PASSWORD}\"}" \
    "${API_BASE_URL}/members/signup" \
    > "${RESULT_DIR}/${phase}-signup.json"

  curl -s -X POST \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"password\":\"${BUYER_PASSWORD}\"}" \
    "${API_BASE_URL}/auth/login" \
    > "${RESULT_DIR}/${phase}-login.json"

  token="$(jq -r '.accessToken' "${RESULT_DIR}/${phase}-login.json")"
  if [[ -z "${token}" || "${token}" == "null" ]]; then
    echo "login failed for phase=${phase}" >&2
    cat "${RESULT_DIR}/${phase}-login.json" >&2
    exit 1
  fi

  echo "${token}"
}

fill_cart() {
  local phase="$1"
  local token="$2"
  local product_ids=()

  IFS=',' read -r -a product_ids <<< "${PRODUCT_IDS_CSV}"

  sleep "${SETUP_WAIT_SECONDS}"

  for product_id in "${product_ids[@]}"; do
    local added=false
    for _ in $(seq 1 10); do
      status="$(curl -s -o "${RESULT_DIR}/${phase}-cart-add-${product_id}.json" -w "%{http_code}" \
        -H "Authorization: Bearer ${token}" \
        -H 'Content-Type: application/json' \
        -d "{\"productId\":${product_id},\"quantity\":1}" \
        "${API_BASE_URL}/carts/items")"

      if [[ "${status}" == "201" ]]; then
        added=true
        break
      fi

      sleep 1
    done

    if [[ "${added}" != "true" ]]; then
      echo "failed to add product to cart: phase=${phase} product_id=${product_id}" >&2
      cat "${RESULT_DIR}/${phase}-cart-add-${product_id}.json" >&2
      exit 1
    fi
  done

  curl -s \
    -H "Authorization: Bearer ${token}" \
    "${API_BASE_URL}/carts" \
    > "${RESULT_DIR}/${phase}-cart.json"

  jq '{cartItemIds: [.items[].cartItemId], zipCode: "06234", baseAddress: "서울특별시 강남구 테헤란로 123", detailAddress: "101호"}' \
    "${RESULT_DIR}/${phase}-cart.json" \
    > "${RESULT_DIR}/${phase}-order-create.json"

  if [[ "$(jq '.cartItemIds | length' "${RESULT_DIR}/${phase}-order-create.json")" -eq 0 ]]; then
    echo "cart item ids are empty for phase=${phase}" >&2
    cat "${RESULT_DIR}/${phase}-cart.json" >&2
    exit 1
  fi
}

prepare_phase_products() {
  local phase="$1"
  local output_path="/results/circuit-breaker-product-ids-${RUN_ID}-${phase}.json"
  local host_output="${ROOT_DIR}/loadtest/results/circuit-breaker-product-ids-${RUN_ID}-${phase}.json"

  docker compose --profile loadtest run --no-deps --rm \
    -e RUN_ID="${RUN_ID}" \
    -e PHASE="${phase}" \
    -e PRODUCT_COUNT="$(echo "${PRODUCT_IDS_CSV}" | awk -F',' '{print NF}')" \
    -e PRODUCT_SERVICE_BASE_URL="http://product-service:8082" \
    -e MARKET_SERVICE_BASE_URL="http://market-service:8083" \
    -e OUTPUT_PATH="${output_path}" \
    k6 run /scripts/circuit-breaker-prepare-products.js >/dev/null

  PRODUCT_IDS_CSV="$(jq -r '.productIds | join(",")' "${host_output}")"
}

normalize_body_json() {
  local body_file="$1"
  local normalized_file="$2"

  if jq -e . "${body_file}" >/dev/null 2>&1; then
    jq '.' "${body_file}" > "${normalized_file}"
  else
    jq -Rn --arg raw "$(cat "${body_file}")" '{raw:$raw}' > "${normalized_file}"
  fi
}

invoke_order_request() {
  local phase="$1"
  local attempt="$2"
  local token="$3"
  local payload_file="$4"
  local output_file="$5"

  local body_file="${RESULT_DIR}/${phase}-order-${attempt}.body.json"
  local normalized_file="${RESULT_DIR}/${phase}-order-${attempt}.normalized.json"
  local status_and_time
  local status
  local time_total

  status_and_time="$(curl -s -o "${body_file}" -w "%{http_code} %{time_total}" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d @"${payload_file}" \
    "${API_BASE_URL}/orders")"

  status="$(echo "${status_and_time}" | awk '{print $1}')"
  time_total="$(echo "${status_and_time}" | awk '{print $2}')"

  normalize_body_json "${body_file}" "${normalized_file}"

  jq -n \
    --arg attempt "${attempt}" \
    --argjson status "${status}" \
    --arg timeSec "${time_total}" \
    --slurpfile body "${normalized_file}" \
    '{
      attempt: $attempt,
      status: $status,
      timeMs: (($timeSec | tonumber) * 1000),
      body: $body[0]
    }' > "${output_file}"
}

wait_for_wallet_ready() {
  local token="$1"

  for _ in $(seq 1 20); do
    wallet_status="$(curl -s -o /tmp/cb-wallet-ready.json -w "%{http_code}" \
      -H "Authorization: Bearer ${token}" \
      "${API_BASE_URL}/payments/wallet/me")"

    if [[ "${wallet_status}" == "200" ]]; then
      return 0
    fi

    sleep 1
  done

  echo "payment-service wallet endpoint did not recover in time" >&2
  cat /tmp/cb-wallet-ready.json >&2 || true
  exit 1
}

run_failure_sequence() {
  local phase="$1"
  local token="$2"
  local start_mark="$3"
  local request_dir="${RESULT_DIR}/${phase}-failures"
  local file_index

  mkdir -p "${request_dir}"
  docker compose stop payment-service >/dev/null

  for attempt in $(seq 1 "${FAIL_REQUESTS}"); do
    printf -v file_index "%03d" "${attempt}"
    invoke_order_request "${phase}" "failure-${attempt}" "${token}" "${RESULT_DIR}/${phase}-order-create.json" \
      "${request_dir}/${file_index}.json"
  done

  docker compose logs --since="${start_mark}" market-service > "${RESULT_DIR}/${phase}-market.log" || true
}

run_recovery_sequence() {
  local phase="$1"
  local token="$2"
  local request_dir="${RESULT_DIR}/${phase}-recovery"
  local recovery_success=false
  local file_index

  mkdir -p "${request_dir}"

  docker compose up -d payment-service >/dev/null
  sleep "${RECOVERY_WAIT_SECONDS}"
  wait_for_wallet_ready "${token}"

  for attempt in $(seq 1 "${RECOVERY_ATTEMPTS}"); do
    printf -v file_index "%03d" "${attempt}"
    invoke_order_request "${phase}" "recovery-${attempt}" "${token}" "${RESULT_DIR}/${phase}-order-create.json" \
      "${request_dir}/${file_index}.json"
    status="$(jq -r '.status' "${request_dir}/${file_index}.json")"
    if [[ "${status}" == "201" ]]; then
      recovery_success=true
    fi
    sleep 4
  done

  echo "${recovery_success}"
}

summarize_phase() {
  local phase="$1"
  local cb_enabled="$2"
  local phase_file="${RESULT_DIR}/${phase}.json"
  local failure_dir="${RESULT_DIR}/${phase}-failures"
  local recovery_dir="${RESULT_DIR}/${phase}-recovery"
  local failure_requests_json
  local recovery_requests_json

  failure_requests_json="$(jq -s '.' "${failure_dir}"/*.json)"

  if compgen -G "${recovery_dir}/*.json" > /dev/null; then
    recovery_requests_json="$(jq -s '.' "${recovery_dir}"/*.json)"
  else
    recovery_requests_json='[]'
  fi

  jq -n \
    --arg phase "${phase}" \
    --argjson circuitBreakerEnabled "${cb_enabled}" \
    --argjson failRequestCount "${FAIL_REQUESTS}" \
    --argjson recoveryAttempts "${RECOVERY_ATTEMPTS}" \
    --argjson minimumNumberOfCalls "${MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS}" \
    --argjson failureRequests "${failure_requests_json}" \
    --argjson recoveryRequests "${recovery_requests_json}" \
    --arg marketLogPath "${RESULT_DIR}/${phase}-market.log" \
    '{
      phase: $phase,
      circuitBreakerEnabled: $circuitBreakerEnabled,
      failRequestCount: $failRequestCount,
      minimumNumberOfCalls: $minimumNumberOfCalls,
      failureRequests: $failureRequests,
      failureAvgTimeMs: (if ($failureRequests | length) > 0 then (($failureRequests | map(.timeMs) | add) / ($failureRequests | length)) else null end),
      followupFailureAvgTimeMs: (if ($failureRequests | length) > 1 then (($failureRequests[1:] | map(.timeMs) | add) / ($failureRequests[1:] | length)) else null end),
      postThresholdFailureAvgTimeMs:
        (if (($failureRequests | length) > $minimumNumberOfCalls)
         then (($failureRequests[$minimumNumberOfCalls:] | map(.timeMs) | add) / ($failureRequests[$minimumNumberOfCalls:] | length))
         else null
         end),
      recoveryRequests: $recoveryRequests,
      recoveryAttempts: $recoveryAttempts,
      marketLogPath: $marketLogPath
    }' > "${phase_file}"

  if [[ -f "${RESULT_DIR}/${phase}-market.log" ]]; then
    call_not_permitted_count="$(grep -c 'CALL_NOT_PERMITTED' "${RESULT_DIR}/${phase}-market.log" || true)"
    open_transition_count="$(grep -Ec 'transition=State transition from CLOSED to OPEN|changed state from CLOSED to OPEN' "${RESULT_DIR}/${phase}-market.log" || true)"
    half_open_transition_count="$(grep -Ec 'transition=State transition from OPEN to HALF_OPEN|changed state from OPEN to HALF_OPEN' "${RESULT_DIR}/${phase}-market.log" || true)"
    closed_transition_count="$(grep -Ec 'transition=State transition from HALF_OPEN to CLOSED|changed state from HALF_OPEN to CLOSED' "${RESULT_DIR}/${phase}-market.log" || true)"

    tmp_file="${phase_file}.tmp"
    jq \
      --argjson callNotPermittedCount "${call_not_permitted_count}" \
      --argjson openTransitionCount "${open_transition_count}" \
      --argjson halfOpenTransitionCount "${half_open_transition_count}" \
      --argjson closedTransitionCount "${closed_transition_count}" \
      '. + {
        callNotPermittedCount: $callNotPermittedCount,
        openTransitionCount: $openTransitionCount,
        halfOpenTransitionCount: $halfOpenTransitionCount,
        closedTransitionCount: $closedTransitionCount,
        callNotPermittedObserved: ($callNotPermittedCount > 0),
        openTransitionObserved: ($openTransitionCount > 0),
        halfOpenTransitionObserved: ($halfOpenTransitionCount > 0),
        closedTransitionObserved: ($closedTransitionCount > 0)
      }' "${phase_file}" > "${tmp_file}"
    mv "${tmp_file}" "${phase_file}"
  fi
}

run_phase() {
  local phase="$1"
  local circuit_breaker_enabled="$2"
  local start_mark
  local token

  start_mark="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  start_market_phase "${phase}" "${circuit_breaker_enabled}"
  prepare_phase_products "${phase}"
  token="$(signup_and_login "${phase}")"
  fill_cart "${phase}" "${token}"
  run_failure_sequence "${phase}" "${token}" "${start_mark}"

  if [[ "${phase}" == "after" ]]; then
    run_recovery_sequence "${phase}" "${token}" >/dev/null
    docker compose logs --since="${start_mark}" market-service > "${RESULT_DIR}/${phase}-market.log" || true
  else
    docker compose up -d payment-service >/dev/null
  fi

  summarize_phase "${phase}" "${circuit_breaker_enabled}"
}

combine_summary() {
  jq -n \
    --arg runId "${RUN_ID}" \
    --arg apiBaseUrl "${API_BASE_URL}" \
    --arg productIds "${PRODUCT_IDS_CSV}" \
    --argjson failRequests "${FAIL_REQUESTS}" \
    --argjson connectTimeout "${MARKET_FEIGN_DEFAULT_CONNECT_TIMEOUT}" \
    --argjson readTimeout "${MARKET_FEIGN_DEFAULT_READ_TIMEOUT}" \
    --argjson minimumNumberOfCalls "${MARKET_CB_PAYMENT_MINIMUM_NUMBER_OF_CALLS}" \
    --argjson slidingWindowSize "${MARKET_CB_PAYMENT_SLIDING_WINDOW_SIZE}" \
    --argjson failureRateThreshold "${MARKET_CB_PAYMENT_FAILURE_RATE_THRESHOLD}" \
    --argjson permittedCallsInHalfOpen "${MARKET_CB_PAYMENT_PERMITTED_CALLS_IN_HALF_OPEN}" \
    --arg cbWaitDuration "${MARKET_CB_PAYMENT_WAIT_DURATION_IN_OPEN_STATE}" \
    --slurpfile before "${RESULT_DIR}/before.json" \
    --slurpfile after "${RESULT_DIR}/after.json" \
    '{
      runId: $runId,
      config: {
        apiBaseUrl: $apiBaseUrl,
        productIds: ($productIds | split(",") | map(tonumber)),
        failRequests: $failRequests,
        feignConnectTimeoutMs: $connectTimeout,
        feignReadTimeoutMs: $readTimeout,
        cbSlidingWindowSize: $slidingWindowSize,
        cbMinimumNumberOfCalls: $minimumNumberOfCalls,
        cbFailureRateThreshold: $failureRateThreshold,
        cbWaitDurationInOpenState: $cbWaitDuration
        ,
        cbPermittedCallsInHalfOpenState: $permittedCallsInHalfOpen
      },
      before: $before[0],
      after: $after[0],
      improvements: {
        postThresholdFailureTimeReductionPct:
          (if (($before[0].postThresholdFailureAvgTimeMs // 0) > 0 and ($after[0].postThresholdFailureAvgTimeMs // 0) > 0)
           then ((($before[0].postThresholdFailureAvgTimeMs - $after[0].postThresholdFailureAvgTimeMs) / $before[0].postThresholdFailureAvgTimeMs) * 100)
           else null
           end)
      },
      validations: {
        beforePostThresholdFailuresSlow:
          (($before[0].failureRequests | map(.status != 201) | all) and (($before[0].postThresholdFailureAvgTimeMs // 0) > 100)),
        afterOpenBlockedFast:
          (($after[0].callNotPermittedObserved // false) and (($after[0].postThresholdFailureAvgTimeMs // 999999) < 200)),
        afterRecovered:
          (($after[0].closedTransitionObserved // false) and (($after[0].recoveryRequests | length) > 0) and (($after[0].recoveryRequests | map(.status == 201) | any)))
      }
    }' > "${SUMMARY_JSON}"
}

start_common_services
run_phase "before" "false"
run_phase "after" "true"
combine_summary

echo "run_id=${RUN_ID}"
echo "summary_json=${SUMMARY_JSON}"
