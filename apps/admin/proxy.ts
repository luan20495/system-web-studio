import { createCspProxy } from "../../packages/auth/src/server/csp";

export const proxy = createCspProxy({ enabled: true });

export const config = {
  matcher: [{ source: "/((?!_next/static|_next/image|favicon.ico).*)", missing: [{ type: "header", key: "next-router-prefetch" }] }]
};
