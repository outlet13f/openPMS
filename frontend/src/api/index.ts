// Which data layer runs: the in-memory fake until /replica-backend exists, then the HTTP client.
// Set VITE_API=http in .env.local to talk to Spring Boot through the Vite proxy.
import type { Api } from "./api";
import { fakeApi } from "./fake";
import { httpApi } from "./http";

export const API_MODE: "fake" | "http" = import.meta.env.VITE_API === "http" ? "http" : "fake";
export const api: Api = API_MODE === "http" ? httpApi : fakeApi;

export type { Api } from "./api";
export * from "./types";
export { ApiError, isApiError } from "./errors";
