import test from 'node:test';
import assert from 'node:assert/strict';
import { cleanupGeneratedKeys, runGeneratedWorkload } from './workloadRunner.js';

function makeCache() {
  const entries = new Map();
  return {
    entries,
    async set(key, value) { entries.set(key, value); return { key }; },
    async read(key) {
      if (!entries.has(key)) return { key, hit: false };
      return { key, hit: true, value: entries.get(key) };
    },
    async exists(key) { return { key, exists: entries.has(key) }; },
    async update(key, value) {
      assert.ok(entries.has(key), `UPDATE must target an existing key: ${key}`);
      entries.set(key, value);
      return { key, updated: true };
    },
    async remove(key) {
      assert.ok(entries.has(key), `DELETE must target an existing key: ${key}`);
      entries.delete(key);
      return { key, deleted: true };
    },
  };
}

const clockOptions = () => {
  let time = 0;
  return { now: () => time, wait: async (ms) => { time += ms; } };
};

const config = (overrides = {}) => ({
  cache: makeCache(), prefix: 'run-a', count: 60, rate: 10, duration: 10,
  distribution: { GET: 70, SET: 15, UPDATE: 10, DELETE: 5 },
  random: () => 0.25, ...overrides,
});

test('intentional missing GETs are measured as misses and do not end the workload', async () => {
  const cache = makeCache();
  cache.read = async (key) => {
    if (key.includes('-missing-')) throw { response: { status: 404, data: { message: `Key not found: ${key}` } } };
    return { key, hit: true };
  };
  const result = await runGeneratedWorkload({
    ...config({ cache, distribution: { GET: 100, SET: 0, UPDATE: 0, DELETE: 0 }, random: () => 0.99 }),
    ...clockOptions(), wait: async () => {},
  });

  assert.equal(result.attempted, 60);
  assert.equal(result.successful, 60);
  assert.equal(result.failed, 0);
  assert.equal(result.misses, 60);
  assert.equal(result.seedWritesSucceeded, 4);
  assert.equal(result.operationCounts.GET.attempted, 60);
});

test('actual hit and miss responses are separately counted', async () => {
  const cache = makeCache();
  let randomCalls = 0;
  const values = [0, 0, 0.99];
  const result = await runGeneratedWorkload({
    ...config({ cache, count: 2, rate: 2, duration: 1, distribution: { GET: 100, SET: 0, UPDATE: 0, DELETE: 0 }, random: () => values[randomCalls++ % values.length] }),
    ...clockOptions(), wait: async () => {},
  });
  assert.equal(result.hits, 1);
  assert.equal(result.misses, 1);
  assert.equal(result.successful, 2);
});

test('requested operation count completes and reports seed writes separately', async () => {
  const cache = makeCache();
  const result = await runGeneratedWorkload({
    ...config({ cache, distribution: { GET: 100, SET: 0, UPDATE: 0, DELETE: 0 }, random: () => 0 }),
    ...clockOptions(), wait: async () => {},
  });
  assert.equal(result.requested, 60);
  assert.equal(result.attempted, 60);
  assert.equal(result.seedWritesAttempted, 4);
  assert.equal(result.seedWritesSucceeded, 4);
});

test('configured operation weights produce the expected 60-request mix', async () => {
  const cache = makeCache();
  const result = await runGeneratedWorkload({
    ...config({ cache, random: () => 0.42 }),
    ...clockOptions(), wait: async () => {},
  });
  assert.equal(result.attempted, 60);
  assert.deepEqual(Object.fromEntries(Object.entries(result.operationCounts).map(([op, stats]) => [op, stats.attempted])), {
    GET: 42, SET: 9, UPDATE: 6, DELETE: 3,
  });
  assert.equal(result.failed, 0);
});

test('UPDATE and DELETE target verified generated entries; cleanup never touches other keys', async () => {
  const cache = makeCache();
  cache.entries.set('user-owned-key', 'untouched');
  const result = await runGeneratedWorkload({
    ...config({ cache, count: 8, rate: 2, duration: 4, distribution: { GET: 0, SET: 0, UPDATE: 0, DELETE: 100 }, random: () => 0.99 }),
    ...clockOptions(), wait: async () => {},
  });

  assert.equal(result.attempted, 8);
  assert.equal(result.operationCounts.DELETE.succeeded, 8);
  assert.equal(result.failed, 0);
  assert.equal(result.preflightChecks, 4, 'the four initial seed targets are checked; replacements are verified by their successful SET response');
  assert.equal(cache.entries.get('user-owned-key'), 'untouched');
  assert.equal(result.generatedKeys.length, 0);

  cache.entries.set('run-a-remains', 'generated');
  const cleanup = await cleanupGeneratedKeys(cache, ['run-a-remains']);
  assert.deepEqual(cleanup.remaining, []);
  assert.equal(cache.entries.get('user-owned-key'), 'untouched');
});

test('concurrent workloads use isolated keys and both complete', async () => {
  const cache = makeCache();
  const options = clockOptions();
  const makeRun = (prefix) => runGeneratedWorkload({
    ...config({ cache, prefix, count: 20, rate: 10, duration: 2, distribution: { GET: 100, SET: 0, UPDATE: 0, DELETE: 0 }, random: () => 0 }),
    ...options, wait: async () => {},
  });
  const [first, second] = await Promise.all([makeRun('concurrent-a'), makeRun('concurrent-b')]);
  assert.equal(first.attempted, 20);
  assert.equal(second.attempted, 20);
  assert.ok([...cache.entries.keys()].every((key) => key.startsWith('concurrent-a-') || key.startsWith('concurrent-b-')));
  assert.ok([...cache.entries.keys()].some((key) => key.startsWith('concurrent-a-')));
  assert.ok([...cache.entries.keys()].some((key) => key.startsWith('concurrent-b-')));
});

test('unexpected request failures are counted and later scheduled operations continue', async () => {
  const cache = makeCache();
  let reads = 0;
  cache.read = async (key) => {
    reads++;
    if (reads === 1) throw { response: { status: 503, data: { message: 'temporarily unavailable' } } };
    return { key, hit: false };
  };
  const result = await runGeneratedWorkload({
    ...config({ cache, count: 3, rate: 3, duration: 1, distribution: { GET: 100, SET: 0, UPDATE: 0, DELETE: 0 }, random: () => 0.99 }),
    ...clockOptions(), wait: async () => {},
  });
  assert.equal(result.attempted, 3);
  assert.equal(result.failed, 1);
  assert.equal(result.successful, 2);
  assert.equal(result.operationCounts.GET.failed, 1);
});
