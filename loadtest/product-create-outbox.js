import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const productsCreated = new Counter('products_created_total');
const productCreateFailures = new Counter('product_create_failures_total');
const experimentRunId = __ENV.RUN_ID || `${Date.now()}`;

const rate = Number(__ENV.RATE || 10);
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || 20);
const maxVUs = Number(__ENV.MAX_VUS || 100);
const sleepBetween = Number(__ENV.SLEEP_BETWEEN || 0);

export const options = {
  scenarios: {
    product_create_load: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs,
      maxVUs,
      gracefulStop: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  const baseUrl = (__ENV.BASE_URL || 'http://api-gateway:8080').replace(/\/$/, '');
  const password = __ENV.PASSWORD || 'password123!';
  const runId = experimentRunId;

  if (__ENV.SELLER_ACCESS_TOKEN) {
    return {
      accessToken: __ENV.SELLER_ACCESS_TOKEN,
      baseUrl,
      runId,
    };
  }

  const sellerEmail = `seller-k6-${runId}@example.com`;
  const sellerName = `seller-k6-${runId}`;

  signUp(baseUrl, sellerEmail, sellerName, password);
  sleep(0.5);

  const memberToken = login(baseUrl, sellerEmail, password);
  promoteSeller(baseUrl, memberToken, sellerName);
  sleep(0.5);

  const sellerToken = login(baseUrl, sellerEmail, password);

  return {
    accessToken: sellerToken,
    baseUrl,
    runId,
  };
}

export default function (data) {
  const productName = buildProductName(data.runId);
  const price = Number(__ENV.PRICE || 1000);
  const salePrice = Number(__ENV.SALE_PRICE || __ENV.PRICE || 1000);
  const payload = JSON.stringify({
    name: productName,
    price,
    salePrice,
    stock: Number(__ENV.STOCK || 5),
    category: __ENV.CATEGORY || 'KEYBOARD',
    description: `k6 outbox load ${productName}`,
    imageUrl: 'https://example.com/k6-product.png',
    detail: {
      source: 'k6',
      runId: data.runId,
    },
  });

  const response = http.post(`${data.baseUrl}/api/v1/products/create`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${data.accessToken}`,
    },
    tags: {
      name: 'create_product',
      experiment: __ENV.EXPERIMENT_NAME || 'product-create-load',
    },
  });

  const passed = check(response, {
    'create product status is 201': (res) => res.status === 201,
  });

  if (passed) {
    productsCreated.add(1);
  } else {
    productCreateFailures.add(1);
  }

  if (sleepBetween > 0) {
    sleep(sleepBetween);
  }
}

export function handleSummary(data) {
  const summaryPath = __ENV.SUMMARY_PATH || '/results/product-create-summary.json';

  return {
    stdout: [
      '',
      `experiment=${__ENV.EXPERIMENT_NAME || 'product-create-load'}`,
      `run_id=${experimentRunId}`,
      `http_req_failed=${formatMetricValue(data.metrics.http_req_failed, 'rate')}`,
      `http_req_duration_p95=${formatMetricValue(data.metrics.http_req_duration, 'p(95)')}ms`,
      `products_created_total=${formatMetricValue(data.metrics.products_created_total, 'count')}`,
      `product_create_failures_total=${formatMetricValue(data.metrics.product_create_failures_total, 'count')}`,
      '',
    ].join('\n'),
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}

function signUp(baseUrl, email, name, password) {
  const response = requestWithRetry(() =>
    http.post(
      `${baseUrl}/api/v1/members/signup`,
      JSON.stringify({ email, name, password }),
      {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'signup' },
      }
    ),
    {
      action: 'signup',
      expectedStatuses: [201, 409],
      retryOnStatuses: [500, 502, 503, 504],
    }
  );

  if (response.status !== 201 && response.status !== 409) {
    fail(`signup failed: status=${response.status} body=${response.body}`);
  }
}

function login(baseUrl, email, password) {
  const response = requestWithRetry(() =>
    http.post(
      `${baseUrl}/api/v1/auth/login`,
      JSON.stringify({ email, password }),
      {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'login' },
      }
    ),
    {
      action: 'login',
      expectedStatuses: [200],
      retryOnStatuses: [500, 502, 503, 504],
      maxAttempts: 6,
      backoffSeconds: 1,
    }
  );

  if (!response || response.status !== 200) {
    fail(`login failed: status=${response.status} body=${response.body}`);
  }

  const payload = response.json();
  if (!payload.accessToken) {
    fail(`login response does not contain accessToken: body=${response.body}`);
  }

  return payload.accessToken;
}

function promoteSeller(baseUrl, accessToken, accountHolder) {
  const response = requestWithRetry(() =>
    http.patch(
      `${baseUrl}/api/v1/members/role`,
      JSON.stringify({
        bankCode: __ENV.BANK_CODE || '088',
        accountNumber: __ENV.ACCOUNT_NUMBER || '1234567890',
        accountHolder: __ENV.ACCOUNT_HOLDER || accountHolder,
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${accessToken}`,
        },
        tags: { name: 'promote_seller' },
      }
    ),
    {
      action: 'promote seller',
      expectedStatuses: [200, 409],
      retryOnStatuses: [500, 502, 503, 504],
      maxAttempts: 5,
      backoffSeconds: 1,
    }
  );

  if (response.status !== 200 && response.status !== 409) {
    fail(`promote seller failed: status=${response.status} body=${response.body}`);
  }
}

function requestWithRetry(requestFn, options = {}) {
  const {
    action = 'request',
    expectedStatuses = [200],
    retryOnStatuses = [500, 502, 503, 504],
    maxAttempts = 5,
    backoffSeconds = 0.5,
  } = options;

  let lastResponse = null;

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    lastResponse = requestFn();

    if (lastResponse && expectedStatuses.includes(lastResponse.status)) {
      return lastResponse;
    }

    const shouldRetry =
      !lastResponse ||
      retryOnStatuses.includes(lastResponse.status) ||
      lastResponse.error_code !== 0;

    if (!shouldRetry || attempt === maxAttempts) {
      return lastResponse;
    }

    console.warn(
      `${action} retrying: attempt=${attempt} status=${lastResponse.status} error=${lastResponse.error}`
    );
    sleep(backoffSeconds * attempt);
  }

  return lastResponse;
}

function buildProductName(runId) {
  return [
    'k6',
    runId,
    exec.vu.idInTest,
    exec.scenario.iterationInTest,
  ].join('-');
}

function formatMetricValue(metric, key) {
  if (!metric || !metric.values || metric.values[key] === undefined) {
    return '0';
  }

  return Number(metric.values[key]).toFixed(2);
}
