"use client";

import { PORTAL_PREFIX } from "@xweb/permissions";
import { ErrorFallback } from "@xweb/ui";

/** Next route-segment error boundary of the admin portal: a render exception shows the shared fallback (Thử lại + reference code), never the stack. */
export default function Error({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return <ErrorFallback error={error} onRetry={reset} homeHref={PORTAL_PREFIX.admin}/>;
}
