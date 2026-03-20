import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const cartReads = new Counter('cart_reads_total');
const cartReadFailures = new Counter('cart_read_failures_total');

const experimentRunId = __ENV.RUN_ID || `${Date.now()}`;
const rate = Number(__ENV.RATE || 50);
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || 50);
const maxVUs = Number(__ENV.MAX_VUS || 100);
const sleepBetween = Number(__ENV.SLEEP_BETWEEN || 0);
const productIds = (__ENV.PRODUCT_IDS || '1,2,3')
  .split(',')
  .map((value) => Number(value.trim()))
  .filter((value) => Number.isFinite(value) && value > 0);
const cartItemQuantity = Number(__ENV.CART_ITEM_QUANTITY || 1);

export const options = {
  scenarios: {
    cart_read_load: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
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
  if (productIds.length === 0) {
    fail('PRODUCT_IDS must contain at least one valid product id.');
  }

  const baseUrl = (__ENV.BASE_URL || 'http://api-gateway:8080').replace(/\/$/, '');
  const password = __ENV.PASSWORD || 'password123!';
  const runId = experimentRunId;
  const buyerEmail = __ENV.BUYER_EMAIL || `buyer-k6-${runId}@example.com`;
  const buyerName = __ENV.BUYER_NAME || `buyer-k6-${runId}`;

  let accessToken = __ENV.BUYER_ACCESS_TOKEN;
  if (!accessToken) {
    signUp(baseUrl, buyerEmail, buyerName, password);
    sleep(0.5);
    accessToken = login(baseUrl, buyerEmail, password);
  }

  waitForCartReady(baseUrl, accessToken);
  clearCart(baseUrl, accessToken);
  seedCart(baseUrl, accessToken, productIds, cartItemQuantity);
  verifyCart(baseUrl, accessToken, productIds);

  return {
    accessToken,
    baseUrl,
    runId,
    buyerEmail,
  };
}

export default function (data) {
  const response = http.get(`${data.baseUrl}/api/v1/carts`, {
    headers: {
      Authorization: `Bearer ${data.accessToken}`,
    },
    tags: {
      name: 'get_cart_items',
      experiment: __ENV.EXPERIMENT_NAME || 'cart-read-load',
    },
  });

  const passed = check(response, {
    'get cart status is 200': (res) => res.status === 200,
    'get cart returns items': (res) => {
      if (res.status !== 200) {
        return false;
      }

      const payload = res.json();
      return payload && Array.isArray(payload.items) && payload.items.length >= productIds.length;
    },
  });

  if (passed) {
    cartReads.add(1);
  } else {
    cartReadFailures.add(1);
  }

  if (sleepBetween > 0) {
    sleep(sleepBetween);
  }
}

export function handleSummary(data) {
  const summaryPath = __ENV.SUMMARY_PATH || '/results/cart-read-summary.json';
  const buyerEmail = __ENV.BUYER_EMAIL || `buyer-k6-${experimentRunId}@example.com`;

  return {
    stdout: [
      '',
      `experiment=${__ENV.EXPERIMENT_NAME || 'cart-read-load'}`,
      `run_id=${experimentRunId}`,
      `buyer_email=${buyerEmail}`,
      `product_ids=${productIds.join(',')}`,
      `http_req_failed_rate=${formatMetricValue(data.metrics.http_req_failed, 'rate')}`,
      `http_req_duration_avg=${formatMetricValue(data.metrics.http_req_duration, 'avg')}ms`,
      `http_req_duration_p95=${formatMetricValue(data.metrics.http_req_duration, 'p(95)')}ms`,
      `http_reqs_count=${formatMetricValue(data.metrics.http_reqs, 'count')}`,
      `http_reqs_rate=${formatMetricValue(data.metrics.http_reqs, 'rate')}`,
      `cart_reads_total=${formatMetricValue(data.metrics.cart_reads_total, 'count')}`,
      `cart_read_failures_total=${formatMetricValue(data.metrics.cart_read_failures_total, 'count')}`,
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

function waitForCartReady(baseUrl, accessToken) {
  const maxAttempts = Number(__ENV.CART_READY_MAX_ATTEMPTS || 20);
  const backoffSeconds = Number(__ENV.CART_READY_BACKOFF_SECONDS || 1);

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const response = http.get(`${baseUrl}/api/v1/carts`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
      tags: {
        name: 'wait_cart_ready',
      },
    });

    if (response.status === 200) {
      return;
    }

    if (attempt === maxAttempts) {
      fail(`cart was not ready: status=${response.status} body=${response.body}`);
    }

    sleep(backoffSeconds);
  }
}

function clearCart(baseUrl, accessToken) {
  const response = requestWithRetry(() =>
    http.del(
      `${baseUrl}/api/v1/carts/items`,
      JSON.stringify([]),
      {
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${accessToken}`,
        },
        tags: { name: 'clear_cart' },
      }
    ),
    {
      action: 'clear cart',
      expectedStatuses: [204],
      retryOnStatuses: [404, 500, 502, 503, 504],
      maxAttempts: 6,
      backoffSeconds: 1,
    }
  );

  if (response.status !== 204) {
    fail(`clear cart failed: status=${response.status} body=${response.body}`);
  }
}

function seedCart(baseUrl, accessToken, productIdsToSeed, quantity) {
  for (const productId of productIdsToSeed) {
    const response = requestWithRetry(() =>
      http.post(
        `${baseUrl}/api/v1/carts/items`,
        JSON.stringify({ productId, quantity }),
        {
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${accessToken}`,
          },
          tags: { name: 'seed_cart_item' },
        }
      ),
      {
        action: `seed cart productId=${productId}`,
        expectedStatuses: [201],
        retryOnStatuses: [404, 409, 500, 502, 503, 504],
        maxAttempts: 6,
        backoffSeconds: 1,
      }
    );

    if (response.status !== 201) {
      fail(`seed cart failed: productId=${productId} status=${response.status} body=${response.body}`);
    }
  }
}

function verifyCart(baseUrl, accessToken, expectedProductIds) {
  const response = http.get(`${baseUrl}/api/v1/carts`, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
    tags: { name: 'verify_cart' },
  });

  if (response.status !== 200) {
    fail(`verify cart failed: status=${response.status} body=${response.body}`);
  }

  const payload = response.json();
  const actualProductIds = (payload.items || []).map((item) => item.productId);
  for (const productId of expectedProductIds) {
    if (!actualProductIds.includes(productId)) {
      fail(`cart does not contain expected productId=${productId}: body=${response.body}`);
    }
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

function formatMetricValue(metric, key) {
  if (!metric || !metric.values || metric.values[key] === undefined) {
    return '0';
  }

  return Number(metric.values[key]).toFixed(2);
}
