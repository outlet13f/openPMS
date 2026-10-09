// Plain text ⇄ the small HTML subset the API stores for descriptions and comments.
// The real backend sanitizes every HTML body on write (architecture.md §5); this only builds safe markup.

export function escapeHtml(s: string) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

/**
 * Paragraphs from blank-line separated text, <br> for single newlines, and @mentions for known people
 * (longest names first, so "Alex Kim" wins over "Alex").
 */
export function textToHtml(text: string, people: { id: string; name: string }[] = []): string {
  const trimmed = text.trim();
  if (!trimmed) return "";
  const sorted = [...people].sort((a, b) => b.name.length - a.name.length);
  const mention = (escaped: string) => {
    let out = escaped;
    for (const p of sorted) {
      const token = `@${escapeHtml(p.name)}`;
      out = out.split(token).join(`<span class="mention" data-user-id="${escapeHtml(p.id)}">${token}</span>`);
    }
    return out;
  };
  return trimmed
    .split(/\n{2,}/)
    .map((para) => `<p>${mention(escapeHtml(para)).replace(/\n/g, "<br>")}</p>`)
    .join("");
}

export function htmlToText(html: string): string {
  const div = document.createElement("div");
  div.innerHTML = html.replace(/<br\s*\/?>/gi, "\n").replace(/<\/p>\s*<p>/gi, "\n\n");
  return (div.textContent ?? "").trim();
}
