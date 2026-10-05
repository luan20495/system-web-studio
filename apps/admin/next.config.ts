import path from "node:path";
import type { NextConfig } from "next";
import { createNextConfig } from "../../packages/auth/src/server/nextConfig";

// Xweb Admin: always server-backed (real API through the same-origin /api proxy). Workspace packages are TS sources, so they are transpiled here.
const nextConfig: NextConfig = {
  ...createNextConfig({ http: true, root: path.resolve(process.cwd(), "../..") }),
  transpilePackages: ["@xweb/api-client", "@xweb/auth", "@xweb/i18n", "@xweb/permissions", "@xweb/types", "@xweb/ui"],
};

export default nextConfig;
