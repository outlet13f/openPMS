// Renders help/markdown blocks as React elements (text only: no HTML from the document reaches the page).
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ImageIcon } from "lucide-react";
import type { Block } from "./markdown";

const INLINE = /(`+)([\s\S]*?)\1|\*\*([\s\S]+?)\*\*|!\[([^\]]*)\]\(([^)]*)\)|\[([^\]]+)\]\(([^)\s]*)\)/g;

export interface LinkHandler {
  /** "#…" links inside the same manual */
  anchorHref: (id: string) => string;
  onAnchor: (id: string) => void;
  /** another manual by its file name ("10-운영자매뉴얼.md"), or null to show the text only */
  docHref?: (file: string, id: string | undefined) => string | null;
}

export function Inline({ text, links }: { text: string; links: LinkHandler }): ReactNode {
  const out: ReactNode[] = [];
  let last = 0;
  let key = 0;
  for (const m of text.matchAll(INLINE)) {
    if (m.index > last) out.push(text.slice(last, m.index));
    last = m.index + m[0].length;
    if (m[1]) out.push(<code key={key++} className="rounded bg-surface-sunken px-1 py-0.5 font-mono text-[0.85em] break-words">{m[2]}</code>);
    else if (m[3] !== undefined) out.push(<strong key={key++} className="font-semibold text-fg"><Inline text={m[3]} links={links} /></strong>);
    else if (m[4] !== undefined) out.push(<span key={key++} className="text-fg-muted">[{m[4]}]</span>);
    else {
      const label = <Inline text={m[6]} links={links} />;
      const href = m[7];
      if (href.startsWith("#")) {
        const id = decodeURIComponent(href.slice(1));
        out.push(
          <a key={key++} href={links.anchorHref(id)} className="text-accent underline underline-offset-2" onClick={(e) => (e.preventDefault(), links.onAnchor(id))}>
            {label}
          </a>,
        );
      } else if (/^https?:\/\//.test(href)) {
        out.push(<a key={key++} href={href} target="_blank" rel="noreferrer" className="text-accent underline underline-offset-2">{label}</a>);
      } else {
        const [file, hash] = href.split("#");
        const to = links.docHref?.(file.split("/").pop() ?? file, hash ? decodeURIComponent(hash) : undefined);
        out.push(to ? <a key={key++} href={to} className="text-accent underline underline-offset-2">{label}</a> : <span key={key++}>{label}</span>);
      }
    }
  }
  if (last < text.length) out.push(text.slice(last));
  return <>{out}</>;
}

const headingCls: Record<number, string> = {
  1: "mt-2 mb-4 text-2xl font-semibold text-fg",
  2: "mt-10 mb-3 border-b border-border-muted pb-2 text-xl font-semibold text-fg",
  3: "mt-8 mb-2 text-lg font-semibold text-fg",
  4: "mt-6 mb-2 text-base font-semibold text-fg",
};

export function MarkdownView({ blocks, links, headingOffset = 0, focusId }: { blocks: Block[]; links: LinkHandler; headingOffset?: number; focusId?: string }) {
  const { t } = useTranslation();
  const render = (b: Block, i: number): ReactNode => {
    switch (b.kind) {
      case "heading": {
        const level = Math.min(6, Math.max(2, b.level + headingOffset));
        const Tag = `h${level}` as "h2";
        return (
          <Tag key={i} id={b.id} tabIndex={-1} className={`${headingCls[Math.min(4, b.level)] ?? headingCls[4]} scroll-mt-24 focus-visible:outline-none ${focusId === b.id ? "-mx-2 rounded-md bg-accent-subtle px-2" : ""}`}>
            <Inline text={b.text} links={links} />
          </Tag>
        );
      }
      case "para":
        return <p key={i} className="my-3 leading-7 text-fg"><Inline text={b.text} links={links} /></p>;
      case "list": {
        const items = b.items.map((it, j) => (
          <li key={j} className="my-1 pl-1 leading-7 [&>p]:my-1">
            {it.length === 1 && it[0].kind === "para" ? <Inline text={it[0].text} links={links} /> : it.map(render)}
          </li>
        ));
        return b.ordered ? <ol key={i} start={b.start} className="my-3 list-decimal pl-6">{items}</ol> : <ul key={i} className="my-3 list-disc pl-6">{items}</ul>;
      }
      case "table":
        return (
          <div key={i} tabIndex={0} className="my-4 overflow-x-auto focus-visible:outline-2 focus-visible:outline-accent">
            <table className="w-full min-w-[480px] border-collapse text-sm">
              <thead>
                <tr className="border-b border-border text-left">
                  {b.head.map((c, j) => <th key={j} scope="col" className="px-2 py-2 align-top font-semibold text-fg"><Inline text={c} links={links} /></th>)}
                </tr>
              </thead>
              <tbody>
                {b.rows.map((r, j) => (
                  <tr key={j} className="border-b border-border-muted">
                    {r.map((c, k) => <td key={k} className="px-2 py-2 align-top text-fg"><Inline text={c} links={links} /></td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        );
      case "code":
        return <pre key={i} tabIndex={0} className="focus-visible:outline-2 focus-visible:outline-accent my-3 overflow-x-auto rounded-md bg-surface-sunken p-3 font-mono text-xs leading-5 text-fg">{b.text}</pre>;
      case "quote":
        return <blockquote key={i} className="my-3 border-l-4 border-border pl-3 text-fg-muted">{b.blocks.map(render)}</blockquote>;
      case "figure":
        return (
          <p key={i} className="my-3 flex items-start gap-2 rounded-md border border-dashed border-border px-3 py-2 text-sm text-fg-muted">
            <ImageIcon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            <span>{t("help.figure", { alt: b.alt })}</span>
          </p>
        );
      case "rule":
        return <hr key={i} className="my-6 border-border-muted" />;
    }
  };
  return <>{blocks.map(render)}</>;
}
