import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const reservationsAccepted = new Counter('stock_reservation_accepted_total');
const reservationsRejected = new Counter('stock_reservation_rejected_total');
const reservationFailures = new Counter('stock_reservation_failures_total');
const reservationAttempts = new Counter('stock_reservation_attempts_total');
const reservationDuration = new Trend('stock_reservation_duration', true);

const experimentRunId = __ENV.RUN_ID || `${Date.now()}`;
const baseUrl = (__ENV.PRODUCT_SERVICE_BASE_URL || 'http://product-service:8082').replace(/\/$/, '');
const initialStock = parsePositiveInteger(__ENV.INITIAL_STOCK, 10);
const quantity = parsePositiveInteger(__ENV.QUANTITY, 1);
const executor = (__ENV.STOCK_EXECUTOR || __ENV.STOCK_EXPERIMENT_EXECUTOR || 'per-vu').toLowerCase();
const attempts = parsePositiveInteger(__ENV.ATTEMPTS || __ENV.ITERATIONS, 500);
const vus = parsePositiveInteger(__ENV.VUS, Math.min(attempts, 200));

export const options = {
  scenarios: {
    stock_pessimistic_baseline: buildScenario(),
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    stock_reservation_duration: ['p(95)<5000'],
  },
};

export function setup() {
  if (executor !== 'per-vu' && executor !== 'shared') {
    fail('EXECUTOR must be one of per-vu, shared.');
  }

  reservationsAccepted.add(0);
  reservationsRejected.add(0);
  reservationFailures.add(0);
  reservationAttempts.add(0);

  const response = http.post(
    `${baseUrl}/api/v1/experiments/stock/products`,
    JSON.stringify({
      sellerId: Number(__ENV.SELLER_ID || 1),
      name: `stock-baseline-${experimentRunId}-${attempts}`,
      price: Number(__ENV.PRICE || 10000),
      salePrice: Number(__ENV.SALE_PRICE || 9000),
      category: __ENV.CATEGORY || 'KEYBOARD',
      stock: initialStock,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: {
        name: 'stock_experiment_create_product',
        experiment: __ENV.EXPERIMENT_NAME || 'stock-pessimistic-baseline',
      },
    }
  );

  if (response.status !== 201) {
    fail(`failed to create stock experiment product: status=${response.status} body=${response.body}`);
  }

  const payload = response.json();
  if (!payload || !payload.productId) {
    fail(`create product response does not contain productId: body=${response.body}`);
  }

  console.log(
    `stock_experiment_product_created product_id=${payload.productId} initial_stock=${initialStock} executor=${executor} attempts=${attempts} vus=${resolveScenarioVus()}`
  );

  return {
    productId: payload.productId,
  };
}

export default function (data) {
  const iteration = exec.scenario.iterationInTest;
  const orderNumber = `${experimentRunId}-stock-${attempts}-${iteration}`;

  const response = http.post(
    `${baseUrl}/api/v1/experiments/stock/reservations`,
    JSON.stringify({
      orderNumber,
      productId: data.productId,
      quantity,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: {
        name: 'stock_experiment_reserve',
        experiment: __ENV.EXPERIMENT_NAME || 'stock-pessimistic-baseline',
        attempts: `${attempts}`,
      },
    }
  );

  reservationAttempts.add(1);
  reservationDuration.add(response.timings.duration);

  const payload = parseJson(response);
  const passed = check(response, {
    'reservation status is 200': (res) => res.status === 200,
    'reservation outcome is valid': () =>
      payload && (payload.outcome === 'RESERVED' || payload.outcome === 'REJECTED'),
  });

  if (!passed) {
    reservationFailures.add(1);
    return;
  }

  if (payload.outcome === 'RESERVED') {
    reservationsAccepted.add(1);
  } else if (payload.outcome === 'REJECTED') {
    reservationsRejected.add(1);
  }
}

export function teardown(data) {
  const response = http.get(`${baseUrl}/api/v1/experiments/stock/products/${data.productId}`, {
    tags: {
      name: 'stock_experiment_get_product',
      experiment: __ENV.EXPERIMENT_NAME || 'stock-pessimistic-baseline',
    },
  });

  if (response.status === 200) {
    const payload = response.json();
    console.log(
      `stock_experiment_final product_id=${payload.productId} stock=${payload.stock} reserved_stock=${payload.reservedStock} available_stock=${payload.availableStock}`
    );
  } else {
    console.warn(`failed to fetch final product state: status=${response.status} body=${response.body}`);
  }
}

export function handleSummary(data) {
  const summaryPath =
    __ENV.SUMMARY_PATH || `/results/stock-pessimistic-baseline-${attempts}-${experimentRunId}.json`;

  return {
    stdout: [
      '',
      `experiment=${__ENV.EXPERIMENT_NAME || 'stock-pessimistic-baseline'}`,
      `run_id=${experimentRunId}`,
      `executor=${executor}`,
      `initial_stock=${initialStock}`,
      `quantity=${quantity}`,
      `attempts=${attempts}`,
      `vus=${resolveScenarioVus()}`,
      `http_req_failed_rate=${formatMetricValue(data.metrics.http_req_failed, 'rate')}`,
      `http_reqs_count=${formatMetricValue(data.metrics.http_reqs, 'count')}`,
      `http_req_duration_avg=${formatMetricValue(data.metrics.http_req_duration, 'avg')}ms`,
      `http_req_duration_p95=${formatMetricValue(data.metrics.http_req_duration, 'p(95)')}ms`,
      `stock_reservation_attempts_total=${formatMetricValue(data.metrics.stock_reservation_attempts_total, 'count')}`,
      `stock_reservation_accepted_total=${formatMetricValue(data.metrics.stock_reservation_accepted_total, 'count')}`,
      `stock_reservation_rejected_total=${formatMetricValue(data.metrics.stock_reservation_rejected_total, 'count')}`,
      `stock_reservation_failures_total=${formatMetricValue(data.metrics.stock_reservation_failures_total, 'count')}`,
      `stock_reservation_duration_avg=${formatMetricValue(data.metrics.stock_reservation_duration, 'avg')}ms`,
      `stock_reservation_duration_p95=${formatMetricValue(data.metrics.stock_reservation_duration, 'p(95)')}ms`,
      '',
    ].join('\n'),
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}

function buildScenario() {
  if (executor === 'per-vu') {
    return {
      executor: 'per-vu-iterations',
      vus: attempts,
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || '5m',
      gracefulStop: '10s',
    };
  }

  return {
    executor: 'shared-iterations',
    vus,
    iterations: attempts,
    maxDuration: __ENV.MAX_DURATION || '5m',
    gracefulStop: '10s',
  };
}

function resolveScenarioVus() {
  return executor === 'per-vu' ? attempts : vus;
}

function parseJson(response) {
  if (!response) {
    return null;
  }

  try {
    return response.json();
  } catch (error) {
    return null;
  }
}

function parsePositiveInteger(rawValue, defaultValue) {
  const parsed = Number(rawValue || defaultValue);

  if (!Number.isFinite(parsed) || parsed <= 0) {
    return defaultValue;
  }

  return Math.floor(parsed);
}

function formatMetricValue(metric, key) {
  if (!metric || !metric.values || metric.values[key] === undefined) {
    return '0';
  }

  return Number(metric.values[key]).toFixed(2);
}
