import http from 'k6/http';
import { check, fail } from 'k6';

const runId = __ENV.RUN_ID || `${Date.now()}`;
const phase = __ENV.PHASE || 'before';
const productServiceBaseUrl = (__ENV.PRODUCT_SERVICE_BASE_URL || 'http://product-service:8082').replace(/\/$/, '');
const marketServiceBaseUrl = (__ENV.MARKET_SERVICE_BASE_URL || 'http://market-service:8083').replace(/\/$/, '');
const productCount = Number(__ENV.PRODUCT_COUNT || 3);
const outputPath = __ENV.OUTPUT_PATH || `/results/circuit-breaker-product-ids-${runId}-${phase}.json`;

export const options = {
  scenarios: {
    prepare_products: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '1m',
      gracefulStop: '5s',
    },
  },
};

export function setup() {
  const productIds = [];

  for (let index = 0; index < productCount; index += 1) {
    const response = http.post(
      `${productServiceBaseUrl}/api/v1/experiments/stock/products`,
      JSON.stringify({
        name: `cb-${runId}-${phase}-${index + 1}`,
        price: 10000,
        salePrice: 9000,
        stock: 1000,
      }),
      {
        headers: {
          'Content-Type': 'application/json',
        },
        tags: {
          name: 'circuit_breaker_prepare_product',
        },
      }
    );

    check(response, {
      'prepare product status is 201': (res) => res.status === 201,
    }) || fail(`failed to create experiment product: status=${response.status} body=${response.body}`);

    productIds.push(response.json().productId);
  }

  const syncResponse = http.post(
    `${marketServiceBaseUrl}/api/v1/experiments/cart-cqrs/product-views/sync`,
    JSON.stringify({ productIds }),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: {
        name: 'circuit_breaker_sync_product_views',
      },
    }
  );

  check(syncResponse, {
    'sync product views status is 204': (res) => res.status === 204,
  }) || fail(`failed to sync cart product views: status=${syncResponse.status} body=${syncResponse.body}`);

  return {
    runId,
    phase,
    productIds,
  };
}

export default function () {}

export function handleSummary(data) {
  return {
    [outputPath]: JSON.stringify(data.setup_data, null, 2),
  };
}
