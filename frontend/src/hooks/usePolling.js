import { useCallback, useEffect, useRef, useState } from 'react';
import { errorMessage } from '../services/api';

/**
 * Calls `fetcher` immediately and then every `intervalMs` (0 = once). The dashboard uses polling because it is simple,
 * robust behind proxies, and fast enough for a 2-5 second refresh; WebSocket/SSE would be a future optimisation.
 */
export default function usePolling(fetcher, intervalMs, deps = []) {
  const [state, setState] = useState({ data: null, error: null, loading: true, lastSuccessAt: null });
  const fetchRef = useRef(fetcher);
  const inFlight = useRef(null);
  fetchRef.current = fetcher;

  const load = useCallback(async () => {
    if (inFlight.current) return inFlight.current;
    setState((s) => ({ ...s, loading: true }));
    inFlight.current = (async () => {
      try {
        const data = await fetchRef.current();
        setState({ data, error: null, loading: false, lastSuccessAt: Date.now() });
      } catch (e) {
        setState((s) => ({ ...s, error: errorMessage(e), loading: false }));
      } finally { inFlight.current = null; }
    })();
    return inFlight.current;
  }, []);

  useEffect(() => {
    let alive = true;
    let timer;
    const tick = async () => {
      if (!alive) return;
      await load();
      if (alive && intervalMs) timer = setTimeout(tick, intervalMs);
    };
    tick();
    return () => { alive = false; clearTimeout(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, load, ...deps]);

  return { ...state, reload: load };
}
