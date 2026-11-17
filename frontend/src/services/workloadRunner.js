const OPERATIONS = ['GET', 'SET', 'UPDATE', 'DELETE'];

const expectedMissingKey = (error) => error?.response?.status === 404
  && String(error?.response?.data?.message || error?.message || '').startsWith('Key not found:');

const choose = (items, random) => items[Math.floor(random() * items.length)];
const defaultNow = () => globalThis.performance?.now?.() ?? Date.now();
const defaultWait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function emptyOperationCounts() {
  return Object.fromEntries(OPERATIONS.map((operation) => [operation, { attempted: 0, succeeded: 0, failed: 0 }]));
}

function createOperationSchedule(count, weights, weightTotal, random) {
  const quotas = OPERATIONS.map((operation, order) => {
    const exact = count * weights[operation] / weightTotal;
    return { operation, order, amount: Math.floor(exact), remainder: exact - Math.floor(exact) };
  });
  let unassigned = count - quotas.reduce((sum, item) => sum + item.amount, 0);
  for (const item of [...quotas].sort((a, b) => b.remainder - a.remainder || a.order - b.order).slice(0, unassigned)) item.amount++;
  const schedule = quotas.flatMap(({ operation, amount }) => Array(amount).fill(operation));
  for (let index = schedule.length - 1; index > 0 && quotas.filter((item) => item.amount > 0).length > 1; index--) {
    const other = Math.floor(Math.min(0.999999999, Math.max(0, random())) * (index + 1));
    [schedule[index], schedule[other]] = [schedule[other], schedule[index]];
  }
  return schedule;
}

/**
 * Runs real cache API calls for one isolated namespace. Seed writes and existence
 * checks are setup traffic and are deliberately excluded from the requested count.
 */
export async function runGeneratedWorkload({
  cache,
  prefix,
  count,
  rate,
  duration,
  distribution,
  shouldStop = () => false,
  onProgress = () => {},
  onGeneratedKeysChange = () => {},
  random = Math.random,
  now = defaultNow,
  wait = defaultWait,
  ttlSeconds = 600,
}) {
  const requested = Number(count);
  const requestsPerSecond = Number(rate);
  const runDuration = Number(duration);
  const weights = Object.fromEntries(OPERATIONS.map((op) => [op, Number(distribution[op] || 0)]));
  const weightTotal = OPERATIONS.reduce((sum, op) => sum + weights[op], 0);
  if (!Number.isInteger(requested) || requested < 1 || requested > 500
      || !Number.isInteger(requestsPerSecond) || requestsPerSecond < 1 || requestsPerSecond > 50
      || !Number.isInteger(runDuration) || runDuration < 1 || runDuration > 300
      || requested > requestsPerSecond * runDuration || weightTotal <= 0
      || OPERATIONS.some((op) => !Number.isFinite(weights[op]) || weights[op] < 0 || weights[op] > 100)) {
    throw new Error('Invalid workload settings. Check request count, rate, duration and operation weights.');
  }
  const operationSchedule = createOperationSchedule(requested, weights, weightTotal, random);

  const owned = new Set();
  const active = new Set();
  const operationCounts = emptyOperationCounts();
  const errors = [];
  let sequence = 0;
  let seedSequence = 4;
  let seedWritesAttempted = 0;
  let seedWritesSucceeded = 0;
  let seedWritesFailed = 0;
  let preflightChecks = 0;
  let successful = 0;
  let failed = 0;
  let hits = 0;
  let misses = 0;
  let latencyTotalMs = 0;
  let latencySamples = 0;
  let attempted = 0;
  let stopped = false;

  const publishKeys = () => onGeneratedKeysChange([...owned]);
  const createSeed = async (key) => {
    seedWritesAttempted++;
    try {
      await cache.set(key, JSON.stringify({ generated: true, run: prefix, seed: true }), ttlSeconds);
      seedWritesSucceeded++;
      owned.add(key);
      active.add(key);
      publishKeys();
      return true;
    } catch (error) {
      seedWritesFailed++;
      errors.push({ operation: 'SEED', message: error?.response?.data?.message || error?.message || 'Seed write failed' });
      return false;
    }
  };

  // Initial keys are extra setup writes, distinct from the requested operations.
  const initialSeeds = Array.from({ length: 4 }, (_, i) => `${prefix}-seed-${i}`);
  const seedResults = await Promise.allSettled(initialSeeds.map((key) => createSeed(key)));
  if (!active.size && seedResults.every((result) => result.status === 'rejected' || !result.value)) {
    // GET and SET remain possible; UPDATE/DELETE will safely request a replacement seed if selected.
  }

  const ensureExistingTarget = async () => {
    // Verify the tracked candidate still exists before mutating it. This covers
    // expiry, eviction or another client deleting a generated key.
    for (const key of [...active]) {
      preflightChecks++;
      const state = await cache.exists(key);
      if (state?.exists) return key;
      active.delete(key);
      owned.delete(key);
      publishKeys();
    }

    const key = `${prefix}-seed-replacement-${seedSequence++}`;
    if (await createSeed(key)) return key;
    throw new Error('Could not create a verified generated key for UPDATE/DELETE; no unsafe mutation was sent.');
  };

  const startedAt = now();
  const intervalMs = 1000 / requestsPerSecond;
  for (let index = 0; index < requested; index++) {
    if (shouldStop()) { stopped = true; break; }

    // Pace request start times rather than adding a full interval after each
    // network call. Slow calls never cause a catch-up burst.
    const nextStart = startedAt + index * intervalMs;
    const delay = nextStart - now();
    if (delay > 0) await wait(delay);
    if (shouldStop()) { stopped = true; break; }

    const operation = operationSchedule[index];
    operationCounts[operation].attempted++;
    attempted++;
    const operationStarted = now();
    let result = null;
    let targetKey = null;

    try {
      if (operation === 'GET') {
        const keys = [...active];
        const useHit = keys.length > 0 && random() < 0.5;
        targetKey = useHit ? choose(keys, random) : `${prefix}-missing-${index}`;
        result = await cache.read(targetKey);
        if (result?.hit) hits++;
        else misses++;
        // A cache MISS is a successful cache request, matching backend gateway metrics.
      } else if (operation === 'SET') {
        targetKey = `${prefix}-entry-${++sequence}`;
        result = await cache.set(targetKey, JSON.stringify({ generated: true, run: prefix, operation, index }), ttlSeconds);
        owned.add(targetKey);
        active.add(targetKey);
        publishKeys();
      } else {
        targetKey = await ensureExistingTarget();
        if (operation === 'UPDATE') {
          result = await cache.update(targetKey, JSON.stringify({ generated: true, run: prefix, operation, index }), ttlSeconds);
        } else {
          result = await cache.remove(targetKey);
          if (result?.deleted === false) throw new Error('Cache API did not confirm deletion.');
          active.delete(targetKey);
          owned.delete(targetKey);
          publishKeys();
        }
      }
      operationCounts[operation].succeeded++;
      successful++;
    } catch (error) {
      if (operation === 'GET' && expectedMissingKey(error)) {
        misses++;
        operationCounts.GET.succeeded++;
        successful++;
      } else {
        operationCounts[operation].failed++;
        failed++;
        errors.push({ operation, key: targetKey, message: error?.response?.data?.message || error?.message || 'Request failed' });
        if (targetKey && expectedMissingKey(error)) {
          // The API confirms this generated key is gone; do not select or clean it again.
          active.delete(targetKey);
          owned.delete(targetKey);
          publishKeys();
        }
      }
    } finally {
      const elapsed = Math.max(0, now() - operationStarted);
      latencyTotalMs += elapsed;
      latencySamples++;
    }

    onProgress({
      attempted, requested, successful, failed, hits, misses,
      operationCounts: structuredClone(operationCounts),
      seedWritesAttempted, seedWritesSucceeded, seedWritesFailed, preflightChecks,
      averageLatencyMs: latencySamples ? latencyTotalMs / latencySamples : null,
    });
  }

  return {
    requested, attempted, successful, failed, hits, misses,
    operationCounts, seedWritesAttempted, seedWritesSucceeded, seedWritesFailed,
    preflightChecks, latencySamples,
    averageLatencyMs: latencySamples ? latencyTotalMs / latencySamples : null,
    stopped, errors: errors.slice(0, 10), generatedKeys: [...owned],
  };
}

/** Deletes only keys the caller recorded as successfully created by this workload. */
export async function cleanupGeneratedKeys(cache, keys) {
  const ownedKeys = [...new Set(keys)];
  const results = await Promise.allSettled(ownedKeys.map((key) => cache.remove(key)));
  const remaining = ownedKeys.filter((key, index) => {
    const result = results[index];
    if (result.status === 'fulfilled') return result.value?.deleted === false;
    return !expectedMissingKey(result.reason);
  });
  return { attempted: ownedKeys.length, removed: ownedKeys.length - remaining.length, remaining };
}
