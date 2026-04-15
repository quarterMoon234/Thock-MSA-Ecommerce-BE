import http from 'k6/http';
import { sleep } from 'k6';
import { check } from 'k6';

export const options = {
  vus: 1,
  iterations: 1,
};

const productServiceBaseUrl = (__ENV.PRODUCT_SERVICE_BASE_URL || 'http://product-service:8082').replace(/\/$/, '');
const runId = __ENV.RUN_ID || `${Date.now()}`;
const phase = __ENV.EXPERIMENT_PHASE || 'single';
const phaseRunId = `${runId}-${phase}`;
const topic = __ENV.EXPERIMENT_TOPIC || 'market.order.stock.changed.experiment.single';
const productCount = Number(__ENV.EXPERIMENT_PRODUCT_COUNT || 12);
const totalEvents = Number(__ENV.EXPERIMENT_TOTAL_EVENTS || 3000);
const quantity = Number(__ENV.EXPERIMENT_QUANTITY || 1);
const stock = Number(__ENV.EXPERIMENT_STOCK || 100000);
const pollIntervalMs = Number(__ENV.EXPERIMENT_POLL_INTERVAL_MS || 1000);
const pollTimeoutSeconds = Number(__ENV.EXPERIMENT_POLL_TIMEOUT_SECONDS || 180);

if (totalEvents % 2 !== 0) {
  throw new Error(`EXPERIMENT_TOTAL_EVENTS must be even. totalEvents=${totalEvents}`);
}

const expectedOrderCount = totalEvents / 2;

function request(method, path, body = null, expectedStatus = 200) {
  const url = `${productServiceBaseUrl}${path}`;
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
    timeout: '180s',
  };

  const response = body === null
    ? http.request(method, url, null, params)
    : http.request(method, url, JSON.stringify(body), params);

  check(response, {
    [`${method} ${path} -> ${expectedStatus}`]: (res) => res.status === expectedStatus,
  });

  if (response.status !== expectedStatus) {
    throw new Error(`Unexpected status for ${method} ${path}: ${response.status} body=${response.body}`);
  }

  return response;
}

function createProducts() {
  const productIds = [];

  for (let index = 0; index < productCount; index += 1) {
    const response = request('POST', '/api/v1/experiments/stock/products', {
      sellerId: 1,
      name: `partition-exp-${phaseRunId}-${index}`,
      price: 10000,
      salePrice: 10000,
      category: 'KEYBOARD',
      stock,
    }, 201);

    productIds.push(response.json('productId'));
  }

  return productIds;
}

function pollSummary() {
  const deadline = Date.now() + (pollTimeoutSeconds * 1000);

  while (Date.now() < deadline) {
    const response = request('GET', `/api/v1/experiments/partition/runs/${phaseRunId}/summary`);
    const summary = response.json();

    if (summary.completed === true) {
      return summary;
    }

    sleep(pollIntervalMs / 1000);
  }

  const finalResponse = request('GET', `/api/v1/experiments/partition/runs/${phaseRunId}/summary`);
  throw new Error(`Experiment polling timed out. summary=${finalResponse.body}`);
}

export default function () {
  const productIds = createProducts();
  const startedAtMillis = Date.now();

  request('POST', '/api/v1/experiments/partition/runs/reset', {
    runId: phaseRunId,
    expectedOrderCount,
    expectedEventCount: totalEvents,
    startedAtMillis,
  }, 204);

  request('POST', '/api/v1/experiments/partition/runs/publish', {
    runId: phaseRunId,
    topic,
    productIds,
    totalEventCount: totalEvents,
    quantity,
  });

  const summary = pollSummary();

  console.log(`phase=${phase}`);
  console.log(`phase_run_id=${phaseRunId}`);
  console.log(`topic=${topic}`);
  console.log(`processed=${summary.processedEventCount}/${summary.expectedEventCount}`);
  console.log(`completed_orders=${summary.completedOrderCount}/${summary.expectedOrderCount}`);
  console.log(`ordering_violations=${summary.orderingViolationCount}`);
  console.log(`throughput_events_per_second=${summary.throughputEventsPerSecond}`);
  console.log(`total_duration_ms=${summary.totalDurationMillis}`);
}
