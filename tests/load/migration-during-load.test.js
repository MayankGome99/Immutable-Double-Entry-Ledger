import http from 'k6/http';
import { check, sleep } from 'k6';

// Run with:
//   k6 run tests/load/migration-during-load.test.js
// While this 60s load test runs with 20 concurrent VUs, apply an online DDL migration
// (e.g., ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS audit_tag VARCHAR(50);)
// and confirm zero 5xx errors and <1s p95 latency.

export const options = {
    vus: 20,
    duration: '60s',
    thresholds: {
        http_req_failed: ['rate<0.01'],     // <1% errors during live migration
        http_req_duration: ['p(95)<1000'],  // 95% of requests under 1 second
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
    const res = http.get(`${BASE_URL}/api/v1/ledger/accounts`);
    check(res, { 'setup: accounts fetched (200)': (r) => r.status === 200 });
    const accounts = res.json();
    const inrAccounts = accounts.filter((a) => a.currency === 'INR');
    return {
        fromAccountId: inrAccounts[0] ? inrAccounts[0].id : null,
        toAccountId: inrAccounts[1] ? inrAccounts[1].id : null,
    };
}

export default function (data) {
    // 1. Health & Request-ID check during migration
    const healthRes = http.get(`${BASE_URL}/api/v1/ledger/health`);
    check(healthRes, {
        'health status 200': (r) => r.status === 200,
        'health has X-Request-Id': (r) => Boolean(r.headers['X-Request-Id']),
    });

    // 2. Concurrent P2P transfer load (20 VUs)
    if (data.fromAccountId && data.toAccountId) {
        const payload = JSON.stringify({
            fromAccountId: data.fromAccountId,
            toAccountId: data.toAccountId,
            amount: 10.00,
        });
        const params = {
            headers: {
                'Content-Type': 'application/json',
                'X-Idempotency-Key': `k6-mig-${__VU}-${__ITER}-${Date.now()}`,
            },
        };
        const txRes = http.post(`${BASE_URL}/api/v1/ledger/transfer`, payload, params);
        check(txRes, {
            'transfer no 5xx during migration': (r) => r.status < 500,
            'transfer accepted or guarded (200/422)': (r) => r.status === 200 || r.status === 422,
        });
    }

    // 3. Per-currency Trial Balance read check
    const tbRes = http.get(`${BASE_URL}/api/v1/ledger/trial-balance`);
    check(tbRes, {
        'trial-balance status 200': (r) => r.status === 200,
    });

    sleep(0.2);
}