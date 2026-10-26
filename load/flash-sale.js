// Flash sale opening: a crowd joins the waiting room, polls until admitted, then
// tries to hold a ticket. Arrival rate is fixed, so a slow server cannot quietly
// lower the load (the coordinated omission problem with closed loop tools).
//
//   k6 run -e BASE_URL=http://localhost:8080 -e RATE=300 -e CAPACITY=1000 load/flash-sale.js
//
// Afterwards this must never return a row:
//   select * from inventory where sold > capacity;
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 200);
const CAPACITY = Number(__ENV.CAPACITY || 1000);

const held = new Counter('tickets_held');
const soldOut = new Counter('sold_out');
const shed = new Counter('shed_or_limited');

export const options = {
  scenarios: {
    crowd: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: RATE * 2,
      maxVUs: RATE * 10,
    },
  },
  thresholds: {
    'http_req_duration{name:event}': ['p(99)<250'],
    'http_req_duration{name:hold}': ['p(99)<500'],
    'http_req_failed{name:hold}': ['rate<0.01'],
  },
};

export function setup() {
  const res = http.post(`${BASE}/events`, JSON.stringify({ name: 'Load test', capacity: CAPACITY }),
    { headers: { 'Content-Type': 'application/json' } });
  return { eventId: res.json('id') };
}

export default function ({ eventId }) {
  const user = `vu-${__VU}-${__ITER}`;
  const headers = { 'X-User-Id': user, 'Content-Type': 'application/json' };

  http.get(`${BASE}/events/${eventId}`, { headers, tags: { name: 'event' } });
  http.post(`${BASE}/events/${eventId}/queue`, null, { headers, tags: { name: 'join' } });

  let token = null;
  for (let i = 0; i < 30 && !token; i++) {
    const me = http.get(`${BASE}/events/${eventId}/queue/me`, { headers, tags: { name: 'poll' } });
    if (me.status === 200 && me.json('state') === 'ADMITTED') {
      token = me.json('admissionToken');
    } else {
      sleep(1);
    }
  }
  if (!token) return;

  const res = http.post(`${BASE}/events/${eventId}/holds`, JSON.stringify({ quantity: 1 }),
    { headers: { ...headers, 'X-Admission-Token': token }, tags: { name: 'hold' },
      responseCallback: http.expectedStatuses(201, 409, 429, 503) });
  if (res.status === 201) held.add(1);
  else if (res.status === 409) soldOut.add(1);
  else shed.add(1);
  check(res, { 'no server errors': (r) => r.status < 500 || r.status === 503 });
}
