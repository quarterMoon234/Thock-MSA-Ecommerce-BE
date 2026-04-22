import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 1,
  iterations: 1,
};

const productServiceBaseUrl = (__ENV.PRODUCT_SERVICE_BASE_URL || 'http://product-service:8082').replace(/\/$/, '');
const runId = __ENV.RUN_ID || `${Date.now()}`;
const phase = __ENV.EXPERIMENT_PHASE || 'before';
const phaseRunId = `${runId}-${phase}`;
const topic = __ENV.EXPERIMENT_TOPIC || 'market.order.stock.changed.experiment.inbox';
const consumerGroup = __ENV.EXPERIMENT_CONSUMER_GROUP || `product-inbox-experiment-${phaseRunId}`;
const duplicateCount = Number(__ENV.EXPERIMENT_DUPLICATE_COUNT || 100);
const quantity = Number(__ENV.EXPERIMENT_QUANTITY || 1);
const stock = Number(__ENV.EXPERIMENT_STOCK || 100);
const pollIntervalMs = Number(__ENV.EXPERIMENT_POLL_INTERVAL_MS || 1000);
const pollTimeoutSeconds = Number(__ENV.EXPERIMENT_POLL_TIMEOUT_SECONDS || 120);

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

function createProduct() {
  const response = request('POST', '/api/v1/experiments/stock/products', {
    sellerId: 1,
    name: `inbox-exp-${phaseRunId}`,
    price: 10000,
    salePrice: 10000,
    category: 'KEYBOARD',
    stock,
  }, 201);

  return response.json();
}

function pollSummary() {
  const deadline = Date.now() + (pollTimeoutSeconds * 1000);

  while (Date.now() < deadline) {
    const response = request('GET', `/api/v1/experiments/inbox/runs/${phaseRunId}/summary`);
    const summary = response.json();

    if (summary.completed === true) {
      return summary;
    }

    sleep(pollIntervalMs / 1000);
  }

  const finalResponse = request('GET', `/api/v1/experiments/inbox/runs/${phaseRunId}/summary`);
  throw new Error(`Inbox experiment polling timed out. summary=${finalResponse.body}`);
}

export default function () {
  const product = createProduct();
  const orderNumber = `inbox-exp:${phaseRunId}:order-1`;
  const startedAtMillis = Date.now();

  request('POST', '/api/v1/experiments/inbox/runs/reset', {
    runId: phaseRunId,
    productId: product.productId,
    orderNumber,
    eventType: 'RESERVE',
    quantity,
    expectedMessageCount: duplicateCount,
    topic,
    consumerGroup,
    initialStock: product.stock,
    initialReservedStock: product.reservedStock,
    startedAtMillis,
  }, 204);

  request('POST', '/api/v1/experiments/inbox/runs/publish', {
    runId: phaseRunId,
    topic,
    orderNumber,
    eventType: 'RESERVE',
    productId: product.productId,
    quantity,
    duplicateCount,
  }, 202);

  const summary = pollSummary();

  console.log(`phase=${phase}`);
  console.log(`phase_run_id=${phaseRunId}`);
  console.log(`product_id=${product.productId}`);
  console.log(`expected_messages=${summary.expectedMessageCount}`);
  console.log(`processed=${summary.processedCount}`);
  console.log(`duplicate_skipped=${summary.duplicateSkippedCount}`);
  console.log(`failed=${summary.failedCount}`);
  console.log(`reserved_delta=${summary.reservedDelta}`);
  console.log(`applied_reservation_count=${summary.appliedReservationCount}`);
  console.log(`inbox_record_count=${summary.inboxRecordCount}`);
}
