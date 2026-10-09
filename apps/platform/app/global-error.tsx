"use client";

import "@xweb/ui/styles/factory.css";
import "@xweb/ui/styles/ui.css";
import { PORTAL_PREFIX } from "@xweb/permissions";
import { ErrorFallback } from "@xweb/ui";

/** Last resort: the root layout itself failed. Renders its own <html>/<body> (the layout is not mounted) with the shared fallback. */
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <html lang="vi">
      <body style={{ margin: 0 }}><ErrorFallback error={error} onRetry={reset} homeHref={PORTAL_PREFIX.platform}/></body>
    </html>
  );
}
