import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Static build only (no server runtime). Relative base: the app is served under a path by the factory gateway.
export default defineConfig({
  base: "./",
  plugins: [react()],
  build: { outDir: "dist", assetsDir: "assets", sourcemap: false }
});
