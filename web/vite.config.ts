import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Importing defineConfig from vitest/config types the test block, one config file for both tools. The
// proxy exists because the backend mounts routes at bare paths (/accounts); the client uses /api/... so
// this rewrite is the single place the origin and prefix live, and no CORS code is needed anywhere.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        rewrite: (path) => path.replace(/^\/api/, ""),
      },
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
