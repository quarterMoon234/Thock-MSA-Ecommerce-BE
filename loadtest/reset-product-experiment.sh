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

TOPIC="${TOPIC:-product.changed}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"
WAIT_MAX_ATTEMPTS="${WAIT_MAX_ATTEMPTS:-30}"
WAIT_SLEEP_SECONDS="${WAIT_SLEEP_SECONDS:-2}"
RESET_PROMETHEUS_DATA="${RESET_PROMETHEUS_DATA:-false}"

CORE_SERVICES=(mysql redpanda)
QUIESCE_SERVICES=(api-gateway product-service)
START_SERVICES=(
  member-service
  payment-service
  market-service
  settlement-service
  product-service
  api-gateway
  prometheus
  grafana
)

wait_for_mysql() {
  local attempt=1
  while (( attempt <= WAIT_MAX_ATTEMPTS )); do
    if docker compose exec -T mysql mysqladmin ping -uroot "-p${MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1; then
      return 0
    fi
    echo "mysql_ready=false attempt=${attempt}"
    sleep "${WAIT_SLEEP_SECONDS}"
    attempt=$((attempt + 1))
  done

  echo "MySQL did not become ready." >&2
  return 1
}

wait_for_redpanda() {
  local attempt=1
  while (( attempt <= WAIT_MAX_ATTEMPTS )); do
    if docker compose exec -T redpanda rpk cluster info >/dev/null 2>&1; then
      return 0
    fi
    echo "redpanda_ready=false attempt=${attempt}"
    sleep "${WAIT_SLEEP_SECONDS}"
    attempt=$((attempt + 1))
  done

  echo "Redpanda did not become ready." >&2
  return 1
}

wait_until_topic_absent() {
  local attempt=1
  while (( attempt <= WAIT_MAX_ATTEMPTS )); do
    if ! docker compose exec -T redpanda rpk topic describe "${TOPIC}" >/dev/null 2>&1; then
      return 0
    fi
    echo "topic_delete_pending=true attempt=${attempt} topic=${TOPIC}"
    sleep "${WAIT_SLEEP_SECONDS}"
    attempt=$((attempt + 1))
  done

  echo "Topic still exists after delete attempts: ${TOPIC}" >&2
  return 1
}

echo "step=core_up services=${CORE_SERVICES[*]}"
docker compose up -d "${CORE_SERVICES[@]}"

wait_for_mysql
echo "mysql_ready=true"

wait_for_redpanda
echo "redpanda_ready=true"

echo "step=stop_app services=${QUIESCE_SERVICES[*]}"
docker compose stop "${QUIESCE_SERVICES[@]}"

echo "step=delete_topic topic=${TOPIC}"
docker compose exec -T redpanda rpk topic delete "${TOPIC}" >/dev/null 2>&1 || true
wait_until_topic_absent
echo "topic_deleted=true"

echo "step=truncate_product_tables"
docker compose exec -T mysql mysql -uroot "-p${MYSQL_ROOT_PASSWORD}" -e "
SET FOREIGN_KEY_CHECKS=0;
TRUNCATE TABLE thock_product_db.product_outbox_event;
TRUNCATE TABLE thock_product_db.product_inbox_event;
TRUNCATE TABLE thock_product_db.products;
SET FOREIGN_KEY_CHECKS=1;
"

if [[ "${RESET_PROMETHEUS_DATA}" == "true" ]]; then
  echo "step=reset_prometheus_data"
  docker compose stop prometheus
  docker compose rm -f prometheus
  docker volume rm beadv4_4_refactoring_be_prometheus-data >/dev/null 2>&1 || true
fi

echo "step=start_stack services=${START_SERVICES[*]}"
docker compose up -d "${START_SERVICES[@]}"

TOPIC_COUNT="$(bash loadtest/topic-message-count.sh "${TOPIC}")"

IFS=$'\t' read -r PRODUCT_COUNT OUTBOX_COUNT INBOX_COUNT <<EOF
$(docker compose exec -T mysql mysql -uroot "-p${MYSQL_ROOT_PASSWORD}" -Nse "
SELECT
  (SELECT COUNT(*) FROM thock_product_db.products),
  (SELECT COUNT(*) FROM thock_product_db.product_outbox_event),
  (SELECT COUNT(*) FROM thock_product_db.product_inbox_event);
")
EOF

printf '%-22s %s\n' "topic" "${TOPIC}"
printf '%-22s %s\n' "topic_message_count" "${TOPIC_COUNT}"
printf '%-22s %s\n' "products_count" "${PRODUCT_COUNT}"
printf '%-22s %s\n' "outbox_count" "${OUTBOX_COUNT}"
printf '%-22s %s\n' "inbox_count" "${INBOX_COUNT}"

if [[ "${TOPIC_COUNT}" != "0" || "${PRODUCT_COUNT}" != "0" || "${OUTBOX_COUNT}" != "0" || "${INBOX_COUNT}" != "0" ]]; then
  echo "reset_status=failed" >&2
  exit 1
fi

echo "reset_status=ok"
