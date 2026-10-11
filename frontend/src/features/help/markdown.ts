// The manuals (docs/deliverables) are Markdown. This reads the part of Markdown they use — headings, paragraphs,
// nested lists, tables, code blocks, quotes, rules, images — into plain data that help/markdown-view renders as React
// elements. No HTML is ever passed through, so nothing in a document can run in the page.

export type Block =
  | { kind: "heading"; level: number; text: string; id: string }
  | { kind: "para"; text: string }
  | { kind: "list"; ordered: boolean; start: number; items: Block[][] }
  | { kind: "table"; head: string[]; rows: string[][] }
  | { kind: "code"; text: string }
  | { kind: "quote"; blocks: Block[] }
  | { kind: "figure"; alt: string }
  | { kind: "rule" };

/** The heading's plain text: inline code, emphasis and link syntax removed. */
export const plain = (s: string) =>
  s
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\[([^\]]+)\]\([^)\s]*\)/g, "$1")
    .replace(/`([^`]*)`/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .trim();

/** The anchor a heading gets on GitHub (and in the manuals' own "#…" links): lower case, punctuation dropped, spaces to "-". */
export const slug = (heading: string) =>
  plain(heading)
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s_-]/gu, "")
    .replace(/\s/g, "-");

const LIST = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;
const indentOf = (s: string) => s.length - s.trimStart().length;
const isFence = (s: string) => /^\s*```/.test(s);
const IMAGE_LINE = /^\s*!\[([^\]]*)\]\([^)]*\)\s*$/;

function startsBlock(line: string) {
  return /^#{1,6}\s/.test(line) || isFence(line) || /^\s*\|/.test(line) || /^\s*>/.test(line) || LIST.test(line) || /^\s*(-{3,}|\*{3,})\s*$/.test(line);
}

/** Splits a table row on "|" outside inline code; "\|" stays a character. */
export function cells(row: string): string[] {
  const s = row.trim().replace(/^\|/, "").replace(/\|$/, "");
  const out: string[] = [];
  let cur = "";
  let code = false;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === "\\" && s[i + 1] === "|") {
      cur += "|";
      i++;
    } else if (c === "`") {
      code = !code;
      cur += c;
    } else if (c === "|" && !code) {
      out.push(cur.trim());
      cur = "";
    } else cur += c;
  }
  out.push(cur.trim());
  return out;
}

export function parse(md: string, ids: Map<string, number> = new Map()): Block[] {
  const lines = md.replace(/\r\n?/g, "\n").split("\n");
  const blocks: Block[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) {
      i++;
      continue;
    }
    if (isFence(line)) {
      const body: string[] = [];
      i++;
      while (i < lines.length && !isFence(lines[i])) body.push(lines[i++]);
      i++;
      blocks.push({ kind: "code", text: body.join("\n") });
      continue;
    }
    const h = /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line);
    if (h) {
      // the same heading twice gets "-1", "-2"… as on GitHub
      const base = slug(h[2]);
      const n = ids.get(base) ?? 0;
      ids.set(base, n + 1);
      blocks.push({ kind: "heading", level: h[1].length, text: h[2], id: n ? `${base}-${n}` : base });
      i++;
      continue;
    }
    if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) {
      blocks.push({ kind: "rule" });
      i++;
      continue;
    }
    if (/^\s*\|/.test(line)) {
      const rows: string[] = [];
      while (i < lines.length && /^\s*\|/.test(lines[i])) rows.push(lines[i++]);
      const sep = rows.length > 1 && /^[\s|:-]+$/.test(rows[1]);
      blocks.push({ kind: "table", head: cells(rows[0]), rows: rows.slice(sep ? 2 : 1).map(cells) });
      continue;
    }
    if (/^\s*>/.test(line)) {
      const body: string[] = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) body.push(lines[i++].replace(/^\s*>\s?/, ""));
      blocks.push({ kind: "quote", blocks: parse(body.join("\n"), ids) });
      continue;
    }
    const m = LIST.exec(line);
    if (m) {
      const base = indentOf(line);
      const ordered = /\d/.test(m[2]);
      const items: Block[][] = [];
      while (i < lines.length) {
        const item = LIST.exec(lines[i]);
        if (!item || indentOf(lines[i]) !== base || /\d/.test(item[2]) !== ordered) break;
        const content = [item[3]];
        const inner = base + item[2].length + 1;
        i++;
        // the item goes on while lines are indented under it, or are a lazy continuation of its text
        while (i < lines.length) {
          const next = lines[i];
          if (!next.trim()) {
            const after = lines.slice(i + 1).find((l) => l.trim());
            if (after !== undefined && indentOf(after) > base) {
              content.push("");
              i++;
              continue;
            }
            break;
          }
          if (indentOf(next) > base) content.push(next.slice(Math.min(inner, indentOf(next))));
          else if (!startsBlock(next)) content.push(next.trim());
          else break;
          i++;
        }
        items.push(parse(content.join("\n"), ids));
        while (i < lines.length && !lines[i].trim()) {
          const after = lines.slice(i).find((l) => l.trim());
          const again = after ? LIST.exec(after) : null;
          if (again && indentOf(after!) === base && /\d/.test(again[2]) === ordered) i++;
          else break;
        }
      }
      blocks.push({ kind: "list", ordered, start: ordered ? Number(m[2].replace(/\D/g, "")) || 1 : 1, items });
      continue;
    }
    const img = IMAGE_LINE.exec(line);
    if (img) {
      // the manuals' screenshots live outside the app; their description stays
      blocks.push({ kind: "figure", alt: img[1] });
      i++;
      continue;
    }
    const para: string[] = [];
    while (i < lines.length && lines[i].trim() && !startsBlock(lines[i]) && !IMAGE_LINE.test(lines[i])) para.push(lines[i++].trim());
    blocks.push({ kind: "para", text: para.join(" ") });
  }
  return blocks;
}

export interface Section {
  id: string;
  level: number;
  title: string;
  /** "7.2", "6", "부록 A"… — the number the title starts with, or "" */
  number: string;
  /** index of the heading in Manual.blocks; the section runs until the next heading of the same or a higher level */
  start: number;
  end: number;
  /** the section's own words, for search */
  text: string;
}

export interface Manual {
  title: string;
  blocks: Block[];
  sections: Section[];
}

function words(b: Block): string {
  switch (b.kind) {
    case "heading":
    case "para":
    case "code":
      return plain(b.text);
    case "figure":
      return b.alt;
    case "list":
      return b.items.map((it) => it.map(words).join(" ")).join(" ");
    case "table":
      return [b.head, ...b.rows].map((r) => r.map(plain).join(" ")).join(" ");
    case "quote":
      return b.blocks.map(words).join(" ");
    default:
      return "";
  }
}

/**
 * A manual for the help screens: the document's own front matter (cover table, revision history, table of contents)
 * is left out — the help page makes its own contents — so it starts at the first numbered chapter.
 */
export function manual(md: string): Manual {
  const all = parse(md);
  const title = all.find((b): b is Extract<Block, { kind: "heading" }> => b.kind === "heading" && b.level === 1)?.text ?? "";
  const first = all.findIndex((b) => b.kind === "heading" && b.level === 2 && /^\d/.test(plain(b.text)));
  const blocks = first >= 0 ? all.slice(first) : all;
  const sections: Section[] = [];
  blocks.forEach((b, i) => {
    if (b.kind !== "heading" || b.level > 3) return;
    const t = plain(b.text);
    const number = /^(\d+(?:\.\d+)*)\.?\s/.exec(t)?.[1] ?? /^(부록 [A-Z])\./.exec(t)?.[1] ?? "";
    let end = blocks.length;
    for (let j = i + 1; j < blocks.length; j++) {
      const n = blocks[j];
      if (n.kind === "heading" && n.level <= b.level) {
        end = j;
        break;
      }
    }
    let own = blocks.length;
    for (let j = i + 1; j < blocks.length; j++) if (blocks[j].kind === "heading" && (blocks[j] as { level: number }).level <= 3) { own = j; break; }
    sections.push({ id: b.id, level: b.level, title: t, number, start: i, end, text: blocks.slice(i + 1, own).map(words).join(" ") });
  });
  return { title, blocks, sections };
}

/** The section that starts with this number ("7.2" → "7.2 보드", "6" → "6. 목록 보기 다루기"). */
export const sectionByNumber = (m: Manual, number: string) => m.sections.find((s) => s.number === number);
