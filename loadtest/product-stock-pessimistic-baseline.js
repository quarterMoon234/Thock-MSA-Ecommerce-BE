import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';

const reservationsAccepted = new Counter('stock_reservation_accepted_total');
const reservationsRejected = new Counter('stock_reservation_rejected_total');
const reservationFailures = new Counter('stock_reservation_failures_total');
const reservationAttempts = new Counter('stock_reservation_attempts_total');
const reservationDuration = new Trend('stock_reservation_duration', true);
const stockPressureRedisPreRejected = new Gauge('stock_pressure_redis_pre_rejected_count');
const stockPressureDbEntry = new Gauge('stock_pressure_db_entry_count');
const stockPressureDbSucceeded = new Gauge('stock_pressure_db_succeeded_count');
const stockPressureDbRejected = new Gauge('stock_pressure_db_rejected_count');
const stockPressureDbFailed = new Gauge('stock_pressure_db_failed_count');
const stockPressureDbActiveMax = new Gauge('stock_pressure_db_active_max');
const stockPressureDbDurationAvg = new Gauge('stock_pressure_db_duration_avg_ms');
const stockPressureDbDurationMax = new Gauge('stock_pressure_db_duration_max_ms');
const stockPressureRedisCompensation = new Gauge('stock_pressure_redis_compensation_count');
const stockPressureRedisReserved = new Gauge('stock_pressure_redis_reserved_count');
const stockPressureRedisAlreadyReserved = new Gauge('stock_pressure_redis_already_reserved_count');
const stockPressureRedisOutOfStock = new Gauge('stock_pressure_redis_out_of_stock_count');
const stockPressureRedisStockKeyMissing = new Gauge('stock_pressure_redis_stock_key_missing_count');
const stockPressureRedisInvalidArgument = new Gauge('stock_pressure_redis_invalid_argument_count');
const stockPressureRedisDisabled = new Gauge('stock_pressure_redis_disabled_count');
const stockPressureRedisUnavailable = new Gauge('stock_pressure_redis_unavailable_count');

const experimentRunId = __ENV.RUN_ID || `${Date.now()}`;
const baseUrl = (__ENV.PRODUCT_SERVICE_BASE_URL || 'http://product-service:8082').replace(/\/$/, '');
const metricsUrl = `${baseUrl}/api/v1/experiments/stock/metrics`;
const initialStock = parsePositiveInteger(__ENV.INITIAL_STOCK, 10);
const quantity = parsePositiveInteger(__ENV.QUANTITY, 1);
const executor = (__ENV.STOCK_EXECUTOR || __ENV.STOCK_EXPERIMENT_EXECUTOR || 'per-vu').toLowerCase();
const attempts = parsePositiveInteger(__ENV.ATTEMPTS || __ENV.ITERATIONS, 500);
const vus = parsePositiveInteger(__ENV.VUS, Math.min(attempts, 200));
const redisStockEnabled = parseBoolean(__ENV.PRODUCT_STOCK_REDIS_ENABLED, false);
const rebuildRedisStock = parseBoolean(__ENV.REBUILD_REDIS_STOCK, redisStockEnabled);
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

  if (rebuildRedisStock) {
    const rebuildResponse = http.post(
      `${baseUrl}/api/v1/experiments/stock/products/${payload.productId}/redis/rebuild`,
      null,
      {
        tags: {
          name: 'stock_experiment_rebuild_redis_product',
          experiment: __ENV.EXPERIMENT_NAME || 'stock-pessimistic-baseline',
        },
      }
    );

    if (rebuildResponse.status !== 200) {
      fail(
        `failed to rebuild redis stock for product: status=${rebuildResponse.status} body=${rebuildResponse.body}`
      );
    }
  }

  const redisState = fetchProductRedisState(payload.productId);
  if (redisState) {
    console.log(
      `stock_experiment_redis_state product_id=${payload.productId} redis_enabled=${redisState.redisEnabled} available_key_exists=${redisState.availableKeyExists} available_value=${redisState.availableValue} available_key=${redisState.availableKey}`
    );

    if (redisStockEnabled && !redisState.redisEnabled) {
      fail(
        `redis stock gate is expected to be enabled but runtime reports disabled for productId=${payload.productId}`
      );
    }

    if (rebuildRedisStock && !redisState.availableKeyExists) {
      fail(
        `redis stock key is expected after rebuild but is missing for productId=${payload.productId} key=${redisState.availableKey}`
      );
    }
  }

  const resetMetricsResponse = http.post(`${metricsUrl}/reset`, null, {
    tags: {
      name: 'stock_experiment_reset_metrics',
      experiment: __ENV.EXPERIMENT_NAME || 'stock-pessimistic-baseline',
    },
  });

  if (resetMetricsResponse.status !== 204) {
    fail(
      `failed to reset stock experiment metrics: status=${resetMetricsResponse.status} body=${resetMetricsResponse.body}`
    );
  }

  console.log(
    `stock_experiment_product_created product_id=${payload.productId} initial_stock=${initialStock} executor=${executor} attempts=${attempts} vus=${resolveScenarioVus()} redis_stock_enabled=${redisStockEnabled} rebuild_redis_stock=${rebuildRedisStock}`
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

  const experimentStockMetrics = fetchExperimentStockMetrics();

  if (experimentStockMetrics) {
    stockPressureRedisPreRejected.add(experimentStockMetrics.redisPreRejectedCount);
    stockPressureDbEntry.add(experimentStockMetrics.dbEntryCount);
    stockPressureDbSucceeded.add(experimentStockMetrics.dbSucceededCount);
    stockPressureDbRejected.add(experimentStockMetrics.dbRejectedCount);
    stockPressureDbFailed.add(experimentStockMetrics.dbFailedCount);
    stockPressureDbActiveMax.add(experimentStockMetrics.dbActiveMax);
    stockPressureDbDurationAvg.add(experimentStockMetrics.dbDurationAvgMs);
    stockPressureDbDurationMax.add(experimentStockMetrics.dbDurationMaxMs);
    stockPressureRedisCompensation.add(experimentStockMetrics.redisCompensationCount);
    stockPressureRedisReserved.add(experimentStockMetrics.redisReservedCount);
    stockPressureRedisAlreadyReserved.add(experimentStockMetrics.redisAlreadyReservedCount);
    stockPressureRedisOutOfStock.add(experimentStockMetrics.redisOutOfStockCount);
    stockPressureRedisStockKeyMissing.add(experimentStockMetrics.redisStockKeyMissingCount);
    stockPressureRedisInvalidArgument.add(experimentStockMetrics.redisInvalidArgumentCount);
    stockPressureRedisDisabled.add(experimentStockMetrics.redisDisabledCount);
    stockPressureRedisUnavailable.add(experimentStockMetrics.redisUnavailableCount);

    console.log(
      `stock_experiment_metrics redis_pre_rejected=${experimentStockMetrics.redisPreRejectedCount} db_entry=${experimentStockMetrics.dbEntryCount} db_succeeded=${experimentStockMetrics.dbSucceededCount} db_rejected=${experimentStockMetrics.dbRejectedCount} db_failed=${experimentStockMetrics.dbFailedCount} db_active_max=${experimentStockMetrics.dbActiveMax} db_duration_avg_ms=${formatNumber(experimentStockMetrics.dbDurationAvgMs)} db_duration_max_ms=${formatNumber(experimentStockMetrics.dbDurationMaxMs)} redis_compensation=${experimentStockMetrics.redisCompensationCount} redis_reserved=${experimentStockMetrics.redisReservedCount} redis_already_reserved=${experimentStockMetrics.redisAlreadyReservedCount} redis_out_of_stock=${experimentStockMetrics.redisOutOfStockCount} redis_stock_key_missing=${experimentStockMetrics.redisStockKeyMissingCount} redis_invalid_argument=${experimentStockMetrics.redisInvalidArgumentCount} redis_disabled=${experimentStockMetrics.redisDisabledCount} redis_unavailable=${experimentStockMetrics.redisUnavailableCount}`
    );
  }
}

export function handleSummary(data) {
  const summaryPath =
    __ENV.SUMMARY_PATH || `/results/stock-pessimistic-baseline-${attempts}-${experimentRunId}.json`;
  const metricsSummaryPath = summaryPath.replace(/\.json$/, '-stock-metrics.json');
  const stockMetricsSummary = {
    redisPreRejectedCount: formatGaugeValue(data.metrics.stock_pressure_redis_pre_rejected_count),
    dbEntryCount: formatGaugeValue(data.metrics.stock_pressure_db_entry_count),
    dbSucceededCount: formatGaugeValue(data.metrics.stock_pressure_db_succeeded_count),
    dbRejectedCount: formatGaugeValue(data.metrics.stock_pressure_db_rejected_count),
    dbFailedCount: formatGaugeValue(data.metrics.stock_pressure_db_failed_count),
    dbActiveMax: formatGaugeValue(data.metrics.stock_pressure_db_active_max),
    dbDurationAvgMs: formatGaugeValue(data.metrics.stock_pressure_db_duration_avg_ms),
    dbDurationMaxMs: formatGaugeValue(data.metrics.stock_pressure_db_duration_max_ms),
    redisCompensationCount: formatGaugeValue(data.metrics.stock_pressure_redis_compensation_count),
    redisReservedCount: formatGaugeValue(data.metrics.stock_pressure_redis_reserved_count),
    redisAlreadyReservedCount: formatGaugeValue(data.metrics.stock_pressure_redis_already_reserved_count),
    redisOutOfStockCount: formatGaugeValue(data.metrics.stock_pressure_redis_out_of_stock_count),
    redisStockKeyMissingCount: formatGaugeValue(data.metrics.stock_pressure_redis_stock_key_missing_count),
    redisInvalidArgumentCount: formatGaugeValue(data.metrics.stock_pressure_redis_invalid_argument_count),
    redisDisabledCount: formatGaugeValue(data.metrics.stock_pressure_redis_disabled_count),
    redisUnavailableCount: formatGaugeValue(data.metrics.stock_pressure_redis_unavailable_count),
  };
  const dbBypassedByRedis = stockMetricsSummary.redisPreRejectedCount;
  const dbEntryCount = stockMetricsSummary.dbEntryCount;
  const dbEntryRate = attempts > 0 ? (dbEntryCount / attempts) * 100 : 0;

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
      `redis_stock_enabled=${redisStockEnabled}`,
      `rebuild_redis_stock=${rebuildRedisStock}`,
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
      `stock_pressure_redis_pre_rejected_count=${dbBypassedByRedis}`,
      `stock_pressure_db_entry_count=${dbEntryCount}`,
      `stock_pressure_db_entry_rate=${formatNumber(dbEntryRate)}%`,
      `stock_pressure_db_succeeded_count=${stockMetricsSummary.dbSucceededCount}`,
      `stock_pressure_db_rejected_count=${stockMetricsSummary.dbRejectedCount}`,
      `stock_pressure_db_failed_count=${stockMetricsSummary.dbFailedCount}`,
      `stock_pressure_db_active_max=${stockMetricsSummary.dbActiveMax}`,
      `stock_pressure_db_duration_avg_ms=${formatNumber(stockMetricsSummary.dbDurationAvgMs)}`,
      `stock_pressure_db_duration_max_ms=${formatNumber(stockMetricsSummary.dbDurationMaxMs)}`,
      `stock_pressure_redis_compensation_count=${stockMetricsSummary.redisCompensationCount}`,
      `stock_pressure_redis_reserved_count=${stockMetricsSummary.redisReservedCount}`,
      `stock_pressure_redis_already_reserved_count=${stockMetricsSummary.redisAlreadyReservedCount}`,
      `stock_pressure_redis_out_of_stock_count=${stockMetricsSummary.redisOutOfStockCount}`,
      `stock_pressure_redis_stock_key_missing_count=${stockMetricsSummary.redisStockKeyMissingCount}`,
      `stock_pressure_redis_invalid_argument_count=${stockMetricsSummary.redisInvalidArgumentCount}`,
      `stock_pressure_redis_disabled_count=${stockMetricsSummary.redisDisabledCount}`,
      `stock_pressure_redis_unavailable_count=${stockMetricsSummary.redisUnavailableCount}`,
      '',
    ].join('\n'),
    [summaryPath]: JSON.stringify(data, null, 2),
    [metricsSummaryPath]: JSON.stringify(stockMetricsSummary, null, 2),
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

function parseBoolean(rawValue, defaultValue) {
  if (rawValue === undefined || rawValue === null || rawValue === '') {
    return defaultValue;
  }

  return `${rawValue}`.toLowerCase() === 'true';
}

function formatMetricValue(metric, key) {
  if (!metric || !metric.values || metric.values[key] === undefined) {
    return '0';
  }

  return Number(metric.values[key]).toFixed(2);
}

function formatGaugeValue(metric) {
  if (!metric || !metric.values || metric.values.value === undefined) {
    return 0;
  }

  return Number(metric.values.value);
}

function fetchExperimentStockMetrics() {
  const response = http.get(metricsUrl, {
    tags: {
      name: 'stock_experiment_metrics',
      experiment: __ENV.EXPERIMENT_NAME || 'stock-pessimistic-baseline',
    },
  });

  if (response.status !== 200) {
    console.warn(`failed to fetch stock experiment metrics: status=${response.status} body=${response.body}`);
    return null;
  }

  const payload = parseJson(response);
  if (!payload) {
    console.warn(`stock experiment metrics response is invalid json: body=${response.body}`);
    return null;
  }

  return payload;
}

function fetchProductRedisState(productId) {
  const response = http.get(`${baseUrl}/api/v1/experiments/stock/products/${productId}/redis`, {
    tags: {
      name: 'stock_experiment_redis_state',
      experiment: __ENV.EXPERIMENT_NAME || 'stock-pessimistic-baseline',
    },
  });

  if (response.status !== 200) {
    console.warn(`failed to fetch stock redis state: status=${response.status} body=${response.body}`);
    return null;
  }

  const payload = parseJson(response);
  if (!payload) {
    console.warn(`stock redis state response is invalid json: body=${response.body}`);
    return null;
  }

  return payload;
}

function formatNumber(value) {
  return Number(value || 0).toFixed(2);
}
