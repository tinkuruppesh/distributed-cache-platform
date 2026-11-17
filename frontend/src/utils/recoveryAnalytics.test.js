import assert from 'node:assert/strict';
import test from 'node:test';
import { averageCompletedMeasurement, overallRequestSuccess } from './recoveryAnalytics.js';

test('overall success uses measured successes and request totals', () => {
  assert.deepEqual(overallRequestSuccess([{ requestsTotal: 100, requestsSucceeded: 95 }]), {
    value: 95, succeeded: 95, requests: 100,
  });
});

test('zero or missing request counters do not become a 100 percent success rate', () => {
  assert.equal(overallRequestSuccess([{ requestsTotal: 0, requestsSucceeded: 0 }]), null);
  assert.equal(overallRequestSuccess([{ requestsTotal: 100, requestsSucceeded: null }]), null);
  assert.equal(overallRequestSuccess([{ requestsTotal: null, requestsSucceeded: 95 }]), null);
});

test('request success is weighted by request volume across simulations', () => {
  const result = overallRequestSuccess([
    { requestsTotal: 100, requestsSucceeded: 90 },
    { requestsTotal: 1000, requestsSucceeded: 990 },
  ]);
  assert.equal(result.requests, 1100);
  assert.equal(result.succeeded, 1080);
  assert.equal(result.value, (1080 / 1100) * 100);
});

test('averages exclude unavailable measurements while preserving measured zero', () => {
  const rows = [
    { outcome: 'COMPLETED', detectionMs: 10, failoverMs: null, recoveryMs: 0 },
    { outcome: 'COMPLETED', detectionMs: null, failoverMs: null, recoveryMs: null },
    { outcome: 'FAILED', detectionMs: 99, failoverMs: 99, recoveryMs: 99 },
  ];
  assert.deepEqual(averageCompletedMeasurement(rows, 'detectionMs'), { value: 10, count: 1 });
  assert.deepEqual(averageCompletedMeasurement(rows, 'failoverMs'), { value: null, count: 0 });
  assert.deepEqual(averageCompletedMeasurement(rows, 'recoveryMs'), { value: 0, count: 1 });
});
