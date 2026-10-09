// Shared plumbing for the fake data layer.
import { ApiError } from "../errors";
import { persist } from "./db";

const latency = () => new Promise((r) => setTimeout(r, 90 + Math.random() * 140));

/** Every fake call: simulated network latency, the work, then persistence. */
export async function run<T>(fn: () => T): Promise<T> {
  await latency();
  const out = fn();
  persist();
  return out;
}

export const fieldError = (field: string, message: string) => new ApiError(422, "입력값을 확인하세요.", message, { fieldErrors: [{ field, message }] });

export const HEX = /^#[0-9a-fA-F]{6}$/;
