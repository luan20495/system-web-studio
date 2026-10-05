import type { NextConfig } from "next";
import { createNextConfig } from "./packages/auth/src/server/nextConfig";

// Legacy combined app. Mock mode = static export (GitHub Pages); http mode = server-backed. The platform/admin/studio apps live in apps/*.
const nextConfig: NextConfig = createNextConfig({ http: process.env.NEXT_PUBLIC_API_MODE === "http" });

export default nextConfig;
