// Colours that come from data (type, status, priority, avatar) are chosen by admins at runtime,
// so contrast is decided here, not in the token file.

function channel(c: number) {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}

export function luminance(hex: string) {
  const h = hex.replace("#", "");
  const full = h.length === 3 ? h.split("").map((c) => c + c).join("") : h;
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16));
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

export function contrast(a: string, b: string) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const DARK = "#16191d";
const LIGHT = "#ffffff";

/** Text colour for a filled pill or bar of `bg`: whichever of near-black / white reads better. */
export function readableOn(bg: string) {
  return contrast(DARK, bg) >= contrast(LIGHT, bg) ? DARK : LIGHT;
}

/** True when a data colour can be used as text on white (4.5:1). Admin colour pickers warn otherwise. */
export function isReadableAsText(color: string, background = "#ffffff") {
  return contrast(color, background) >= 4.5;
}

const AVATAR_PALETTE = ["blue", "teal", "green", "olive", "amber", "orange", "red", "pink", "purple", "grey"] as const;

/** Stable avatar colour role for a user id (same person, same colour everywhere). */
export function avatarColor(seed: string) {
  let h = 0;
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0;
  return AVATAR_PALETTE[h % AVATAR_PALETTE.length];
}
