import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  server: {
    port: 5173,
    // Spring Boot runs on 8080 in development; the SPA calls /api through this proxy.
    proxy: { "/api": "http://localhost:8080" },
  },
  build: {
    // Spring Boot serves the built SPA as static resources.
    outDir: "../backend/src/main/resources/static",
    emptyOutDir: true,
  },
});
