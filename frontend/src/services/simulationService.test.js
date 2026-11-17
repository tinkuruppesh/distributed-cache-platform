import assert from 'node:assert/strict';
import test from 'node:test';
import api from './api.js';
import { simulationService } from './simulationService.js';

test('Recovery Analytics sends filters and paging to the backend endpoint', async () => {
  globalThis.sessionStorage = { getItem: () => null };
  let request;
  const previousAdapter = api.defaults.adapter;
  api.defaults.adapter = async (config) => {
    request = config;
    return { data: { items: [], summary: { totalSimulations: 0 } }, status: 200, headers: {}, config };
  };
  try {
    const response = await simulationService.analytics({
      q: 'node-1', outcome: 'COMPLETED', mode: 'CRASH', nodeId: 'node-1',
      from: 1_700_000_000_000, to: 1_700_086_400_000, sortBy: 'recovery', direction: 'asc', page: 2, size: 10,
    });
    assert.equal(response.summary.totalSimulations, 0);
    assert.equal(request.url, '/simulations/analytics');
    assert.deepEqual(request.params, {
      q: 'node-1', outcome: 'COMPLETED', mode: 'CRASH', nodeId: 'node-1',
      from: 1_700_000_000_000, to: 1_700_086_400_000, sortBy: 'recovery', direction: 'asc', page: 2, size: 10,
    });
  } finally {
    api.defaults.adapter = previousAdapter;
    delete globalThis.sessionStorage;
  }
});
