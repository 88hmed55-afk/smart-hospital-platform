import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The bundle is copied into the WAR by the Ant build. Keeping Vite's output
// in frontend/dist preserves WEB-INF/web.xml in smart-hospital-war/web.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "dist",
    sourcemap: false,
    target: "es2022",
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
