import { useEffect, useRef, useState } from 'react';
import { fetchJson } from './api';

export interface ApiState<T> {
  data: T | null;
  error: string | null;
  /** True only before the first successful or failed fetch. */
  loading: boolean;
}

/**
 * Polling data hook — fetches `path` immediately, then every `intervalMs`.
 *
 * NOTE (deviation from the original Phase 9 plan): the plan called for a
 * WebSocket stream from port 8080. We poll instead: the HTTP API already
 * exists, is CORS-open, and 2s polling is indistinguishable from push at
 * dashboard refresh rates. WebSocket push is listed as a future improvement
 * in docs/internals/phase9-dashboard.md.
 */
export function useApi<T>(path: string, intervalMs = 2000): ApiState<T> {
  const [state, setState] = useState<ApiState<T>>({ data: null, error: null, loading: true });
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    let timer: ReturnType<typeof setInterval> | undefined;

    const tick = async () => {
      try {
        const data = await fetchJson<T>(path);
        if (alive.current) setState({ data, error: null, loading: false });
      } catch (e) {
        if (alive.current) {
          setState((prev) => ({ data: prev.data, error: (e as Error).message, loading: false }));
        }
      }
    };

    void tick();
    timer = setInterval(tick, intervalMs);
    return () => {
      alive.current = false;
      if (timer) clearInterval(timer);
    };
  }, [path, intervalMs]);

  return state;
}
