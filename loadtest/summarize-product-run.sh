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

RUN_ID="${1:-}"
START_TOPIC_COUNT="${2:-0}"
TOPIC="${TOPIC:-product.changed}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"

if [[ -z "${RUN_ID}" ]]; then
  echo "Usage: bash loadtest/summarize-product-run.sh <run_id> [start_topic_count]" >&2
  exit 1
fi

END_TOPIC_COUNT="$(bash loadtest/topic-message-count.sh "${TOPIC}")"

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
done < <(bash loadtest/run-scoped-topic-stats.sh "${RUN_ID}" "${START_TOPIC_COUNT}" "${TOPIC}")

DB_PRODUCTS_CREATED="$(
  docker compose exec -T mysql mysql -uroot "-p${MYSQL_ROOT_PASSWORD}" -Nse \
  "SELECT COUNT(*) FROM thock_product_db.products
   WHERE name LIKE 'k6-${RUN_ID}-%';"
)"

TOPIC_NEW_MESSAGES=$((END_TOPIC_COUNT - START_TOPIC_COUNT))
MISSING_PUBLISHED_EVENT=$((DB_PRODUCTS_CREATED - MATCHED_UNIQUE_PRODUCTS))

printf '%-22s %s\n' "run_id" "${RUN_ID}"
printf '%-22s %s\n' "topic" "${TOPIC}"
printf '%-22s %s\n' "start_topic_count" "${START_TOPIC_COUNT}"
printf '%-22s %s\n' "end_topic_count" "${END_TOPIC_COUNT}"
printf '%-22s %s\n' "topic_new_messages" "${TOPIC_NEW_MESSAGES}"
printf '%-22s %s\n' "matched_topic_messages" "${MATCHED_TOPIC_MESSAGES}"
printf '%-22s %s\n' "matched_unique_products" "${MATCHED_UNIQUE_PRODUCTS}"
printf '%-22s %s\n' "duplicate_topic_messages" "${DUPLICATE_TOPIC_MESSAGES}"
printf '%-22s %s\n' "unrelated_topic_messages" "${UNRELATED_TOPIC_MESSAGES}"
printf '%-22s %s\n' "db_products_created" "${DB_PRODUCTS_CREATED}"
printf '%-22s %s\n' "missing_published_event" "${MISSING_PUBLISHED_EVENT}"
