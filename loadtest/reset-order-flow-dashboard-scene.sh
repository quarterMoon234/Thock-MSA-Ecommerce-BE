#!/usr/bin/env bash
set -euo pipefail

echo "[1/4] stop app + log pipeline containers"
docker compose stop \
  api-gateway \
  member-service \
  product-service \
  market-service \
  payment-service \
  settlement-service \
  promtail \
  loki \
  grafana >/dev/null

echo "[2/4] remove existing application logs"
find logs -type f ! -name '.gitkeep' -delete

echo "[3/4] recreate loki/promtail/grafana to reset log indexes and positions"
docker compose rm -sf loki promtail grafana >/dev/null

echo "[4/4] start required containers"
docker compose up -d \
  mysql redpanda \
  member-service product-service market-service payment-service settlement-service api-gateway \
  loki promtail grafana >/dev/null

echo
echo "dashboard scene reset completed"
echo "recommended next steps:"
echo "  1. Grafana time range -> Last 15 minutes"
echo "  2. auto refresh -> 5s"
echo "  3. default candidates: PRODUCT_ID_CANDIDATES=4,5,6"
echo "     example: PRODUCT_ID=4 bash loadtest/run-order-flow-dashboard-portfolio-scene.sh"
echo "     or: PRODUCT_ID=4 bash loadtest/run-order-flow-dashboard-scene.sh"
