import { clsx, type ClassValue } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

// Teach tailwind-merge our custom font-size names so `text-sm` and `text-fg` are not treated as the same group.
const twMerge = extendTailwindMerge({
  extend: { classGroups: { "font-size": [{ text: ["xs", "sm", "base", "lg", "xl", "display"] }] } },
});

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
