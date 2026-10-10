import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig(({ command, mode }) => {
  // a shipped build on the in-browser fake data layer would look fine and save nothing: refuse to build it
  if (command === "build" && mode === "production" && loadEnv(mode, process.cwd(), "VITE_").VITE_API !== "http") {
    throw new Error("production build without VITE_API=http (is .env.production missing from the build?)");
  }
  return {
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
  };
});
