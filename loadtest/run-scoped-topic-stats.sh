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
TOPIC="${3:-${TOPIC:-product.changed}}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"

if [[ -z "${RUN_ID}" ]]; then
  echo "Usage: bash loadtest/run-scoped-topic-stats.sh <run_id> [start_topic_count] [topic]" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
PRODUCT_IDS_FILE="${TMP_DIR}/product_ids.txt"
TOPIC_KEYS_FILE="${TMP_DIR}/topic_keys.txt"

cleanup() {
  rm -rf "${TMP_DIR}"
}

trap cleanup EXIT

docker compose exec -T mysql mysql -uroot "-p${MYSQL_ROOT_PASSWORD}" -Nse \
  "SELECT id
   FROM thock_product_db.products
   WHERE name LIKE 'k6-${RUN_ID}-%'
   ORDER BY id;" > "${PRODUCT_IDS_FILE}"

if [[ ! -s "${PRODUCT_IDS_FILE}" ]]; then
  cat <<EOF
matched_topic_messages=0
matched_unique_products=0
duplicate_topic_messages=0
unrelated_topic_messages=0
EOF
  exit 0
fi

# Ensure the topic exists before attempting an offset-based read.
bash loadtest/topic-message-count.sh "${TOPIC}" >/dev/null

docker compose --profile loadtest run --rm -T kcat \
  -b redpanda:29092 \
  -C \
  -t "${TOPIC}" \
  -o "${START_TOPIC_COUNT}" \
  -e \
  -q \
  -f '%k\n' 2>/dev/null > "${TOPIC_KEYS_FILE}" || true

IFS=' ' read -r RUN_SCOPED_TOPIC_MESSAGES RUN_SCOPED_UNIQUE_PRODUCT_KEYS RUN_SCOPED_DUPLICATE_MESSAGES TOPIC_TOTAL_DELTA <<EOF
$(awk '
  NR == FNR {
    runProductIds[$1] = 1
    next
  }

  {
    topicTotalDelta++
    if ($0 in runProductIds) {
      runScopedTopicMessages++
      seen[$0]++
    }
  }

  END {
    uniqueKeys = 0
    duplicateMessages = 0
    for (key in seen) {
      uniqueKeys++
      duplicateMessages += seen[key] - 1
    }

    printf "%d %d %d %d\n",
      runScopedTopicMessages + 0,
      uniqueKeys + 0,
      duplicateMessages + 0,
      topicTotalDelta + 0
  }
' "${PRODUCT_IDS_FILE}" "${TOPIC_KEYS_FILE}")
EOF

cat <<EOF
matched_topic_messages=${RUN_SCOPED_TOPIC_MESSAGES}
matched_unique_products=${RUN_SCOPED_UNIQUE_PRODUCT_KEYS}
duplicate_topic_messages=${RUN_SCOPED_DUPLICATE_MESSAGES}
unrelated_topic_messages=$((TOPIC_TOTAL_DELTA - RUN_SCOPED_TOPIC_MESSAGES))
EOF
