import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    scenarios: {
        concurrent_ledger_traffic: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '15s', target: 25 },
                { duration: '30s', target: 50 },
                { duration: '15s', target: 0 },
            ],
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<200', 'p(99)<500'],
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';

export default function () {
    // 1. Health & partition check
    const healthRes = http.get(`${BASE_URL}/health`);
    check(healthRes, {
        'health is 200': (r) => r.status === 200,
        'has X-Request-Id': (r) => r.headers['X-Request-Id'] !== undefined,
    });

    // 2. Post balanced deposit transaction
    const txnRes = http.post(
        `${BASE_URL}/transactions/typed?type=DEPOSIT_BANK&amount=100.00&createdBy=K6_VU_${__VU}`
    );
    check(txnRes, {
        'deposit posted 200': (r) => r.status === 200,
    });

    // 3. Verify Trial Balance stays balanced under concurrent load
    const tbRes = http.get(`${BASE_URL}/trial-balance?currency=INR`);
    check(tbRes, {
        'trial balance 200': (r) => r.status === 200,
        'trial balance is balanced': (r) => r.json('balanced') === true,
    });

    sleep(0.2);
}