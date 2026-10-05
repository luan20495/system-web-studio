"use client";
import { useCallback, useEffect, useRef, useState } from "react";

/** Fetch-on-mount with explicit loading/error state; `deps` re-run the loader. Stale responses are ignored. */
export function useLoad<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const seq = useRef(0);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(() => {
    const id = ++seq.current; setLoading(true); setError(null);
    loader().then((d) => { if (id === seq.current) setData(d); }).catch((e) => { if (id === seq.current) setError(e); }).finally(() => { if (id === seq.current) setLoading(false); });
  }, deps);
  useEffect(() => { run(); }, [run]);
  return { data, error, loading, reload: run, setData };
}
