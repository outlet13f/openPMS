import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  server: {
    port: 5173,
    // With VITE_API=http the SPA calls /api through this proxy to the dev backend (backend/scripts/dev.sh run, port 8090).
    proxy: { "/api": process.env.OPENPMS_API_URL ?? "http://localhost:8090" },
  },
  build: {
    // Spring Boot serves the built SPA as static resources.
    outDir: "../backend/src/main/resources/static",
    emptyOutDir: true,
  },
});
