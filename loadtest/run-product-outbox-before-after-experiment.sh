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

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "required command not found: $1" >&2
    exit 1
  fi
}

require_cmd docker
require_cmd jq

RUN_ID="${RUN_ID:-$(date +%s)}"
TOPIC="${TOPIC:-product.changed}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"
BASE_URL="${K6_BASE_URL:-http://api-gateway:8080}"
PRODUCT_SERVICE_BASE_URL="${PRODUCT_SERVICE_BASE_URL:-http://product-service:8082}"
PRODUCT_SERVICE_HEALTH_URL="${PRODUCT_SERVICE_HEALTH_URL:-${PRODUCT_SERVICE_BASE_URL}/actuator/health}"
GATEWAY_HEALTH_URL="${GATEWAY_HEALTH_URL:-http://api-gateway:8080/actuator/health}"

PRODUCT_SERVICE_PROFILES_ACTIVE="${PRODUCT_SERVICE_PROFILES_ACTIVE:-docker,experiment}"
PRODUCT_OUTBOX_POLLER_INTERVAL_MS="${PRODUCT_OUTBOX_POLLER_INTERVAL_MS:-1000}"
DIRECT_PRODUCT_KAFKA_REQUEST_TIMEOUT_MS="${DIRECT_PRODUCT_KAFKA_REQUEST_TIMEOUT_MS:-100}"
DIRECT_PRODUCT_KAFKA_DELIVERY_TIMEOUT_MS="${DIRECT_PRODUCT_KAFKA_DELIVERY_TIMEOUT_MS:-200}"
DIRECT_PRODUCT_KAFKA_MAX_BLOCK_MS="${DIRECT_PRODUCT_KAFKA_MAX_BLOCK_MS:-10}"
AFTER_PRODUCT_KAFKA_REQUEST_TIMEOUT_MS="${AFTER_PRODUCT_KAFKA_REQUEST_TIMEOUT_MS:-1000}"
AFTER_PRODUCT_KAFKA_DELIVERY_TIMEOUT_MS="${AFTER_PRODUCT_KAFKA_DELIVERY_TIMEOUT_MS:-2000}"
AFTER_PRODUCT_KAFKA_MAX_BLOCK_MS="${AFTER_PRODUCT_KAFKA_MAX_BLOCK_MS:-1000}"
DIRECT_ASYNC_AFTER_COMMIT_ENABLED="${DIRECT_ASYNC_AFTER_COMMIT_ENABLED:-true}"
DIRECT_ASYNC_CORE_POOL_SIZE="${DIRECT_ASYNC_CORE_POOL_SIZE:-16}"
DIRECT_ASYNC_MAX_POOL_SIZE="${DIRECT_ASYNC_MAX_POOL_SIZE:-32}"
DIRECT_ASYNC_QUEUE_CAPACITY="${DIRECT_ASYNC_QUEUE_CAPACITY:-4000}"
DIRECT_WARMUP_ENABLED="${DIRECT_WARMUP_ENABLED:-true}"
DIRECT_WARMUP_ITERATIONS="${DIRECT_WARMUP_ITERATIONS:-1}"
DIRECT_WARMUP_VUS="${DIRECT_WARMUP_VUS:-1}"

K6_ITERATIONS="${K6_ITERATIONS:-2400}"
K6_VUS="${K6_VUS:-50}"
K6_STOCK="${K6_STOCK:-5}"
K6_SLEEP_BETWEEN="${K6_SLEEP_BETWEEN:-0}"

RECOVERY_POLL_INTERVAL_SECONDS="${RECOVERY_POLL_INTERVAL_SECONDS:-1}"
RECOVERY_POLL_TIMEOUT_SECONDS="${RECOVERY_POLL_TIMEOUT_SECONDS:-300}"
DIRECT_SETTLE_SECONDS="${DIRECT_SETTLE_SECONDS:-3}"
PHASE_START_GRACE_SECONDS="${PHASE_START_GRACE_SECONDS:-3}"

BEFORE_PHASE_RUN_ID="${RUN_ID}-before"
AFTER_PHASE_RUN_ID="${RUN_ID}-after"
DIRECT_WARMUP_RUN_ID="${RUN_ID}-direct-warmup"

BEFORE_K6_SUMMARY_PATH="/results/product-outbox-before-${RUN_ID}-k6.json"
AFTER_K6_SUMMARY_PATH="/results/product-outbox-after-${RUN_ID}-k6.json"
RESULT_PATH="loadtest/results/product-outbox-before-after-${RUN_ID}.json"

wait_for_http() {
  local url="$1"
  docker compose --profile loadtest run --no-deps --rm \
    -e WAIT_URL="${url}" \
    -e WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-60}" \
    -e WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS:-2}" \
    -e WAIT_STABLE_SUCCESSES="${WAIT_STABLE_SUCCESSES:-3}" \
    k6 run /scripts/wait-http.js >/dev/null
}

wait_for_redpanda() {
  local max_attempts="${REDPANDA_WAIT_MAX_ATTEMPTS:-60}"
  local sleep_seconds="${REDPANDA_WAIT_SLEEP_SECONDS:-2}"
  local attempt=1

  while (( attempt <= max_attempts )); do
    if docker compose exec -T redpanda rpk cluster info >/dev/null 2>&1; then
      return 0
    fi

    sleep "${sleep_seconds}"
    attempt=$((attempt + 1))
  done

  echo "Redpanda did not become ready in time." >&2
  exit 1
}

db_scalar() {
  local query="$1"
  docker compose exec -T \
    -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" \
    mysql mysql -uroot -Nse "${query}"
}

run_product_count() {
  local phase_run_id="$1"
  db_scalar "
    SELECT COUNT(*)
    FROM thock_product_db.products
    WHERE name LIKE 'k6-${phase_run_id}-%';
  "
}

run_outbox_total_count() {
  local phase_run_id="$1"
  db_scalar "
    SELECT COUNT(*)
    FROM thock_product_db.product_outbox_event e
    JOIN thock_product_db.products p
      ON CAST(e.event_key AS UNSIGNED) = p.id
    WHERE p.name LIKE 'k6-${phase_run_id}-%';
  "
}

run_outbox_status_count() {
  local phase_run_id="$1"
  local status="$2"
  db_scalar "
    SELECT COUNT(*)
    FROM thock_product_db.product_outbox_event e
    JOIN thock_product_db.products p
      ON CAST(e.event_key AS UNSIGNED) = p.id
    WHERE p.name LIKE 'k6-${phase_run_id}-%'
      AND e.status = '${status}';
  "
}

collect_topic_stats() {
  local phase_run_id="$1"
  local start_topic_count="$2"
  bash loadtest/run-scoped-topic-stats.sh "${phase_run_id}" "${start_topic_count}" "${TOPIC}"
}

millis_now() {
  echo $(( $(date +%s) * 1000 ))
}

start_product_service() {
  local publish_mode="$1"
  local poller_interval_ms="${2:-1000}"
  local kafka_request_timeout_ms="${3:-1000}"
  local kafka_delivery_timeout_ms="${4:-2000}"
  local kafka_max_block_ms="${5:-1000}"
  local direct_async_after_commit_enabled="${6:-false}"

  PRODUCT_SERVICE_PROFILES_ACTIVE="${PRODUCT_SERVICE_PROFILES_ACTIVE}" \
  PRODUCT_EVENT_PUBLISH_MODE="${publish_mode}" \
  PRODUCT_OUTBOX_POLLER_INTERVAL_MS="${poller_interval_ms}" \
  PRODUCT_KAFKA_REQUEST_TIMEOUT_MS="${kafka_request_timeout_ms}" \
  PRODUCT_KAFKA_DELIVERY_TIMEOUT_MS="${kafka_delivery_timeout_ms}" \
  PRODUCT_KAFKA_MAX_BLOCK_MS="${kafka_max_block_ms}" \
  PRODUCT_EVENT_DIRECT_ASYNC_AFTER_COMMIT_ENABLED="${direct_async_after_commit_enabled}" \
  PRODUCT_EVENT_DIRECT_ASYNC_CORE_POOL_SIZE="${DIRECT_ASYNC_CORE_POOL_SIZE}" \
  PRODUCT_EVENT_DIRECT_ASYNC_MAX_POOL_SIZE="${DIRECT_ASYNC_MAX_POOL_SIZE}" \
  PRODUCT_EVENT_DIRECT_ASYNC_QUEUE_CAPACITY="${DIRECT_ASYNC_QUEUE_CAPACITY}" \
  docker compose up -d --build product-service
}

run_create_load() {
  local phase_run_id="$1"
  local summary_path="$2"
  local enforce_create_success="${3:-true}"
  local request_timeout="${4:-180s}"
  local iterations_override="${5:-${K6_ITERATIONS}}"
  local vus_override="${6:-${K6_VUS}}"

  docker compose --profile loadtest run --no-deps --rm \
    -e RUN_ID="${phase_run_id}" \
    -e EXPERIMENT_NAME="product-outbox-before-after" \
    -e SUMMARY_PATH="${summary_path}" \
    -e BASE_URL="${BASE_URL}" \
    -e PASSWORD="${K6_PASSWORD:-password123!}" \
    -e K6_ITERATIONS="${iterations_override}" \
    -e K6_VUS="${vus_override}" \
    -e CATEGORY="${K6_CATEGORY:-KEYBOARD}" \
    -e PRICE="${K6_PRICE:-1000}" \
    -e SALE_PRICE="${K6_SALE_PRICE:-0}" \
    -e STOCK="${K6_STOCK}" \
    -e BANK_CODE="${K6_BANK_CODE:-088}" \
    -e ACCOUNT_NUMBER="${K6_ACCOUNT_NUMBER:-1234567890}" \
    -e ACCOUNT_HOLDER="${K6_ACCOUNT_HOLDER:-k6-seller}" \
    -e SLEEP_BETWEEN="${K6_SLEEP_BETWEEN}" \
    -e SELLER_ACCESS_TOKEN="${SELLER_ACCESS_TOKEN}" \
    -e ENFORCE_CREATE_SUCCESS="${enforce_create_success}" \
    -e REQUEST_TIMEOUT="${request_timeout}" \
    k6 run /scripts/product-outbox-recovery.js
}

prepare_seller_access_token() {
  docker compose --profile loadtest run --no-deps --rm \
    -e RUN_ID="${RUN_ID}" \
    -e BASE_URL="${BASE_URL}" \
    -e PASSWORD="${K6_PASSWORD:-password123!}" \
    -e BANK_CODE="${K6_BANK_CODE:-088}" \
    -e ACCOUNT_NUMBER="${K6_ACCOUNT_NUMBER:-1234567890}" \
    -e ACCOUNT_HOLDER="${K6_ACCOUNT_HOLDER:-k6-seller}" \
    k6 run --quiet /scripts/prepare-seller-token.js 2>&1 \
    | sed -n 's/.*__SELLER_ACCESS_TOKEN__=\([^ ]*\).*/\1/p' \
    | tail -n 1
}

docker compose up -d mysql redpanda member-service api-gateway
wait_for_redpanda
wait_for_http "${GATEWAY_HEALTH_URL}"

SELLER_ACCESS_TOKEN="$(prepare_seller_access_token)"
if [[ -z "${SELLER_ACCESS_TOKEN}" ]]; then
  echo "Failed to prepare seller access token." >&2
  exit 1
fi

#
# BEFORE: direct publish mode under broker outage, then product-service restart
#
start_product_service direct "${PRODUCT_OUTBOX_POLLER_INTERVAL_MS}" \
  "${DIRECT_PRODUCT_KAFKA_REQUEST_TIMEOUT_MS}" \
  "${DIRECT_PRODUCT_KAFKA_DELIVERY_TIMEOUT_MS}" \
  "${DIRECT_PRODUCT_KAFKA_MAX_BLOCK_MS}" \
  "${DIRECT_ASYNC_AFTER_COMMIT_ENABLED}"
wait_for_http "${PRODUCT_SERVICE_HEALTH_URL}"
sleep "${PHASE_START_GRACE_SECONDS}"

if [[ "${DIRECT_WARMUP_ENABLED}" == "true" ]]; then
  run_create_load "${DIRECT_WARMUP_RUN_ID}" \
    "/results/product-outbox-before-${RUN_ID}-warmup-k6.json" \
    true \
    "${DIRECT_WARMUP_REQUEST_TIMEOUT:-30s}" \
    "${DIRECT_WARMUP_ITERATIONS}" \
    "${DIRECT_WARMUP_VUS}"
  sleep "${DIRECT_WARMUP_SETTLE_SECONDS:-1}"
fi

BEFORE_START_TOPIC_COUNT="$(bash loadtest/topic-message-count.sh "${TOPIC}")"

docker compose stop redpanda >/dev/null
run_create_load "${BEFORE_PHASE_RUN_ID}" "${BEFORE_K6_SUMMARY_PATH}" false "${DIRECT_REQUEST_TIMEOUT:-5s}"

BEFORE_DB_PRODUCTS_CREATED="$(run_product_count "${BEFORE_PHASE_RUN_ID}")"

docker compose restart product-service >/dev/null
sleep "${DIRECT_SETTLE_SECONDS}"

docker compose up -d redpanda >/dev/null
wait_for_redpanda
wait_for_http "${PRODUCT_SERVICE_HEALTH_URL}"

sleep "${DIRECT_SETTLE_SECONDS}"

BEFORE_MATCHED_TOPIC_MESSAGES=0
BEFORE_MATCHED_UNIQUE_PRODUCTS=0
BEFORE_DUPLICATE_TOPIC_MESSAGES=0
BEFORE_UNRELATED_TOPIC_MESSAGES=0

while IFS='=' read -r key value; do
  case "${key}" in
    matched_topic_messages)
      BEFORE_MATCHED_TOPIC_MESSAGES="${value}"
      ;;
    matched_unique_products)
      BEFORE_MATCHED_UNIQUE_PRODUCTS="${value}"
      ;;
    duplicate_topic_messages)
      BEFORE_DUPLICATE_TOPIC_MESSAGES="${value}"
      ;;
    unrelated_topic_messages)
      BEFORE_UNRELATED_TOPIC_MESSAGES="${value}"
      ;;
  esac
done < <(collect_topic_stats "${BEFORE_PHASE_RUN_ID}" "${BEFORE_START_TOPIC_COUNT}")

BEFORE_MISSING_PUBLISHED_EVENT="$((BEFORE_DB_PRODUCTS_CREATED - BEFORE_MATCHED_UNIQUE_PRODUCTS))"

#
# AFTER: outbox mode under broker outage, then recovery convergence
#
start_product_service outbox "${PRODUCT_OUTBOX_POLLER_INTERVAL_MS}" \
  "${AFTER_PRODUCT_KAFKA_REQUEST_TIMEOUT_MS}" \
  "${AFTER_PRODUCT_KAFKA_DELIVERY_TIMEOUT_MS}" \
  "${AFTER_PRODUCT_KAFKA_MAX_BLOCK_MS}" \
  "false"
wait_for_http "${PRODUCT_SERVICE_HEALTH_URL}"
sleep "${PHASE_START_GRACE_SECONDS}"

AFTER_START_TOPIC_COUNT="$(bash loadtest/topic-message-count.sh "${TOPIC}")"

docker compose stop redpanda >/dev/null
run_create_load "${AFTER_PHASE_RUN_ID}" "${AFTER_K6_SUMMARY_PATH}" true "${AFTER_REQUEST_TIMEOUT:-30s}"

AFTER_DB_PRODUCTS_CREATED="$(run_product_count "${AFTER_PHASE_RUN_ID}")"
AFTER_OUTBOX_TOTAL_BEFORE="$(run_outbox_total_count "${AFTER_PHASE_RUN_ID}")"
AFTER_OUTBOX_PENDING_BEFORE="$(run_outbox_status_count "${AFTER_PHASE_RUN_ID}" PENDING)"
AFTER_OUTBOX_SENT_BEFORE="$(run_outbox_status_count "${AFTER_PHASE_RUN_ID}" SENT)"
AFTER_OUTBOX_FAILED_BEFORE="$(run_outbox_status_count "${AFTER_PHASE_RUN_ID}" FAILED)"

docker compose up -d redpanda >/dev/null
wait_for_redpanda

RECOVERY_STARTED_AT_MILLIS="$(millis_now)"

docker compose restart product-service >/dev/null
wait_for_http "${PRODUCT_SERVICE_HEALTH_URL}"

RECOVERY_POLL_ATTEMPTS=0
AFTER_MATCHED_TOPIC_MESSAGES=0
AFTER_MATCHED_UNIQUE_PRODUCTS=0
AFTER_DUPLICATE_TOPIC_MESSAGES=0
AFTER_UNRELATED_TOPIC_MESSAGES=0
AFTER_OUTBOX_TOTAL_AFTER=0
AFTER_OUTBOX_PENDING_AFTER=0
AFTER_OUTBOX_SENT_AFTER=0
AFTER_OUTBOX_FAILED_AFTER=0

while true; do
  RECOVERY_POLL_ATTEMPTS=$((RECOVERY_POLL_ATTEMPTS + 1))

  AFTER_OUTBOX_TOTAL_AFTER="$(run_outbox_total_count "${AFTER_PHASE_RUN_ID}")"
  AFTER_OUTBOX_PENDING_AFTER="$(run_outbox_status_count "${AFTER_PHASE_RUN_ID}" PENDING)"
  AFTER_OUTBOX_SENT_AFTER="$(run_outbox_status_count "${AFTER_PHASE_RUN_ID}" SENT)"
  AFTER_OUTBOX_FAILED_AFTER="$(run_outbox_status_count "${AFTER_PHASE_RUN_ID}" FAILED)"

  while IFS='=' read -r key value; do
    case "${key}" in
      matched_topic_messages)
        AFTER_MATCHED_TOPIC_MESSAGES="${value}"
        ;;
      matched_unique_products)
        AFTER_MATCHED_UNIQUE_PRODUCTS="${value}"
        ;;
      duplicate_topic_messages)
        AFTER_DUPLICATE_TOPIC_MESSAGES="${value}"
        ;;
      unrelated_topic_messages)
        AFTER_UNRELATED_TOPIC_MESSAGES="${value}"
        ;;
    esac
  done < <(collect_topic_stats "${AFTER_PHASE_RUN_ID}" "${AFTER_START_TOPIC_COUNT}")

  if [[ "${AFTER_OUTBOX_PENDING_AFTER}" == "0" ]] \
    && [[ "${AFTER_OUTBOX_FAILED_AFTER}" == "0" ]] \
    && [[ "${AFTER_OUTBOX_SENT_AFTER}" == "${AFTER_DB_PRODUCTS_CREATED}" ]] \
    && [[ "${AFTER_MATCHED_UNIQUE_PRODUCTS}" == "${AFTER_DB_PRODUCTS_CREATED}" ]]; then
    break
  fi

  if (( RECOVERY_POLL_ATTEMPTS * RECOVERY_POLL_INTERVAL_SECONDS >= RECOVERY_POLL_TIMEOUT_SECONDS )); then
    echo "Outbox recovery did not converge in time." >&2
    exit 1
  fi

  sleep "${RECOVERY_POLL_INTERVAL_SECONDS}"
done

RECOVERY_COMPLETED_AT_MILLIS="$(millis_now)"
RECOVERY_DURATION_MILLIS="$((RECOVERY_COMPLETED_AT_MILLIS - RECOVERY_STARTED_AT_MILLIS))"
AFTER_MISSING_PUBLISHED_EVENT="$((AFTER_DB_PRODUCTS_CREATED - AFTER_MATCHED_UNIQUE_PRODUCTS))"

jq -n \
  --arg runId "${RUN_ID}" \
  --arg topic "${TOPIC}" \
  --arg beforePhaseRunId "${BEFORE_PHASE_RUN_ID}" \
  --arg afterPhaseRunId "${AFTER_PHASE_RUN_ID}" \
  --arg profiles "${PRODUCT_SERVICE_PROFILES_ACTIVE}" \
  --arg directAsyncAfterCommitEnabled "${DIRECT_ASYNC_AFTER_COMMIT_ENABLED}" \
  --arg directWarmupEnabled "${DIRECT_WARMUP_ENABLED}" \
  --argjson iterations "${K6_ITERATIONS}" \
  --argjson vus "${K6_VUS}" \
  --argjson stock "${K6_STOCK}" \
  --argjson directKafkaRequestTimeoutMs "${DIRECT_PRODUCT_KAFKA_REQUEST_TIMEOUT_MS}" \
  --argjson directKafkaDeliveryTimeoutMs "${DIRECT_PRODUCT_KAFKA_DELIVERY_TIMEOUT_MS}" \
  --argjson directKafkaMaxBlockMs "${DIRECT_PRODUCT_KAFKA_MAX_BLOCK_MS}" \
  --argjson beforeStartTopicCount "${BEFORE_START_TOPIC_COUNT}" \
  --argjson beforeDbProductsCreated "${BEFORE_DB_PRODUCTS_CREATED}" \
  --argjson beforeMatchedTopicMessages "${BEFORE_MATCHED_TOPIC_MESSAGES}" \
  --argjson beforeMatchedUniqueProducts "${BEFORE_MATCHED_UNIQUE_PRODUCTS}" \
  --argjson beforeDuplicateTopicMessages "${BEFORE_DUPLICATE_TOPIC_MESSAGES}" \
  --argjson beforeUnrelatedTopicMessages "${BEFORE_UNRELATED_TOPIC_MESSAGES}" \
  --argjson beforeMissingPublishedEvent "${BEFORE_MISSING_PUBLISHED_EVENT}" \
  --argjson afterStartTopicCount "${AFTER_START_TOPIC_COUNT}" \
  --argjson afterDbProductsCreated "${AFTER_DB_PRODUCTS_CREATED}" \
  --argjson afterOutboxTotalBefore "${AFTER_OUTBOX_TOTAL_BEFORE}" \
  --argjson afterOutboxPendingBefore "${AFTER_OUTBOX_PENDING_BEFORE}" \
  --argjson afterOutboxSentBefore "${AFTER_OUTBOX_SENT_BEFORE}" \
  --argjson afterOutboxFailedBefore "${AFTER_OUTBOX_FAILED_BEFORE}" \
  --argjson afterOutboxTotalAfter "${AFTER_OUTBOX_TOTAL_AFTER}" \
  --argjson afterOutboxPendingAfter "${AFTER_OUTBOX_PENDING_AFTER}" \
  --argjson afterOutboxSentAfter "${AFTER_OUTBOX_SENT_AFTER}" \
  --argjson afterOutboxFailedAfter "${AFTER_OUTBOX_FAILED_AFTER}" \
  --argjson afterMatchedTopicMessages "${AFTER_MATCHED_TOPIC_MESSAGES}" \
  --argjson afterMatchedUniqueProducts "${AFTER_MATCHED_UNIQUE_PRODUCTS}" \
  --argjson afterDuplicateTopicMessages "${AFTER_DUPLICATE_TOPIC_MESSAGES}" \
  --argjson afterUnrelatedTopicMessages "${AFTER_UNRELATED_TOPIC_MESSAGES}" \
  --argjson afterMissingPublishedEvent "${AFTER_MISSING_PUBLISHED_EVENT}" \
  --argjson recoveryStartedAtMillis "${RECOVERY_STARTED_AT_MILLIS}" \
  --argjson recoveryCompletedAtMillis "${RECOVERY_COMPLETED_AT_MILLIS}" \
  --argjson recoveryDurationMillis "${RECOVERY_DURATION_MILLIS}" \
  --argjson recoveryPollAttempts "${RECOVERY_POLL_ATTEMPTS}" \
  --arg beforeK6SummaryPath "${BEFORE_K6_SUMMARY_PATH}" \
  --arg afterK6SummaryPath "${AFTER_K6_SUMMARY_PATH}" \
  '
  {
    runId: $runId,
    config: {
      topic: $topic,
      productServiceProfilesActive: $profiles,
      iterations: $iterations,
      vus: $vus,
      stock: $stock,
      beforeMode: "direct",
      afterMode: "outbox",
      beforeDirectAsyncAfterCommitEnabled: $directAsyncAfterCommitEnabled,
      beforeDirectWarmupEnabled: $directWarmupEnabled,
      beforeDirectKafkaRequestTimeoutMs: $directKafkaRequestTimeoutMs,
      beforeDirectKafkaDeliveryTimeoutMs: $directKafkaDeliveryTimeoutMs,
      beforeDirectKafkaMaxBlockMs: $directKafkaMaxBlockMs,
      brokerDownDuringCreate: true,
      productServiceRestartBeforeBrokerRecovery: true,
      productServiceRestartDuringRecovery: true
    },
    paths: {
      beforeK6SummaryPath: $beforeK6SummaryPath,
      afterK6SummaryPath: $afterK6SummaryPath
    },
    before: {
      phaseRunId: $beforePhaseRunId,
      startTopicCount: $beforeStartTopicCount,
      dbProductsCreated: $beforeDbProductsCreated,
      matchedTopicMessages: $beforeMatchedTopicMessages,
      matchedUniqueProducts: $beforeMatchedUniqueProducts,
      duplicateTopicMessages: $beforeDuplicateTopicMessages,
      unrelatedTopicMessages: $beforeUnrelatedTopicMessages,
      missingPublishedEvent: $beforeMissingPublishedEvent
    },
    after: {
      phaseRunId: $afterPhaseRunId,
      startTopicCount: $afterStartTopicCount,
      dbProductsCreated: $afterDbProductsCreated,
      beforeRecovery: {
        outboxTotalCount: $afterOutboxTotalBefore,
        pendingCount: $afterOutboxPendingBefore,
        sentCount: $afterOutboxSentBefore,
        failedCount: $afterOutboxFailedBefore
      },
      afterRecovery: {
        outboxTotalCount: $afterOutboxTotalAfter,
        pendingCount: $afterOutboxPendingAfter,
        sentCount: $afterOutboxSentAfter,
        failedCount: $afterOutboxFailedAfter,
        matchedTopicMessages: $afterMatchedTopicMessages,
        matchedUniqueProducts: $afterMatchedUniqueProducts,
        duplicateTopicMessages: $afterDuplicateTopicMessages,
        unrelatedTopicMessages: $afterUnrelatedTopicMessages,
        missingPublishedEvent: $afterMissingPublishedEvent
      },
      recovery: {
        startedAtMillis: $recoveryStartedAtMillis,
        completedAtMillis: $recoveryCompletedAtMillis,
        durationMillis: $recoveryDurationMillis,
        pollAttempts: $recoveryPollAttempts
      }
    },
    validations: {
      beforeLostEventsDetected: ($beforeDbProductsCreated > 0 and $beforeMissingPublishedEvent > 0),
      beforeNoDuplicateTopicMessage: ($beforeDuplicateTopicMessages == 0),
      afterPendingAccumulatedBeforeRecovery: ($afterOutboxPendingBefore == $afterDbProductsCreated and $afterDbProductsCreated > 0),
      afterAllOutboxSentAfterRecovery: ($afterOutboxPendingAfter == 0 and $afterOutboxFailedAfter == 0 and $afterOutboxSentAfter == $afterDbProductsCreated),
      afterNoEventLoss: ($afterMissingPublishedEvent == 0),
      afterNoDuplicateTopicMessage: ($afterDuplicateTopicMessages == 0)
    }
  }
  ' > "${RESULT_PATH}"

cat <<EOF
run_id=${RUN_ID}
result_path=${RESULT_PATH}
before_db_products_created=${BEFORE_DB_PRODUCTS_CREATED}
before_missing_published_event=${BEFORE_MISSING_PUBLISHED_EVENT}
after_db_products_created=${AFTER_DB_PRODUCTS_CREATED}
after_outbox_pending_before=${AFTER_OUTBOX_PENDING_BEFORE}
after_outbox_sent_after=${AFTER_OUTBOX_SENT_AFTER}
after_missing_published_event=${AFTER_MISSING_PUBLISHED_EVENT}
recovery_duration_ms=${RECOVERY_DURATION_MILLIS}
EOF
