import http from 'k6/http';
import { check, Rate, Counter } from 'k6';
import { b64encode } from 'k6/encoding';

const accepted = new Counter('reservation_success');
const rejected = new Counter('reservation_rejected');
const unexpected = new Rate('reservation_unexpected_response');
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const productIds = (__ENV.PRODUCT_IDS || __ENV.PRODUCT_ID || '').split(',').map((id) => id.trim()).filter(Boolean);
const quantity = Number(__ENV.REQUEST_QUANTITY || 1);
const credentials = `${__ENV.DEV_USER || 'demo'}:${__ENV.DEV_PASSWORD || ''}`;
if (productIds.length === 0) throw new Error('Set PRODUCT_ID or PRODUCT_IDS to stocked product UUIDs.');
if (!__ENV.DEV_PASSWORD) throw new Error('Set DEV_PASSWORD for the development API account.');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  scenarios: {
    flash_sale: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.REQUEST_RATE || 100),
      timeUnit: '1s',
      duration: __ENV.TEST_DURATION || '2m',
      preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 500),
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2500'],
    reservation_unexpected_response: ['rate<0.01'],
  },
};

http.setResponseCallback(http.expectedStatuses(201, 409));

export default function () {
  const productId = productIds[Math.floor(Math.random() * productIds.length)];
  const idempotencyKey = `${__VU}-${__ITER}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const response = http.post(`${baseUrl}/api/v1/reservations`, JSON.stringify({ productId, quantity }), {
    headers: {
      Authorization: `Basic ${b64encode(credentials)}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-Correlation-ID': `k6-${__VU}-${__ITER}`,
    },
    tags: { endpoint: 'reservation_create' },
  });
  const expected = check(response, { 'reservation accepted or stock rejected': (r) => r.status === 201 || (r.status === 409 && r.body.includes('INSUFFICIENT_STOCK')) });
  if (response.status === 201) accepted.add(1);
  else if (response.status === 409 && response.body.includes('INSUFFICIENT_STOCK')) rejected.add(1);
  unexpected.add(!expected);
}

export function handleSummary(data) {
  const strategy = __ENV.INVENTORY_RESERVATION_STRATEGY || 'conditional';
  return { [`load-tests/summary-${strategy}.json`]: JSON.stringify(data, null, 2) };
}
