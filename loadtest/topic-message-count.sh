#!/usr/bin/env bash
set -euo pipefail

TOPIC="${1:-product.changed}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-10}"
SLEEP_SECONDS="${SLEEP_SECONDS:-1}"

ensure_topic_exists() {
  if docker compose exec -T redpanda rpk topic describe "${TOPIC}" >/dev/null 2>&1; then
    return 0
  fi

  docker compose exec -T redpanda rpk topic create "${TOPIC}" -p 1 -r 1 >/dev/null 2>&1 || true
}

count_topic_messages() {
  docker compose --profile loadtest run --rm -T kcat \
    -b redpanda:29092 \
    -C \
    -t "${TOPIC}" \
    -o beginning \
    -e \
    -q \
    -f '%o\n' 2>/dev/null | wc -l | tr -d ' '
}

ensure_topic_exists

attempt=1
while (( attempt <= MAX_ATTEMPTS )); do
  if docker compose exec -T redpanda rpk topic describe "${TOPIC}" >/dev/null 2>&1; then
    count_topic_messages
    exit 0
  fi

  sleep "${SLEEP_SECONDS}"
  attempt=$((attempt + 1))
done

echo "0"
