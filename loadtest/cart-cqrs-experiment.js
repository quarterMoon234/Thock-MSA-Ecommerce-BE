import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const runId = __ENV.RUN_ID || `${Date.now()}`;
const productExperimentBaseUrl = (__ENV.PRODUCT_EXPERIMENT_BASE_URL || 'http://product-service:8082').replace(/\/$/, '');
const marketExperimentBaseUrl = (__ENV.MARKET_EXPERIMENT_BASE_URL || 'http://market-service:8083').replace(/\/$/, '');
const scenarioDuration = __ENV.SCENARIO_DURATION || '20s';
const scenarioSlotSeconds = Number(__ENV.SCENARIO_SLOT_SECONDS || 25);
const scenarioVus = Number(__ENV.SCENARIO_VUS || 30);
const productCount = Number(__ENV.PRODUCT_COUNT || 3);
const productStock = Number(__ENV.PRODUCT_STOCK || 100000);
const productPrice = Number(__ENV.PRODUCT_PRICE || 10000);
const productSalePrice = Number(__ENV.PRODUCT_SALE_PRICE || 9000);
const readMemberCount = Number(__ENV.READ_MEMBER_COUNT || 50);
const addMemberCountPerScenario = Number(__ENV.ADD_MEMBER_COUNT_PER_SCENARIO || 100);
const readItemQuantity = Number(__ENV.READ_ITEM_QUANTITY || 1);
const addItemQuantity = Number(__ENV.ADD_ITEM_QUANTITY || 1);
const productDelayMs = Number(__ENV.PRODUCT_DELAY_MS || 900);
const baseMemberId = Number(__ENV.BASE_MEMBER_ID || 980000);

const syncReadDuration = new Trend('cart_cqrs_sync_read_duration', true);
const cqrsReadDuration = new Trend('cart_cqrs_cqrs_read_duration', true);
const syncReadDelayDuration = new Trend('cart_cqrs_sync_read_delay_duration', true);
const cqrsReadDelayDuration = new Trend('cart_cqrs_cqrs_read_delay_duration', true);
const syncAddDuration = new Trend('cart_cqrs_sync_add_duration', true);
const cqrsAddDuration = new Trend('cart_cqrs_cqrs_add_duration', true);
const syncAddDelayDuration = new Trend('cart_cqrs_sync_add_delay_duration', true);
const cqrsAddDelayDuration = new Trend('cart_cqrs_cqrs_add_delay_duration', true);

const syncReadRequests = new Counter('cart_cqrs_sync_read_requests_total');
const cqrsReadRequests = new Counter('cart_cqrs_cqrs_read_requests_total');
const syncReadDelayRequests = new Counter('cart_cqrs_sync_read_delay_requests_total');
const cqrsReadDelayRequests = new Counter('cart_cqrs_cqrs_read_delay_requests_total');
const syncAddRequests = new Counter('cart_cqrs_sync_add_requests_total');
const cqrsAddRequests = new Counter('cart_cqrs_cqrs_add_requests_total');
const syncAddDelayRequests = new Counter('cart_cqrs_sync_add_delay_requests_total');
const cqrsAddDelayRequests = new Counter('cart_cqrs_cqrs_add_delay_requests_total');

const syncReadFailures = new Counter('cart_cqrs_sync_read_failures_total');
const cqrsReadFailures = new Counter('cart_cqrs_cqrs_read_failures_total');
const syncReadDelayFailures = new Counter('cart_cqrs_sync_read_delay_failures_total');
const cqrsReadDelayFailures = new Counter('cart_cqrs_cqrs_read_delay_failures_total');
const syncAddFailures = new Counter('cart_cqrs_sync_add_failures_total');
const cqrsAddFailures = new Counter('cart_cqrs_cqrs_add_failures_total');
const syncAddDelayFailures = new Counter('cart_cqrs_sync_add_delay_failures_total');
const cqrsAddDelayFailures = new Counter('cart_cqrs_cqrs_add_delay_failures_total');

const syncReadSuccessRate = new Rate('cart_cqrs_sync_read_success_rate');
const cqrsReadSuccessRate = new Rate('cart_cqrs_cqrs_read_success_rate');
const syncReadDelaySuccessRate = new Rate('cart_cqrs_sync_read_delay_success_rate');
const cqrsReadDelaySuccessRate = new Rate('cart_cqrs_cqrs_read_delay_success_rate');
const syncAddSuccessRate = new Rate('cart_cqrs_sync_add_success_rate');
const cqrsAddSuccessRate = new Rate('cart_cqrs_cqrs_add_success_rate');
const syncAddDelaySuccessRate = new Rate('cart_cqrs_sync_add_delay_success_rate');
const cqrsAddDelaySuccessRate = new Rate('cart_cqrs_cqrs_add_delay_success_rate');

export const options = {
  scenarios: {
    sync_read: makeScenario('syncRead', 0),
    cqrs_read: makeScenario('cqrsRead', 1),
    sync_read_delay: makeScenario('syncReadDelay', 2),
    cqrs_read_delay: makeScenario('cqrsReadDelay', 3),
    sync_add: makeScenario('syncAdd', 4),
    cqrs_add: makeScenario('cqrsAdd', 5),
    sync_add_delay: makeScenario('syncAddDelay', 6),
    cqrs_add_delay: makeScenario('cqrsAddDelay', 7),
  },
  thresholds: {
    cart_cqrs_sync_read_success_rate: ['rate>0.99'],
    cart_cqrs_cqrs_read_success_rate: ['rate>0.99'],
    cart_cqrs_sync_read_delay_success_rate: ['rate>0.99'],
    cart_cqrs_cqrs_read_delay_success_rate: ['rate>0.99'],
    cart_cqrs_sync_add_success_rate: ['rate>0.99'],
    cart_cqrs_cqrs_add_success_rate: ['rate>0.99'],
    cart_cqrs_sync_add_delay_success_rate: ['rate>0.99'],
    cart_cqrs_cqrs_add_delay_success_rate: ['rate>0.99'],
  },
};

export function setup() {
  const productIds = [];

  for (let index = 0; index < productCount; index += 1) {
    const response = http.post(
      `${productExperimentBaseUrl}/api/v1/experiments/stock/products`,
      JSON.stringify({
        name: `cart-cqrs-${runId}-${index + 1}`,
        price: productPrice,
        salePrice: productSalePrice,
        stock: productStock,
      }),
      {
        headers: {
          'Content-Type': 'application/json',
        },
        tags: {
          name: 'cart_cqrs_create_product',
        },
      }
    );

    check(response, {
      'create experiment product status is 201': (res) => res.status === 201,
    }) || fail(`failed to create experiment product: status=${response.status} body=${response.body}`);

    productIds.push(response.json().productId);
  }

  const datasetResponse = http.post(
    `${marketExperimentBaseUrl}/api/v1/experiments/cart-cqrs/dataset/seed`,
    JSON.stringify({
      baseMemberId,
      productIds,
      readMemberCount,
      addMemberCountPerScenario,
      readItemQuantity,
      addItemQuantity,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: {
        name: 'cart_cqrs_seed_dataset',
      },
    }
  );

  check(datasetResponse, {
    'seed dataset status is 200': (res) => res.status === 200,
  }) || fail(`failed to seed cart cqrs dataset: status=${datasetResponse.status} body=${datasetResponse.body}`);

  const dataset = datasetResponse.json();
  if (!dataset || !dataset.productIds || dataset.productIds.length === 0) {
    fail(`invalid dataset response: body=${datasetResponse.body}`);
  }

  return dataset;
}

export function syncRead(data) {
  const memberId = pickMemberId(data.readMemberIds);
  const response = http.get(
    `${marketExperimentBaseUrl}/api/v1/experiments/cart-cqrs/baseline/read/${memberId}`,
    { tags: { name: 'cart_cqrs_sync_read' } }
  );

  recordReadResult(
    response,
    syncReadDuration,
    syncReadRequests,
    syncReadFailures,
    syncReadSuccessRate,
    data.productIds.length
  );
}

export function cqrsRead(data) {
  const memberId = pickMemberId(data.readMemberIds);
  const response = http.get(
    `${marketExperimentBaseUrl}/api/v1/experiments/cart-cqrs/cqrs/read/${memberId}`,
    { tags: { name: 'cart_cqrs_cqrs_read' } }
  );

  recordReadResult(
    response,
    cqrsReadDuration,
    cqrsReadRequests,
    cqrsReadFailures,
    cqrsReadSuccessRate,
    data.productIds.length
  );
}

export function syncReadDelay(data) {
  const memberId = pickMemberId(data.readMemberIds);
  const response = http.get(
    `${marketExperimentBaseUrl}/api/v1/experiments/cart-cqrs/baseline/read/${memberId}?productDelayMs=${productDelayMs}`,
    { tags: { name: 'cart_cqrs_sync_read_delay' } }
  );

  recordReadResult(
    response,
    syncReadDelayDuration,
    syncReadDelayRequests,
    syncReadDelayFailures,
    syncReadDelaySuccessRate,
    data.productIds.length
  );
}

export function cqrsReadDelay(data) {
  const memberId = pickMemberId(data.readMemberIds);
  const response = http.get(
    `${marketExperimentBaseUrl}/api/v1/experiments/cart-cqrs/cqrs/read/${memberId}`,
    { tags: { name: 'cart_cqrs_cqrs_read_delay' } }
  );

  recordReadResult(
    response,
    cqrsReadDelayDuration,
    cqrsReadDelayRequests,
    cqrsReadDelayFailures,
    cqrsReadDelaySuccessRate,
    data.productIds.length
  );
}

export function syncAdd(data) {
  const memberId = pickMemberId(data.syncAddMemberIds);
  const response = http.post(
    `${marketExperimentBaseUrl}/api/v1/experiments/cart-cqrs/baseline/add/${memberId}`,
    JSON.stringify({ productId: data.addTargetProductId, quantity: addItemQuantity }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'cart_cqrs_sync_add' },
    }
  );

  recordAddResult(
    response,
    syncAddDuration,
    syncAddRequests,
    syncAddFailures,
    syncAddSuccessRate,
    data.addTargetProductId
  );
}

export function cqrsAdd(data) {
  const memberId = pickMemberId(data.cqrsAddMemberIds);
  const response = http.post(
    `${marketExperimentBaseUrl}/api/v1/experiments/cart-cqrs/cqrs/add/${memberId}`,
    JSON.stringify({ productId: data.addTargetProductId, quantity: addItemQuantity }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'cart_cqrs_cqrs_add' },
    }
  );

  recordAddResult(
    response,
    cqrsAddDuration,
    cqrsAddRequests,
    cqrsAddFailures,
    cqrsAddSuccessRate,
    data.addTargetProductId
  );
}

export function syncAddDelay(data) {
  const memberId = pickMemberId(data.syncAddDelayMemberIds);
  const response = http.post(
    `${marketExperimentBaseUrl}/api/v1/experiments/cart-cqrs/baseline/add/${memberId}?productDelayMs=${productDelayMs}`,
    JSON.stringify({ productId: data.addTargetProductId, quantity: addItemQuantity }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'cart_cqrs_sync_add_delay' },
    }
  );

  recordAddResult(
    response,
    syncAddDelayDuration,
    syncAddDelayRequests,
    syncAddDelayFailures,
    syncAddDelaySuccessRate,
    data.addTargetProductId
  );
}

export function cqrsAddDelay(data) {
  const memberId = pickMemberId(data.cqrsAddDelayMemberIds);
  const response = http.post(
    `${marketExperimentBaseUrl}/api/v1/experiments/cart-cqrs/cqrs/add/${memberId}`,
    JSON.stringify({ productId: data.addTargetProductId, quantity: addItemQuantity }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'cart_cqrs_cqrs_add_delay' },
    }
  );

  recordAddResult(
    response,
    cqrsAddDelayDuration,
    cqrsAddDelayRequests,
    cqrsAddDelayFailures,
    cqrsAddDelaySuccessRate,
    data.addTargetProductId
  );
}

export function handleSummary(data) {
  const summaryPath = __ENV.SUMMARY_PATH || `/results/cart-cqrs-experiment-${runId}.json`;
  const scenarioSeconds = parseDurationToSeconds(scenarioDuration);

  return {
    stdout: [
      '',
      `experiment=cart-cqrs`,
      `run_id=${runId}`,
      `product_delay_ms=${productDelayMs}`,
      formatScenarioSummary(data, 'sync_read', scenarioSeconds),
      formatScenarioSummary(data, 'cqrs_read', scenarioSeconds),
      formatScenarioSummary(data, 'sync_read_delay', scenarioSeconds),
      formatScenarioSummary(data, 'cqrs_read_delay', scenarioSeconds),
      formatScenarioSummary(data, 'sync_add', scenarioSeconds),
      formatScenarioSummary(data, 'cqrs_add', scenarioSeconds),
      formatScenarioSummary(data, 'sync_add_delay', scenarioSeconds),
      formatScenarioSummary(data, 'cqrs_add_delay', scenarioSeconds),
      `read_avg_improvement_pct=${formatPercentDifference(metricAvg(data, 'cart_cqrs_sync_read_duration'), metricAvg(data, 'cart_cqrs_cqrs_read_duration'))}`,
      `read_delay_avg_improvement_pct=${formatPercentDifference(metricAvg(data, 'cart_cqrs_sync_read_delay_duration'), metricAvg(data, 'cart_cqrs_cqrs_read_delay_duration'))}`,
      `add_avg_improvement_pct=${formatPercentDifference(metricAvg(data, 'cart_cqrs_sync_add_duration'), metricAvg(data, 'cart_cqrs_cqrs_add_duration'))}`,
      `add_delay_avg_improvement_pct=${formatPercentDifference(metricAvg(data, 'cart_cqrs_sync_add_delay_duration'), metricAvg(data, 'cart_cqrs_cqrs_add_delay_duration'))}`,
      '',
    ].join('\n'),
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}

function makeScenario(execName, slotIndex) {
  return {
    executor: 'constant-vus',
    vus: scenarioVus,
    duration: scenarioDuration,
    gracefulStop: '0s',
    exec: execName,
    startTime: `${slotIndex * scenarioSlotSeconds}s`,
  };
}

function pickMemberId(memberIds) {
  const iteration = exec.scenario.iterationInTest;
  return memberIds[iteration % memberIds.length];
}

function recordReadResult(response, durationMetric, requestCounter, failureCounter, successRate, expectedItemCount) {
  requestCounter.add(1);
  durationMetric.add(response.timings.duration);

  const passed = check(response, {
    'read status is 200': (res) => res.status === 200,
    'read contains expected item count': (res) => {
      if (res.status !== 200) {
        return false;
      }
      const payload = res.json();
      return payload && Array.isArray(payload.items) && payload.items.length >= expectedItemCount;
    },
  });

  successRate.add(passed);
  if (!passed) {
    failureCounter.add(1);
  }
}

function recordAddResult(response, durationMetric, requestCounter, failureCounter, successRate, expectedProductId) {
  requestCounter.add(1);
  durationMetric.add(response.timings.duration);

  const passed = check(response, {
    'add status is 200': (res) => res.status === 200,
    'add returns expected product id': (res) => {
      if (res.status !== 200) {
        return false;
      }
      const payload = res.json();
      return payload && payload.productId === expectedProductId;
    },
  });

  successRate.add(passed);
  if (!passed) {
    failureCounter.add(1);
  }
}

function formatScenarioSummary(data, scenarioName, scenarioSeconds) {
  const prefix = `cart_cqrs_${scenarioName}`;
  const requestCount = metricCount(data, `${prefix}_requests_total`);
  const avg = metricAvg(data, `${prefix}_duration`);
  const p95 = metricP95(data, `${prefix}_duration`);
  const successRate = metricRate(data, `${prefix}_success_rate`);
  const failures = metricCount(data, `${prefix}_failures_total`);
  const throughput = scenarioSeconds > 0 ? requestCount / scenarioSeconds : 0;

  return [
    `${scenarioName}.requests=${requestCount}`,
    `${scenarioName}.throughput=${formatNumber(throughput)}`,
    `${scenarioName}.avg=${formatNumber(avg)}ms`,
    `${scenarioName}.p95=${formatNumber(p95)}ms`,
    `${scenarioName}.success_rate=${formatNumber(successRate)}`,
    `${scenarioName}.failures=${failures}`,
  ].join(' ');
}

function metricAvg(data, name) {
  return metricValue(data, name, 'avg');
}

function metricP95(data, name) {
  return metricValue(data, name, 'p(95)');
}

function metricCount(data, name) {
  return metricValue(data, name, 'count');
}

function metricRate(data, name) {
  return metricValue(data, name, 'rate');
}

function metricValue(data, name, key) {
  if (!data.metrics[name] || !data.metrics[name].values) {
    return 0;
  }

  return data.metrics[name].values[key] || 0;
}

function formatPercentDifference(beforeValue, afterValue) {
  if (!beforeValue || beforeValue <= 0) {
    return '0';
  }
  return formatNumber(((beforeValue - afterValue) / beforeValue) * 100);
}

function formatNumber(value) {
  return Number(value || 0).toFixed(2);
}

function parseDurationToSeconds(rawDuration) {
  if (!rawDuration) {
    return 0;
  }

  const trimmed = `${rawDuration}`.trim();
  if (trimmed.endsWith('ms')) {
    return Number(trimmed.slice(0, -2)) / 1000;
  }
  if (trimmed.endsWith('s')) {
    return Number(trimmed.slice(0, -1));
  }
  if (trimmed.endsWith('m')) {
    return Number(trimmed.slice(0, -1)) * 60;
  }
  return Number(trimmed);
}
