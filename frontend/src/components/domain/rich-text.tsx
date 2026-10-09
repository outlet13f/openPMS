// Renders description and comment HTML. The API sanitizes on write (architecture.md §5); this second,
// allow-list pass keeps the screen safe even if a body slips through (e.g. the fake data layer).
import { useMemo } from "react";
import { cn } from "@/lib/cn";

const ALLOWED: Record<string, string[]> = {
  P: [], BR: [], STRONG: [], B: [], EM: [], I: [], S: [], CODE: [], PRE: [], UL: [], OL: [], LI: [], BLOCKQUOTE: [],
  A: ["href"], SPAN: ["class", "data-user-id"], H3: [], H4: [],
};

export function sanitizeHtml(html: string): string {
  const doc = new DOMParser().parseFromString(`<body>${html}</body>`, "text/html");
  const walk = (node: Element) => {
    for (const child of Array.from(node.children)) {
      const allowed = ALLOWED[child.tagName];
      if (!allowed) {
        child.replaceWith(...Array.from(child.childNodes));
        continue;
      }
      for (const attr of Array.from(child.attributes)) {
        const ok = allowed.includes(attr.name) && !(attr.name === "href" && !/^(https?:|mailto:|\/)/i.test(attr.value));
        if (!ok) child.removeAttribute(attr.name);
      }
      if (child.tagName === "SPAN" && child.getAttribute("class") !== "mention") child.removeAttribute("class");
      if (child.tagName === "A") {
        child.setAttribute("rel", "noopener noreferrer");
        child.setAttribute("target", "_blank");
      }
      walk(child);
    }
  };
  walk(doc.body);
  return doc.body.innerHTML;
}

export function RichText({ html, className }: { html: string; className?: string }) {
  const safe = useMemo(() => sanitizeHtml(html), [html]);
  return (
    <div
      className={cn(
        "text-sm leading-6 text-fg break-words",
        "[&_p+p]:mt-2 [&_ul]:my-2 [&_ul]:list-disc [&_ul]:pl-5 [&_ol]:my-2 [&_ol]:list-decimal [&_ol]:pl-5",
        "[&_a]:text-accent [&_a]:underline [&_code]:rounded-sm [&_code]:bg-surface-sunken [&_code]:px-1 [&_code]:font-mono [&_code]:text-[0.9em]",
        "[&_pre]:my-2 [&_pre]:overflow-x-auto [&_pre]:rounded-md [&_pre]:bg-surface-sunken [&_pre]:p-3",
        "[&_blockquote]:border-l-2 [&_blockquote]:border-border [&_blockquote]:pl-3 [&_blockquote]:text-fg-muted",
        "[&_.mention]:rounded-sm [&_.mention]:bg-accent-subtle [&_.mention]:px-0.5 [&_.mention]:font-medium [&_.mention]:text-accent-subtle-text",
        className,
      )}
      dangerouslySetInnerHTML={{ __html: safe }}
    />
  );
}
