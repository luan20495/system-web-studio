"use client";
import { ErrorState } from "../ui";

/**
 * A secondary list (the options of a select, a side panel) that failed to load must SAY so, with a retry: an empty select / a default value that is not the real one is a lie by omission (M-056).
 * Nothing is shown while it loads or when it succeeded.
 */
export function LoadNote({ load, what }: { load: { error: unknown; data: unknown; reload: () => void }; what: string }) {
  return load.error && !load.data ? <ErrorState compact error={load.error} retry={load.reload} title={`Chưa tải được ${what}`}/> : null;
}
