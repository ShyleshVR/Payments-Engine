// PayFlow load test: merchants taking card payments through the API gateway, at a fixed arrival
// rate, against the SLOs in docs/LOAD_TEST.md (thresholds below). Run by scripts/load-test.sh as
// a Kubernetes Job; the scenario and rate come from the environment:
//   SCENARIO  smoke | step | soak | spike
//   RATE      payments per second (spike: the base rate)
//   PEAK      spike only: payments per second at the peak
//   DURATION  step and soak: how long to hold RATE (e.g. 3m, 30m)
//   MERCHANTS how many merchants share the load (each stays under the gateway's 20 req/s limit)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import exec from 'k6/execution';
import encoding from 'k6/encoding';

const BASE = __ENV.BASE_URL || 'http://api-gateway.payflow.svc';
const SCENARIO = __ENV.SCENARIO || 'smoke';
const RATE = Number(__ENV.RATE || 5);
const PEAK = Number(__ENV.PEAK || RATE * 2);
const DURATION = __ENV.DURATION || '3m';
const MERCHANTS = Number(__ENV.MERCHANTS || 50);
const TOKEN_VALID_MS = 15 * 60 * 1000; // what the authorization server issues
// Each VU acts as one merchant and renews that merchant's token at its own age between 8 and 12
// minutes. The gateway allows 20 unauthenticated requests/s per IP, token requests included, and
// every VU shares the generator's IP: a token per VU per merchant (hundreds of VUs x 50 merchants)
// could not be renewed within a token's lifetime.
const RENEW_AFTER_MS = (8 + Math.random() * 4) * 60 * 1000;

// Payment created -> SUCCESS seen by the client (2% of iterations poll for it, every 500 ms: gentle
// enough to stay well inside the gateway's per-merchant rate limit even when payments slow down).
const e2e = new Trend('e2e_payment_ms', true);
const e2eTimeouts = new Counter('e2e_payment_timeouts');
// Iterations that threw (e.g. no token): they create no payment, so the run would quietly carry
// less load than its rate says. Any at all fails the run.
const iterationErrors = new Counter('iteration_errors');

function scenarios() {
    const vus = { preAllocatedVUs: Math.max(20, RATE * 2), maxVUs: Math.max(100, Math.max(RATE, PEAK) * 8) };
    switch (SCENARIO) {
        case 'smoke':
            return { load: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: '1m', ...vus } };
        case 'step':
        case 'soak':
            return { load: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION, ...vus } };
        case 'spike':
            return {
                load: {
                    executor: 'ramping-arrival-rate', startRate: RATE, timeUnit: '1s', ...vus,
                    stages: [
                        { target: RATE, duration: '1m' },
                        { target: PEAK, duration: '5s' },
                        { target: PEAK, duration: '30s' },
                        { target: RATE, duration: '5s' },
                        { target: RATE, duration: '3m' },
                    ],
                },
            };
        default:
            throw new Error('Unknown SCENARIO ' + SCENARIO);
    }
}

export const options = {
    scenarios: scenarios(),
    // The SLOs. A run reports against them; the driver (scripts/load-ceiling.sh) decides.
    thresholds: {
        'http_req_duration{name:create_payment}': ['p(95)<150', 'p(99)<300'],
        'http_req_duration{name:get_payment}': ['p(99)<200'],
        'http_req_duration{name:balance}': ['p(99)<200'],
        'http_req_failed{phase:load}': ['rate<0.001'],
        'checks': ['rate>0.999'],
        'e2e_payment_ms': ['p(95)<3000'],
        'dropped_iterations': ['count<1'],
        'iteration_errors': ['count<1'],
    },
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    setupTimeout: '5m',
    teardownTimeout: '1m',
};

function token(basic) {
    const res = http.post(`${BASE}/oauth2/token`, { grant_type: 'client_credentials' }, {
        headers: { Authorization: 'Basic ' + encoding.b64encode(basic) },
        tags: { name: 'token', phase: 'setup' },
    });
    if (res.status !== 200) {
        throw new Error(`token request failed: ${res.status} ${res.body}`);
    }
    return res.json('access_token');
}

// Merchants are created through the operator API, so a run never depends on earlier data.
export function setup() {
    const admin = token('payflow-admin:' + __ENV.ADMIN_SECRET);
    const run = Date.now().toString(36);
    const merchants = [];
    for (let i = 0; i < MERCHANTS; i++) {
        const res = http.post(`${BASE}/api/v1/merchants`,
            JSON.stringify({ name: `Load ${run} ${i}`, email: `load-${run}-${i}@example.test` }), {
                headers: { Authorization: 'Bearer ' + admin, 'Content-Type': 'application/json' },
                tags: { name: 'create_merchant', phase: 'setup' },
            });
        if (res.status !== 201) {
            throw new Error(`merchant creation failed: ${res.status} ${res.body}`);
        }
        const basic = `${res.json('credential.clientId')}:${res.json('credential.clientSecret')}`;
        merchants.push({ basic, token: token(basic) });
    }
    return { merchants, issuedAt: Date.now() };
}

// Per-VU token cache: setup's tokens expire during a soak, so each VU renews its merchant's. A
// failed renewal keeps the current token while it is still valid and tries again a little later.
const tokens = {};

function merchantToken(data, index) {
    const now = Date.now();
    const cached = tokens[index] || (tokens[index] = { token: data.merchants[index].token, at: data.issuedAt, retryAt: 0 });
    if (now - cached.at < RENEW_AFTER_MS) {
        return cached.token;
    }
    const stillValid = now - cached.at < TOKEN_VALID_MS - 60 * 1000;
    if (stillValid && now < cached.retryAt) {
        return cached.token;
    }
    try {
        cached.token = token(data.merchants[index].basic);
        cached.at = now;
    } catch (e) {
        if (!stillValid) {
            throw e;
        }
        cached.retryAt = now + 10000 + Math.random() * 20000;
    }
    return cached.token;
}

function params(bearer, name, extraHeaders) {
    return {
        headers: Object.assign({ Authorization: 'Bearer ' + bearer, 'Content-Type': 'application/json' }, extraHeaders || {}),
        tags: { name, phase: 'load' },
    };
}

export default function (data) {
    iterationErrors.add(0); // so the threshold is evaluated even when nothing fails
    try {
        iteration(data);
    } catch (e) {
        iterationErrors.add(1);
        throw e;
    }
}

function iteration(data) {
    // k6 hands iterations to its VUs in turn, so VU-per-merchant still spreads load evenly
    const index = (exec.vu.idInTest - 1) % data.merchants.length;
    const bearer = merchantToken(data, index);
    const amount = (1 + Math.floor(Math.random() * 9900) / 100).toFixed(2);
    const key = `load-${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${Date.now()}`;

    const started = Date.now();
    const created = http.post(`${BASE}/api/v1/payments`,
        JSON.stringify({ amount, currency: 'USD', paymentMethod: 'pm_card_visa' }),
        params(bearer, 'create_payment', { 'Idempotency-Key': key }));
    if (!check(created, { 'payment created (201)': (r) => r.status === 201 })) {
        return;
    }
    const paymentId = created.json('paymentId');

    const read = http.get(`${BASE}/api/v1/payments/${paymentId}`, params(bearer, 'get_payment'));
    check(read, { 'payment read (200)': (r) => r.status === 200 });

    if (Math.random() < 0.10) {
        const balance = http.get(`${BASE}/api/v1/ledger/balance?currency=USD`, params(bearer, 'balance'));
        check(balance, { 'balance read (200)': (r) => r.status === 200 });
    }

    if (Math.random() < 0.02) {
        // follow this payment to the end of its saga (end-to-end latency)
        let status = null;
        while (Date.now() - started < 15000) {
            sleep(0.5);
            const poll = http.get(`${BASE}/api/v1/payments/${paymentId}`, params(bearer, 'poll_payment'));
            status = poll.status === 200 ? poll.json('status') : null;
            if (status === 'SUCCESS' || status === 'FAILED') {
                break;
            }
        }
        if (status === 'SUCCESS') {
            e2e.add(Date.now() - started);
            if (Math.random() < 0.5) {
                const refund = http.post(`${BASE}/api/v1/payments/${paymentId}/refund`, null, params(bearer, 'refund'));
                check(refund, { 'refund accepted (202)': (r) => r.status === 202 });
            }
        } else {
            e2eTimeouts.add(1);
        }
    }
}

function metric(data, name, stat) {
    const m = data.metrics[name];
    return m && m.values ? m.values[stat] : null;
}

// One JSON line between markers, for scripts/load-test.sh.
export function handleSummary(data) {
    const thresholds = {};
    for (const [name, m] of Object.entries(data.metrics)) {
        if (m.thresholds) {
            for (const [expr, result] of Object.entries(m.thresholds)) {
                thresholds[`${name} ${expr}`] = result.ok;
            }
        }
    }
    const summary = {
        scenario: SCENARIO, rate: RATE, peak: SCENARIO === 'spike' ? PEAK : null, duration: DURATION, merchants: MERCHANTS,
        passed: Object.values(thresholds).every((ok) => ok),
        thresholds,
        iterations: metric(data, 'iterations', 'count'),
        iterationRate: metric(data, 'iterations', 'rate'),
        droppedIterations: metric(data, 'dropped_iterations', 'count') || 0,
        httpRequests: metric(data, 'http_reqs', 'count'),
        httpRate: metric(data, 'http_reqs', 'rate'),
        failedRate: metric(data, 'http_req_failed{phase:load}', 'rate'),
        create: data.metrics['http_req_duration{name:create_payment}'] ? data.metrics['http_req_duration{name:create_payment}'].values : null,
        read: data.metrics['http_req_duration{name:get_payment}'] ? data.metrics['http_req_duration{name:get_payment}'].values : null,
        balance: data.metrics['http_req_duration{name:balance}'] ? data.metrics['http_req_duration{name:balance}'].values : null,
        e2e: data.metrics['e2e_payment_ms'] ? data.metrics['e2e_payment_ms'].values : null,
        e2eTimeouts: metric(data, 'e2e_payment_timeouts', 'count') || 0,
        iterationErrors: metric(data, 'iteration_errors', 'count') || 0,
        checks: metric(data, 'checks', 'rate'),
    };
    return { stdout: '\n===K6_SUMMARY_BEGIN===\n' + JSON.stringify(summary) + '\n===K6_SUMMARY_END===\n' };
}
