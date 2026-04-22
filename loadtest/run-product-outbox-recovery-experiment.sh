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
PRODUCT_EVENT_PUBLISH_MODE="${PRODUCT_EVENT_PUBLISH_MODE:-outbox}"
PRODUCT_OUTBOX_POLLER_INTERVAL_MS="${PRODUCT_OUTBOX_POLLER_INTERVAL_MS:-1000}"

K6_ITERATIONS="${K6_ITERATIONS:-2400}"
K6_VUS="${K6_VUS:-50}"
K6_STOCK="${K6_STOCK:-5}"
K6_SLEEP_BETWEEN="${K6_SLEEP_BETWEEN:-0}"
K6_SUMMARY_PATH="/results/product-outbox-recovery-${RUN_ID}-k6.json"

RECOVERY_POLL_INTERVAL_SECONDS="${RECOVERY_POLL_INTERVAL_SECONDS:-1}"
RECOVERY_POLL_TIMEOUT_SECONDS="${RECOVERY_POLL_TIMEOUT_SECONDS:-300}"

RESULT_PATH="loadtest/results/product-outbox-recovery-${RUN_ID}.json"

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
  db_scalar "
    SELECT COUNT(*)
    FROM thock_product_db.products
    WHERE name LIKE 'k6-${RUN_ID}-%';
  "
}

run_outbox_total_count() {
  db_scalar "
    SELECT COUNT(*)
    FROM thock_product_db.product_outbox_event e
    JOIN thock_product_db.products p
      ON CAST(e.event_key AS UNSIGNED) = p.id
    WHERE p.name LIKE 'k6-${RUN_ID}-%';
  "
}

run_outbox_status_count() {
  local status="$1"
  db_scalar "
    SELECT COUNT(*)
    FROM thock_product_db.product_outbox_event e
    JOIN thock_product_db.products p
      ON CAST(e.event_key AS UNSIGNED) = p.id
    WHERE p.name LIKE 'k6-${RUN_ID}-%'
      AND e.status = '${status}';
  "
}

collect_topic_stats() {
  bash loadtest/run-scoped-topic-stats.sh "${RUN_ID}" "${START_TOPIC_COUNT}" "${TOPIC}"
}

millis_now() {
  echo $(( $(date +%s) * 1000 ))
}

docker compose up -d mysql redpanda member-service api-gateway

PRODUCT_SERVICE_PROFILES_ACTIVE="${PRODUCT_SERVICE_PROFILES_ACTIVE}" \
PRODUCT_EVENT_PUBLISH_MODE="${PRODUCT_EVENT_PUBLISH_MODE}" \
PRODUCT_OUTBOX_POLLER_INTERVAL_MS="${PRODUCT_OUTBOX_POLLER_INTERVAL_MS}" \
docker compose up -d --build product-service

wait_for_redpanda
wait_for_http "${GATEWAY_HEALTH_URL}"
wait_for_http "${PRODUCT_SERVICE_HEALTH_URL}"

START_TOPIC_COUNT="$(bash loadtest/topic-message-count.sh "${TOPIC}")"

docker compose stop redpanda >/dev/null

docker compose --profile loadtest run --no-deps --rm \
  -e RUN_ID="${RUN_ID}" \
  -e EXPERIMENT_NAME="product-outbox-recovery" \
  -e SUMMARY_PATH="${K6_SUMMARY_PATH}" \
  -e BASE_URL="${BASE_URL}" \
  -e PASSWORD="${K6_PASSWORD:-password123!}" \
  -e K6_ITERATIONS="${K6_ITERATIONS}" \
  -e K6_VUS="${K6_VUS}" \
  -e CATEGORY="${K6_CATEGORY:-KEYBOARD}" \
  -e PRICE="${K6_PRICE:-1000}" \
  -e SALE_PRICE="${K6_SALE_PRICE:-0}" \
  -e STOCK="${K6_STOCK}" \
  -e BANK_CODE="${K6_BANK_CODE:-088}" \
  -e ACCOUNT_NUMBER="${K6_ACCOUNT_NUMBER:-1234567890}" \
  -e ACCOUNT_HOLDER="${K6_ACCOUNT_HOLDER:-k6-seller}" \
  -e SLEEP_BETWEEN="${K6_SLEEP_BETWEEN}" \
  k6 run /scripts/product-outbox-recovery.js

DB_PRODUCTS_CREATED="$(run_product_count)"
OUTBOX_TOTAL_BEFORE="$(run_outbox_total_count)"
OUTBOX_PENDING_BEFORE="$(run_outbox_status_count PENDING)"
OUTBOX_SENT_BEFORE="$(run_outbox_status_count SENT)"
OUTBOX_FAILED_BEFORE="$(run_outbox_status_count FAILED)"

docker compose up -d redpanda >/dev/null
wait_for_redpanda

RECOVERY_STARTED_AT_MILLIS="$(millis_now)"

docker compose restart product-service >/dev/null
wait_for_http "${PRODUCT_SERVICE_HEALTH_URL}"

RECOVERY_POLL_ATTEMPTS=0
MATCHED_TOPIC_MESSAGES=0
MATCHED_UNIQUE_PRODUCTS=0
DUPLICATE_TOPIC_MESSAGES=0
UNRELATED_TOPIC_MESSAGES=0
OUTBOX_TOTAL_AFTER=0
OUTBOX_PENDING_AFTER=0
OUTBOX_SENT_AFTER=0
OUTBOX_FAILED_AFTER=0

while true; do
  RECOVERY_POLL_ATTEMPTS=$((RECOVERY_POLL_ATTEMPTS + 1))

  OUTBOX_TOTAL_AFTER="$(run_outbox_total_count)"
  OUTBOX_PENDING_AFTER="$(run_outbox_status_count PENDING)"
  OUTBOX_SENT_AFTER="$(run_outbox_status_count SENT)"
  OUTBOX_FAILED_AFTER="$(run_outbox_status_count FAILED)"

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
  done < <(collect_topic_stats)

  if [[ "${OUTBOX_PENDING_AFTER}" == "0" ]] \
    && [[ "${OUTBOX_FAILED_AFTER}" == "0" ]] \
    && [[ "${OUTBOX_SENT_AFTER}" == "${DB_PRODUCTS_CREATED}" ]] \
    && [[ "${MATCHED_UNIQUE_PRODUCTS}" == "${DB_PRODUCTS_CREATED}" ]]; then
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
MISSING_PUBLISHED_EVENT="$((DB_PRODUCTS_CREATED - MATCHED_UNIQUE_PRODUCTS))"

jq -n \
  --arg runId "${RUN_ID}" \
  --arg topic "${TOPIC}" \
  --argjson startTopicCount "${START_TOPIC_COUNT}" \
  --arg profiles "${PRODUCT_SERVICE_PROFILES_ACTIVE}" \
  --arg publishMode "${PRODUCT_EVENT_PUBLISH_MODE}" \
  --argjson pollerIntervalMs "${PRODUCT_OUTBOX_POLLER_INTERVAL_MS}" \
  --argjson iterations "${K6_ITERATIONS}" \
  --argjson vus "${K6_VUS}" \
  --argjson stock "${K6_STOCK}" \
  --arg k6SummaryPath "${K6_SUMMARY_PATH}" \
  --argjson dbProductsCreated "${DB_PRODUCTS_CREATED}" \
  --argjson outboxTotalBefore "${OUTBOX_TOTAL_BEFORE}" \
  --argjson outboxPendingBefore "${OUTBOX_PENDING_BEFORE}" \
  --argjson outboxSentBefore "${OUTBOX_SENT_BEFORE}" \
  --argjson outboxFailedBefore "${OUTBOX_FAILED_BEFORE}" \
  --argjson outboxTotalAfter "${OUTBOX_TOTAL_AFTER}" \
  --argjson outboxPendingAfter "${OUTBOX_PENDING_AFTER}" \
  --argjson outboxSentAfter "${OUTBOX_SENT_AFTER}" \
  --argjson outboxFailedAfter "${OUTBOX_FAILED_AFTER}" \
  --argjson matchedTopicMessages "${MATCHED_TOPIC_MESSAGES}" \
  --argjson matchedUniqueProducts "${MATCHED_UNIQUE_PRODUCTS}" \
  --argjson duplicateTopicMessages "${DUPLICATE_TOPIC_MESSAGES}" \
  --argjson unrelatedTopicMessages "${UNRELATED_TOPIC_MESSAGES}" \
  --argjson missingPublishedEvent "${MISSING_PUBLISHED_EVENT}" \
  --argjson recoveryStartedAtMillis "${RECOVERY_STARTED_AT_MILLIS}" \
  --argjson recoveryCompletedAtMillis "${RECOVERY_COMPLETED_AT_MILLIS}" \
  --argjson recoveryDurationMillis "${RECOVERY_DURATION_MILLIS}" \
  --argjson recoveryPollAttempts "${RECOVERY_POLL_ATTEMPTS}" \
  '
  {
    runId: $runId,
    config: {
      topic: $topic,
      productServiceProfilesActive: $profiles,
      productEventPublishMode: $publishMode,
      productOutboxPollerIntervalMs: $pollerIntervalMs,
      iterations: $iterations,
      vus: $vus,
      stock: $stock,
      brokerDownDuringCreate: true,
      productServiceRestartDuringRecovery: true
    },
    paths: {
      k6SummaryPath: $k6SummaryPath
    },
    beforeRecovery: {
      dbProductsCreated: $dbProductsCreated,
      outboxTotalCount: $outboxTotalBefore,
      pendingCount: $outboxPendingBefore,
      sentCount: $outboxSentBefore,
      failedCount: $outboxFailedBefore,
      startTopicCount: $startTopicCount
    },
    afterRecovery: {
      outboxTotalCount: $outboxTotalAfter,
      pendingCount: $outboxPendingAfter,
      sentCount: $outboxSentAfter,
      failedCount: $outboxFailedAfter,
      matchedTopicMessages: $matchedTopicMessages,
      matchedUniqueProducts: $matchedUniqueProducts,
      duplicateTopicMessages: $duplicateTopicMessages,
      unrelatedTopicMessages: $unrelatedTopicMessages,
      missingPublishedEvent: $missingPublishedEvent
    },
    recovery: {
      startedAtMillis: $recoveryStartedAtMillis,
      completedAtMillis: $recoveryCompletedAtMillis,
      durationMillis: $recoveryDurationMillis,
      pollAttempts: $recoveryPollAttempts
    },
    validations: {
      allCreatesPersisted: ($dbProductsCreated == $iterations),
      pendingAccumulatedBeforeRecovery: ($outboxPendingBefore == $dbProductsCreated and $dbProductsCreated > 0),
      allOutboxSentAfterRecovery: ($outboxPendingAfter == 0 and $outboxFailedAfter == 0 and $outboxSentAfter == $dbProductsCreated),
      noEventLoss: ($missingPublishedEvent == 0),
      noDuplicateTopicMessage: ($duplicateTopicMessages == 0)
    }
  }
  ' > "${RESULT_PATH}"

cat <<EOF
run_id=${RUN_ID}
result_path=${RESULT_PATH}
db_products_created=${DB_PRODUCTS_CREATED}
outbox_pending_before=${OUTBOX_PENDING_BEFORE}
outbox_sent_after=${OUTBOX_SENT_AFTER}
matched_unique_products=${MATCHED_UNIQUE_PRODUCTS}
missing_published_event=${MISSING_PUBLISHED_EVENT}
recovery_duration_ms=${RECOVERY_DURATION_MILLIS}
EOF
