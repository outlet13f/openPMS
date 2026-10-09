// replica/design/tokens.json → CSS custom properties + Tailwind v4 theme mapping + TS export.
// tokens.json is the single source of truth: /replica-brand changes values there, never here.
//
//   node scripts/build-tokens.mjs
//
// Writes
//   src/styles/tokens.css          :root { --pms-* }            (imported by the app)
//   src/styles/theme.css           @theme inline { … var(--pms-*) }  (Tailwind utilities: bg-surface, text-muted …)
//   src/design/tokens.ts           the same values for the /design page
//   ../replica/design/tokens.css   copy for the design docs
//   ../replica/design/tailwind.theme.css
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");
const src = resolve(root, "../replica/design/tokens.json");
const t = JSON.parse(readFileSync(src, "utf8"));

const px = (n) => `${n}px`;
const vars = [];       // [name, value] for :root
const theme = [];      // [tailwindName, cssValue]

// colours: color.data.blue → --pms-color-data-blue → Tailwind --color-data-blue
function walkColors(obj, prefix) {
  for (const [k, v] of Object.entries(obj)) {
    if (k.startsWith("_")) continue;
    const name = prefix ? `${prefix}-${k}` : k;
    if (typeof v === "string") {
      vars.push([`--pms-color-${name}`, v]);
      // text roles become fg-* in Tailwind so classes read text-fg / text-fg-muted, not text-text-muted
      const tw = name.replace(/^text(?=$|-)/, "fg");
      theme.push([`--color-${tw}`, `var(--pms-color-${name})`]);
    } else walkColors(v, name);
  }
}
walkColors(t.color, "");

vars.push(["--pms-font-sans", t.font.sans], ["--pms-font-mono", t.font.mono]);
theme.push(["--font-sans", "var(--pms-font-sans)"], ["--font-mono", "var(--pms-font-mono)"]);

for (const [k, v] of Object.entries(t.type)) {
  vars.push([`--pms-text-${k}`, px(v.size)], [`--pms-text-${k}-line`, px(v.line)], [`--pms-text-${k}-weight`, String(v.weight)]);
  theme.push(
    [`--text-${k}`, `var(--pms-text-${k})`],
    [`--text-${k}--line-height`, `var(--pms-text-${k}-line)`],
    [`--text-${k}--font-weight`, `var(--pms-text-${k}-weight)`],
  );
}

for (const [k, v] of Object.entries(t.radius)) {
  vars.push([`--pms-radius-${k}`, px(v)]);
  theme.push([`--radius-${k}`, `var(--pms-radius-${k})`]);
}
for (const [k, v] of Object.entries(t.shadow)) {
  vars.push([`--pms-shadow-${k}`, v]);
  theme.push([`--shadow-${k}`, `var(--pms-shadow-${k})`]);
}
vars.push(["--pms-motion-fast", t.motion.fast], ["--pms-motion-base", t.motion.base], ["--pms-motion-slow", t.motion.slow], ["--pms-ease", t.motion.ease]);
theme.push(["--ease-standard", "var(--pms-ease)"]);

// layout: sizes become named spacing utilities (h-header, w-sidebar, w-split …); breakpoints map to Tailwind's.
const sizeNames = {
  "header-h": "header", "sidebar-w": "sidebar", "sidebar-item-h": "sidebar-item", "row-h": "row",
  "row-h-compact": "row-compact", "control-h-sm": "control-sm", "control-h-md": "control-md",
  "control-h-lg": "control-lg", "split-w": "split", "split-w-min": "split-min", "board-col-w": "board-col",
  "modal-sm": "modal-sm", "modal-md": "modal-md", "modal-lg": "modal-lg", "form-max-w": "form",
  "content-px": "content", "toolbar-gap": "toolbar",
};
for (const [k, v] of Object.entries(t.layout)) {
  vars.push([`--pms-layout-${k}`, px(v)]);
  if (k.startsWith("bp-")) theme.push([`--breakpoint-${k.slice(3)}`, px(v)]); // breakpoints must be literal
  else if (sizeNames[k]) theme.push([`--spacing-${sizeNames[k]}`, `var(--pms-layout-${k})`]);
}
for (const [k, v] of Object.entries(t.z)) vars.push([`--pms-z-${k}`, String(v)]);

const banner = "/* 생성 파일: scripts/build-tokens.mjs가 replica/design/tokens.json에서 만든다. 직접 고치지 말 것. */\n";
// dark theme: the same colour roles with color-dark values. Chosen explicitly (data-theme="dark") or, when the
// person keeps "시스템", by the operating system; data-theme="light" pins light.
const darkVars = [];
(function walkDark(obj, prefix) {
  for (const [k, v] of Object.entries(obj ?? {})) {
    if (k.startsWith("_")) continue;
    const name = prefix ? `${prefix}-${k}` : k;
    if (typeof v === "string") darkVars.push([`--pms-color-${name}`, v]);
    else walkDark(v, name);
  }
})(t["color-dark"], "");
const darkBody = ["  color-scheme: dark;", ...darkVars.map(([n, v]) => `  ${n}: ${v};`)].join("\n");
const tokensCss =
  `${banner}:root {\n  color-scheme: light;\n${vars.map(([n, v]) => `  ${n}: ${v};`).join("\n")}\n}\n` +
  `:root[data-theme="dark"] {\n${darkBody}\n}\n` +
  `@media (prefers-color-scheme: dark) {\n  :root:not([data-theme="light"]) {\n${darkBody.replace(/^/gm, "  ")}\n  }\n}\n`;
const themeCss = `${banner}@theme inline {\n${theme.map(([n, v]) => `  ${n}: ${v};`).join("\n")}\n}\n`;
const tokensTs = `${banner.replace("/*", "//").replace(" */", "")}export const tokens = ${JSON.stringify(
  { color: t.color, pairs: t.pairs, type: t.type, space: t.space, radius: t.radius, shadow: t.shadow, motion: t.motion, layout: t.layout, z: t.z },
  null, 2)} as const;\n`;

const out = [
  [resolve(root, "src/styles/tokens.css"), tokensCss],
  [resolve(root, "src/styles/theme.css"), themeCss],
  [resolve(root, "src/design/tokens.ts"), tokensTs],
  [resolve(root, "../replica/design/tokens.css"), tokensCss],
  [resolve(root, "../replica/design/tailwind.theme.css"), themeCss],
];
for (const [file, text] of out) {
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, text);
}
console.log(`tokens: ${vars.length} css variables, ${theme.length} tailwind theme entries`);
