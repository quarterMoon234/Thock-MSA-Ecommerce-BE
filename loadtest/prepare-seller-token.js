import http from 'k6/http';
import { fail, sleep } from 'k6';

const runId = __ENV.RUN_ID || `${Date.now()}`;

export const options = {
  scenarios: {
    prepare_seller_token: {
      executor: 'shared-iterations',
      iterations: 1,
      vus: 1,
      maxDuration: '2m',
      gracefulStop: '0s',
    },
  },
};

export default function () {
  const baseUrl = (__ENV.BASE_URL || 'http://api-gateway:8080').replace(/\/$/, '');
  const password = __ENV.PASSWORD || 'password123!';
  const sellerEmail = `seller-k6-${runId}@example.com`;
  const sellerName = `seller-k6-${runId}`;

  signUp(baseUrl, sellerEmail, sellerName, password);
  sleep(0.5);

  const memberToken = login(baseUrl, sellerEmail, password);
  promoteSeller(baseUrl, memberToken, sellerName);
  sleep(0.5);

  const sellerToken = login(baseUrl, sellerEmail, password);
  console.log(`__SELLER_ACCESS_TOKEN__=${sellerToken}`);
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
