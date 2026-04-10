import http from 'k6/http';
import { fail, sleep } from 'k6';

export const options = {
  scenarios: {
    wait_http_ready: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: __ENV.WAIT_MAX_DURATION || '2m',
    },
  },
};

export default function () {
  const url = __ENV.WAIT_URL;
  if (!url) {
    fail('WAIT_URL is required.');
  }

  const maxAttempts = parsePositiveInteger(__ENV.WAIT_MAX_ATTEMPTS, 30);
  const sleepSeconds = parsePositiveNumber(__ENV.WAIT_SLEEP_SECONDS, 2);
  const stableSuccesses = parsePositiveInteger(__ENV.WAIT_STABLE_SUCCESSES, 3);
  const expectedStatuses = parseExpectedStatuses(__ENV.WAIT_EXPECTED_STATUSES || '200');
  let consecutiveSuccesses = 0;

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const response = http.get(url, {
      tags: {
        name: 'wait_http_ready',
      },
    });

    if (response && expectedStatuses.includes(response.status)) {
      consecutiveSuccesses += 1;
      console.log(
        `http_ready=true url=${url} status=${response.status} consecutive_successes=${consecutiveSuccesses}`
      );

      if (consecutiveSuccesses >= stableSuccesses) {
        return;
      }
    } else {
      consecutiveSuccesses = 0;
      console.warn(
        `http_ready=false attempt=${attempt} url=${url} status=${response && response.status} error=${response && response.error}`
      );
    }

    if (attempt < maxAttempts) {
      sleep(sleepSeconds);
    }
  }

  fail(`HTTP endpoint did not become ready: url=${url}`);
}

function parseExpectedStatuses(rawValue) {
  return rawValue
    .split(',')
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isFinite(value));
}

function parsePositiveInteger(rawValue, defaultValue) {
  const parsed = Number(rawValue || defaultValue);

  if (!Number.isFinite(parsed) || parsed <= 0) {
    return defaultValue;
  }

  return Math.floor(parsed);
}

function parsePositiveNumber(rawValue, defaultValue) {
  const parsed = Number(rawValue || defaultValue);

  if (!Number.isFinite(parsed) || parsed <= 0) {
    return defaultValue;
  }

  return parsed;
}
