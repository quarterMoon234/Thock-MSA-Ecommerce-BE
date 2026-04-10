import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const productDetailReads = new Counter('product_detail_reads_total');
const productDetailFailures = new Counter('product_detail_read_failures_total');
const productInternalReads = new Counter('product_internal_list_reads_total');
const productInternalFailures = new Counter('product_internal_list_failures_total');
const productDetailDuration = new Trend('product_detail_duration', true);
const productInternalListDuration = new Trend('product_internal_list_duration', true);

const experimentRunId = __ENV.RUN_ID || `${Date.now()}`;
const target = (__ENV.TARGET || 'internal').toLowerCase();
const supportedTargets = ['detail', 'internal', 'mixed'];
const rate = parsePositiveInteger(__ENV.RATE, 50);
const preAllocatedVUs = parsePositiveInteger(__ENV.PRE_ALLOCATED_VUS, 50);
const maxVUs = parsePositiveInteger(__ENV.MAX_VUS, 100);
const executor = (__ENV.EXECUTOR || 'iterations').toLowerCase();
const iterations = parsePositiveInteger(__ENV.ITERATIONS, 3000);
const vus = parsePositiveInteger(__ENV.VUS, 50);
const sleepBetween = parseNonNegativeNumber(__ENV.SLEEP_BETWEEN, 0);
const warmCache = (__ENV.WARM_CACHE || 'true').toLowerCase() === 'true';
const productIds = parseProductIds(__ENV.PRODUCT_IDS || '1,2,3');
const productBatchSize = parsePositiveInteger(__ENV.PRODUCT_BATCH_SIZE, productIds.length);

export const options = {
  scenarios: {
    product_cache_read_load: buildScenario(),
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  if (executor !== 'iterations' && executor !== 'rate') {
    fail('EXECUTOR must be either iterations or rate.');
  }
  if (!supportedTargets.includes(target)) {
    fail(`TARGET must be one of ${supportedTargets.join(', ')}.`);
  }
  if (productIds.length === 0) {
    fail('PRODUCT_IDS must contain at least one valid product id.');
  }

  const baseUrl = (__ENV.BASE_URL || 'http://api-gateway:8080').replace(/\/$/, '');
  const detailBaseUrl = (__ENV.DETAIL_BASE_URL || baseUrl).replace(/\/$/, '');
  const internalBaseUrl = (__ENV.INTERNAL_BASE_URL || 'http://product-service:8082').replace(/\/$/, '');
  const internalAuthSecret =
    __ENV.INTERNAL_AUTH_SECRET || __ENV.SECURITY_SERVICE_INTERNAL_SECRET || '';

  if (usesInternalEndpoint() && !internalAuthSecret) {
    fail('INTERNAL_AUTH_SECRET or SECURITY_SERVICE_INTERNAL_SECRET is required for internal product API.');
  }

  if (warmCache && target === 'detail') {
    warmDetailCache(detailBaseUrl, productIds);
  } else if (warmCache && target === 'internal') {
    warmInternalCache(internalBaseUrl, internalAuthSecret, productIds);
  } else if (warmCache) {
    warmDetailCache(detailBaseUrl, productIds);
    warmInternalCache(internalBaseUrl, internalAuthSecret, productIds);
  }

  return {
    baseUrl,
    detailBaseUrl,
    internalBaseUrl,
    internalAuthSecret,
  };
}

export default function (data) {
  const iteration = exec.scenario.iterationInTest;

  if (target === 'detail') {
    readProductDetail(data.detailBaseUrl, productIds[iteration % productIds.length]);
  } else if (target === 'internal') {
    readInternalProductList(data.internalBaseUrl, data.internalAuthSecret, pickBatch(productIds, iteration));
  } else if (iteration % 2 === 0) {
    readProductDetail(data.detailBaseUrl, productIds[iteration % productIds.length]);
  } else {
    readInternalProductList(data.internalBaseUrl, data.internalAuthSecret, pickBatch(productIds, iteration));
  }

  if (sleepBetween > 0) {
    sleep(sleepBetween);
  }
}

export function handleSummary(data) {
  const summaryPath =
    __ENV.SUMMARY_PATH || `/results/product-cache-read-${target}-${experimentRunId}.json`;

  return {
    stdout: [
      '',
      `experiment=${__ENV.EXPERIMENT_NAME || 'product-cache-read-load'}`,
      `run_id=${experimentRunId}`,
      `target=${target}`,
      `executor=${executor}`,
      `warm_cache=${warmCache}`,
      `product_ids=${productIds.join(',')}`,
      `product_batch_size=${productBatchSize}`,
      `iterations=${executor === 'iterations' ? iterations : 'n/a'}`,
      `vus=${executor === 'iterations' ? vus : 'n/a'}`,
      `rate=${executor === 'rate' ? rate : 'n/a'}`,
      `duration=${executor === 'rate' ? (__ENV.DURATION || '1m') : 'n/a'}`,
      `http_req_failed_rate=${formatMetricValue(data.metrics.http_req_failed, 'rate')}`,
      `http_req_duration_avg=${formatMetricValue(data.metrics.http_req_duration, 'avg')}ms`,
      `http_req_duration_p95=${formatMetricValue(data.metrics.http_req_duration, 'p(95)')}ms`,
      `http_reqs_count=${formatMetricValue(data.metrics.http_reqs, 'count')}`,
      `http_reqs_rate=${formatMetricValue(data.metrics.http_reqs, 'rate')}`,
      `product_detail_reads_total=${formatMetricValue(data.metrics.product_detail_reads_total, 'count')}`,
      `product_detail_read_failures_total=${formatMetricValue(data.metrics.product_detail_read_failures_total, 'count')}`,
      `product_detail_duration_avg=${formatMetricValue(data.metrics.product_detail_duration, 'avg')}ms`,
      `product_detail_duration_p95=${formatMetricValue(data.metrics.product_detail_duration, 'p(95)')}ms`,
      `product_internal_list_reads_total=${formatMetricValue(data.metrics.product_internal_list_reads_total, 'count')}`,
      `product_internal_list_failures_total=${formatMetricValue(data.metrics.product_internal_list_failures_total, 'count')}`,
      `product_internal_list_duration_avg=${formatMetricValue(data.metrics.product_internal_list_duration, 'avg')}ms`,
      `product_internal_list_duration_p95=${formatMetricValue(data.metrics.product_internal_list_duration, 'p(95)')}ms`,
      '',
    ].join('\n'),
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}

function buildScenario() {
  if (executor === 'rate') {
    return {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs,
      maxVUs,
      gracefulStop: '5s',
    };
  }

  return {
    executor: 'shared-iterations',
    vus,
    iterations,
    maxDuration: __ENV.MAX_DURATION || '2m',
    gracefulStop: '5s',
  };
}

function readProductDetail(baseUrl, productId) {
  const response = http.get(`${baseUrl}/api/v1/products/${productId}`, {
    tags: {
      name: 'get_product_detail',
      experiment: __ENV.EXPERIMENT_NAME || 'product-cache-read-load',
      target,
    },
  });
  productDetailDuration.add(response.timings.duration);

  const payload = parseJson(response);
  const passed = check(response, {
    'product detail status is 200': (res) => res.status === 200,
    'product detail id matches': () => payload && Number(payload.id) === productId,
  });

  if (passed) {
    productDetailReads.add(1);
  } else {
    productDetailFailures.add(1);
  }
}

function readInternalProductList(internalBaseUrl, internalAuthSecret, productIdsToRead) {
  const response = http.post(
    `${internalBaseUrl}/api/v1/products/internal/list`,
    JSON.stringify(productIdsToRead),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Internal-Auth': internalAuthSecret,
      },
      tags: {
        name: 'get_internal_product_list',
        experiment: __ENV.EXPERIMENT_NAME || 'product-cache-read-load',
        target,
      },
    }
  );
  productInternalListDuration.add(response.timings.duration);

  const payload = parseJson(response);
  const passed = check(response, {
    'internal product list status is 200': (res) => res.status === 200,
    'internal product list returns requested ids': () => containsAllProductIds(payload, productIdsToRead),
  });

  if (passed) {
    productInternalReads.add(1);
  } else {
    productInternalFailures.add(1);
  }
}

function warmDetailCache(baseUrl, productIdsToWarm) {
  for (const productId of productIdsToWarm) {
    const response = requestWithRetry(
      () => http.get(`${baseUrl}/api/v1/products/${productId}`, { tags: { name: 'warm_product_detail' } }),
      { action: `warm product detail productId=${productId}`, expectedStatuses: [200] }
    );

    if (!response || response.status !== 200) {
      fail(`warm product detail failed: productId=${productId} status=${response && response.status}`);
    }
  }
}

function warmInternalCache(internalBaseUrl, internalAuthSecret, productIdsToWarm) {
  const response = requestWithRetry(
    () =>
      http.post(
        `${internalBaseUrl}/api/v1/products/internal/list`,
        JSON.stringify(productIdsToWarm),
        {
          headers: {
            'Content-Type': 'application/json',
            'X-Internal-Auth': internalAuthSecret,
          },
          tags: { name: 'warm_internal_product_list' },
        }
      ),
    { action: 'warm internal product list', expectedStatuses: [200] }
  );

  const payload = parseJson(response);
  if (!response || response.status !== 200 || !containsAllProductIds(payload, productIdsToWarm)) {
    fail(`warm internal product list failed: status=${response && response.status} body=${response && response.body}`);
  }
}

function parseProductIds(rawValue) {
  const parsed = rawValue
    .split(',')
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isFinite(value) && value > 0);

  return [...new Set(parsed)];
}

function parsePositiveInteger(rawValue, defaultValue) {
  const parsed = Number(rawValue || defaultValue);

  if (!Number.isFinite(parsed) || parsed <= 0) {
    return defaultValue;
  }

  return Math.floor(parsed);
}

function parseNonNegativeNumber(rawValue, defaultValue) {
  const parsed = Number(rawValue || defaultValue);

  if (!Number.isFinite(parsed) || parsed < 0) {
    return defaultValue;
  }

  return parsed;
}

function pickBatch(ids, iteration) {
  const size = Math.max(1, Math.min(productBatchSize, ids.length));
  const result = [];

  for (let i = 0; i < size; i += 1) {
    result.push(ids[(iteration + i) % ids.length]);
  }

  return result;
}

function usesInternalEndpoint() {
  return target === 'internal' || target === 'mixed';
}

function containsAllProductIds(payload, expectedProductIds) {
  if (!Array.isArray(payload)) {
    return false;
  }

  const actualIds = new Set(payload.map((item) => Number(item.id)));
  return expectedProductIds.every((productId) => actualIds.has(productId));
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

function requestWithRetry(requestFn, options = {}) {
  const {
    action = 'request',
    expectedStatuses = [200],
    retryOnStatuses = [500, 502, 503, 504],
    maxAttempts = 10,
    backoffSeconds = 1,
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
