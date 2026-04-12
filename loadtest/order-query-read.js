import http from 'k6/http';
import { check, fail } from 'k6';

const experimentName = __ENV.EXPERIMENT_NAME || 'order-query-read';
const summaryPath = __ENV.SUMMARY_PATH || `/results/${experimentName}.json`;
const mode = (__ENV.ORDER_QUERY_MODE || 'optimized').toLowerCase();
const baseUrl = (__ENV.MARKET_SERVICE_BASE_URL || 'http://market-service:8083').replace(/\/$/, '');
const memberId = parsePositiveInteger(__ENV.ORDER_QUERY_MEMBER_ID, 999001);
const orderCount = parsePositiveInteger(__ENV.ORDER_QUERY_ORDER_COUNT, 100);
const itemsPerOrder = parsePositiveInteger(__ENV.ORDER_QUERY_ITEMS_PER_ORDER, 5);
const iterations = parsePositiveInteger(__ENV.ITERATIONS, 300);
const vus = parsePositiveInteger(__ENV.VUS, 20);

export const options = {
  scenarios: {
    order_query_read: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '3m',
    },
  },
};

export function setup() {
  resetDataset();

  const seedResponse = http.post(
    `${baseUrl}/api/v1/experiments/order-query/dataset/seed`,
    JSON.stringify({
      memberId,
      orderCount,
      itemsPerOrder,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: {
        name: 'order_query_seed',
      },
    }
  );

  if (!check(seedResponse, {
    'seed status is 200': (response) => response.status === 200,
  })) {
    fail(`seed failed: status=${seedResponse.status} body=${seedResponse.body}`);
  }

  const dataset = seedResponse.json();

  if (!dataset || dataset.orderCount !== orderCount) {
    fail(`unexpected dataset response: ${seedResponse.body}`);
  }

  return {
    baseUrl,
    mode,
    memberId: dataset.memberId,
    expectedOrderCount: dataset.orderCount,
  };
}

export default function (data) {
  const response = http.get(
    `${data.baseUrl}/api/v1/experiments/order-query/${data.mode}/${data.memberId}`,
    {
      tags: {
        name: `order_query_${data.mode}`,
      },
    }
  );

  let payloadLength = -1;
  try {
    payloadLength = response.json().length;
  } catch (error) {
    payloadLength = -1;
  }

  check(response, {
    'status is 200': (res) => res.status === 200,
    'order count matches dataset': () => payloadLength === data.expectedOrderCount,
  });
}

export function handleSummary(data) {
  return {
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}

function resetDataset() {
  const resetResponse = http.post(`${baseUrl}/api/v1/experiments/order-query/dataset/reset?memberId=${memberId}`, null, {
    tags: {
      name: 'order_query_reset',
    },
  });

  if (!check(resetResponse, {
    'reset status is 200': (response) => response.status === 200,
  })) {
    fail(`reset failed: status=${resetResponse.status} body=${resetResponse.body}`);
  }
}

function parsePositiveInteger(rawValue, defaultValue) {
  const parsed = Number(rawValue || defaultValue);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return defaultValue;
  }

  return Math.floor(parsed);
}
