// Rich text editor (components.md "RichTextEditor"; Tiptap, MIT). Writes the HTML subset the API keeps
// (architecture.md §1: p, strong, em, u, s, code, pre, ul/ol/li, blockquote, a, span.mention[data-user-id]),
// so what the server sanitises and what the editor produces are the same thing.
// Keyboard: Ctrl+B/I/U, Ctrl+Shift+7/8 lists, Ctrl+Enter submits; @ opens the people list (↑↓ Enter, Esc).
import { useEffect, useId, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { EditorContent, Extension, useEditor, type Editor } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import Mention from "@tiptap/extension-mention";
import { Placeholder } from "@tiptap/extensions";
import type { SuggestionKeyDownProps, SuggestionProps } from "@tiptap/suggestion";
import { Bold, Code, Italic, Link2, List, ListOrdered, Quote, SquareCode, Strikethrough, Underline } from "lucide-react";
import { cn } from "@/lib/cn";
import { Avatar } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/field";
import { richTextClasses } from "./rich-text";

export type Person = { id: string; name: string };

/* ---------- the @mention list: a tiny store the suggestion plugin writes and React reads ---------- */

type MenuState = { open: boolean; items: Person[]; active: number; rect: DOMRect | null; command: ((p: { id: string; label: string }) => void) | null };
const closed: MenuState = { open: false, items: [], active: 0, rect: null, command: null };

function createMenuStore() {
  let state = closed;
  const listeners = new Set<() => void>();
  return {
    get: () => state,
    set: (next: Partial<MenuState>) => {
      state = { ...state, ...next };
      listeners.forEach((l) => l());
    },
    subscribe: (l: () => void) => (listeners.add(l), () => listeners.delete(l)),
  };
}

/** Mention stored as the API expects: <span class="mention" data-user-id="…">@이름</span>; reads the same back. */
const StoredMention = Mention.extend({
  parseHTML() {
    return [{ tag: "span.mention[data-user-id]" }, { tag: 'span[data-type="mention"]' }];
  },
  addAttributes() {
    return {
      id: { default: null, parseHTML: (el) => el.getAttribute("data-user-id") ?? el.getAttribute("data-id"), renderHTML: (a) => ({ "data-user-id": a.id }) },
      label: { default: null, parseHTML: (el) => el.getAttribute("data-label") ?? (el.textContent ?? "").replace(/^@/, ""), renderHTML: () => ({}) },
    };
  },
});

function useMenu() {
  const [store] = useState(createMenuStore);
  const state = useSyncExternalStore(store.subscribe, store.get);
  return { store, state };
}

/* ---------- toolbar ---------- */

function ToolButton({ label, pressed, onClick, children }: { label: string; pressed?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      // keep the text selection: the editor must not lose focus before the command runs
      onMouseDown={(e) => e.preventDefault()}
      onClick={onClick}
      className={cn(
        "inline-flex size-7 items-center justify-center rounded-sm text-fg-muted hover:bg-surface-hover hover:text-fg [&_svg]:size-4",
        pressed && "bg-surface-selected text-fg",
      )}
    >
      {children}
    </button>
  );
}

function Toolbar({ editor, onLink, label }: { editor: Editor; onLink: () => void; label: string }) {
  const { t } = useTranslation();
  const c = () => editor.chain().focus();
  // re-render on selection changes so aria-pressed follows the caret
  const [, force] = useState(0);
  useEffect(() => {
    const on = () => force((n) => n + 1);
    editor.on("transaction", on);
    return () => void editor.off("transaction", on);
  }, [editor]);
  return (
    <div role="toolbar" aria-label={t("editor.toolbar", { field: label })} className="flex flex-wrap items-center gap-0.5 border-b border-border-muted px-1 py-1">
      <ToolButton label={t("editor.bold")} pressed={editor.isActive("bold")} onClick={() => c().toggleBold().run()}>
        <Bold />
      </ToolButton>
      <ToolButton label={t("editor.italic")} pressed={editor.isActive("italic")} onClick={() => c().toggleItalic().run()}>
        <Italic />
      </ToolButton>
      <ToolButton label={t("editor.underline")} pressed={editor.isActive("underline")} onClick={() => c().toggleUnderline().run()}>
        <Underline />
      </ToolButton>
      <ToolButton label={t("editor.strike")} pressed={editor.isActive("strike")} onClick={() => c().toggleStrike().run()}>
        <Strikethrough />
      </ToolButton>
      <ToolButton label={t("editor.code")} pressed={editor.isActive("code")} onClick={() => c().toggleCode().run()}>
        <Code />
      </ToolButton>
      <span className="mx-1 h-4 w-px bg-border" aria-hidden="true" />
      <ToolButton label={t("editor.bulletList")} pressed={editor.isActive("bulletList")} onClick={() => c().toggleBulletList().run()}>
        <List />
      </ToolButton>
      <ToolButton label={t("editor.orderedList")} pressed={editor.isActive("orderedList")} onClick={() => c().toggleOrderedList().run()}>
        <ListOrdered />
      </ToolButton>
      <ToolButton label={t("editor.quote")} pressed={editor.isActive("blockquote")} onClick={() => c().toggleBlockquote().run()}>
        <Quote />
      </ToolButton>
      <ToolButton label={t("editor.codeBlock")} pressed={editor.isActive("codeBlock")} onClick={() => c().toggleCodeBlock().run()}>
        <SquareCode />
      </ToolButton>
      <ToolButton label={t("editor.link")} pressed={editor.isActive("link")} onClick={onLink}>
        <Link2 />
      </ToolButton>
    </div>
  );
}

function LinkForm({ editor, onClose }: { editor: Editor; onClose: () => void }) {
  const { t } = useTranslation();
  const [href, setHref] = useState<string>(() => editor.getAttributes("link").href ?? "https://");
  const [error, setError] = useState<string | null>(null);
  const apply = () => {
    const url = href.trim();
    if (!/^(https?:\/\/|mailto:)\S+$/i.test(url)) return setError(t("editor.linkInvalid"));
    const chain = editor.chain().focus().extendMarkRange("link");
    // with nothing selected, insert the address itself as the link text
    if (editor.state.selection.empty && !editor.isActive("link")) chain.insertContent({ type: "text", text: url, marks: [{ type: "link", attrs: { href: url } }] }).run();
    else chain.setLink({ href: url }).run();
    onClose();
  };
  return (
    <div className="flex flex-wrap items-center gap-2 border-b border-border-muted bg-surface px-2 py-1.5">
      <label className="flex min-w-0 flex-1 items-center gap-2 text-xs text-fg-muted">
        {t("editor.linkUrl")}
        <Input
          size="sm"
          autoFocus
          value={href}
          aria-invalid={!!error}
          onChange={(e) => (setHref(e.target.value), setError(null))}
          onKeyDown={(e) => {
            if (e.key === "Enter") (e.preventDefault(), apply());
            if (e.key === "Escape") (e.preventDefault(), onClose(), editor.commands.focus());
          }}
        />
      </label>
      <Button size="sm" variant="primary" onClick={apply}>
        {t("editor.linkApply")}
      </Button>
      {editor.isActive("link") && (
        <Button size="sm" onClick={() => (editor.chain().focus().extendMarkRange("link").unsetLink().run(), onClose())}>
          {t("editor.linkRemove")}
        </Button>
      )}
      <Button size="sm" variant="ghost" onClick={() => (onClose(), editor.commands.focus())}>
        {t("app.cancel")}
      </Button>
      {error && <p className="w-full text-xs text-danger">{error}</p>}
    </div>
  );
}

/* ---------- the editor ---------- */

export interface RichTextEditorProps {
  /** Initial HTML; later changes are ignored unless `resetKey` changes. */
  value: string;
  onChange: (html: string) => void;
  /** Accessible name of the text box. */
  label: string;
  placeholder?: string;
  /** People offered after "@"; leave out to switch mentions off. */
  people?: Person[];
  onSubmit?: () => void;
  onCancel?: () => void;
  autoFocus?: boolean;
  invalid?: boolean;
  minRows?: number;
  className?: string;
  id?: string;
  resetKey?: unknown;
}

const isEmptyDoc = (editor: Editor) => editor.isEmpty || !editor.getText().trim();

export function RichTextEditor({ value, onChange, label, placeholder, people, onSubmit, onCancel, autoFocus, invalid, minRows = 3, className, id, resetKey }: RichTextEditorProps) {
  const { t } = useTranslation();
  const menu = useMenu();
  const listId = useId();
  const [linking, setLinking] = useState(false);
  // the plugin's callbacks are created once; read the latest props through refs
  const peopleRef = useRef(people ?? []);
  peopleRef.current = people ?? [];
  const submitRef = useRef(onSubmit);
  submitRef.current = onSubmit;
  const cancelRef = useRef(onCancel);
  cancelRef.current = onCancel;
  const changeRef = useRef(onChange);
  changeRef.current = onChange;

  const editor = useEditor(
    {
      extensions: [
        StarterKit.configure({
          heading: false,
          horizontalRule: false,
          link: { openOnClick: false, autolink: true, HTMLAttributes: { rel: "noopener noreferrer", target: null } },
        }),
        Placeholder.configure({ placeholder: placeholder ?? "" }),
        // ahead of the built-in Mod-Enter (leave code block / hard break), so Ctrl+Enter always sends
        Extension.create({
          name: "submitKeys",
          priority: 1000,
          addKeyboardShortcuts: () => ({
            "Mod-Enter": () => (submitRef.current ? (submitRef.current(), true) : false),
            Escape: () => (!menu.store.get().open && cancelRef.current ? (cancelRef.current(), true) : false),
          }),
        }),
        ...(people
          ? [
              StoredMention.configure({
                HTMLAttributes: { class: "mention" },
                renderHTML: ({ node }) => ["span", { class: "mention", "data-user-id": node.attrs.id }, `@${node.attrs.label ?? ""}`],
                renderText: ({ node }) => `@${node.attrs.label ?? ""}`,
                suggestion: {
                  char: "@",
                  items: ({ query }) => peopleRef.current.filter((p) => p.name.toLowerCase().includes(query.toLowerCase())).slice(0, 6),
                  render: () => ({
                    onStart: (p: SuggestionProps<Person>) =>
                      menu.store.set({ open: true, items: p.items, active: 0, rect: p.clientRect?.() ?? null, command: (x) => p.command(x) }),
                    onUpdate: (p: SuggestionProps<Person>) =>
                      menu.store.set({ items: p.items, active: 0, rect: p.clientRect?.() ?? null, command: (x) => p.command(x) }),
                    onKeyDown: ({ event }: SuggestionKeyDownProps) => {
                      const s = menu.store.get();
                      if (!s.open || !s.items.length) return false;
                      if (event.key === "ArrowDown") return (menu.store.set({ active: (s.active + 1) % s.items.length }), true);
                      if (event.key === "ArrowUp") return (menu.store.set({ active: (s.active - 1 + s.items.length) % s.items.length }), true);
                      if (event.key === "Enter" || event.key === "Tab") {
                        const pick = s.items[s.active];
                        s.command?.({ id: pick.id, label: pick.name });
                        return true;
                      }
                      if (event.key === "Escape") return (menu.store.set(closed), true);
                      return false;
                    },
                    onExit: () => menu.store.set(closed),
                  }),
                },
              }),
            ]
          : []),
      ],
      content: value,
      autofocus: autoFocus ? "end" : false,
      editorProps: {
        attributes: {
          role: "textbox",
          "aria-multiline": "true",
          "aria-label": label,
          ...(id ? { id } : {}),
          class: cn(richTextClasses, "min-h-[var(--rows)] px-3 py-2 outline-none"),
          style: `--rows: ${minRows * 1.5 + 1}rem`,
        },
      },
      onUpdate: ({ editor: e }) => changeRef.current(isEmptyDoc(e) ? "" : e.getHTML()),
    },
    [resetKey],
  );

  // the list's state is announced through the text box (combobox pattern)
  useEffect(() => {
    const dom = editor?.view.dom;
    if (!dom) return;
    const s = menu.state;
    if (s.open && s.items.length) {
      dom.setAttribute("aria-expanded", "true");
      dom.setAttribute("aria-controls", listId);
      dom.setAttribute("aria-activedescendant", `${listId}-${s.active}`);
    } else {
      dom.setAttribute("aria-expanded", "false");
      dom.removeAttribute("aria-activedescendant");
    }
  }, [editor, menu.state, listId]);

  if (!editor) return null;
  const s = menu.state;
  return (
    <div
      className={cn(
        "rounded-md border bg-bg focus-within:outline-2 focus-within:outline-offset-0 focus-within:outline-focus-ring",
        invalid ? "border-danger" : "border-border-input",
        className,
      )}
    >
      <Toolbar editor={editor} label={label} onLink={() => setLinking((x) => !x)} />
      {linking && <LinkForm editor={editor} onClose={() => setLinking(false)} />}
      <EditorContent editor={editor} />
      {s.open && s.items.length > 0 && s.rect && (
        <ul
          id={listId}
          role="listbox"
          aria-label={t("editor.mentionList")}
          className="fixed z-(--pms-z-dropdown) w-56 overflow-hidden rounded-lg border border-border bg-bg p-1 shadow-pop"
          style={{ left: s.rect.left, top: s.rect.bottom + 4 }}
        >
          {s.items.map((p, i) => (
            <li
              key={p.id}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === s.active}
              onMouseDown={(e) => (e.preventDefault(), s.command?.({ id: p.id, label: p.name }))}
              className={cn("flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm text-fg", i === s.active && "bg-surface-hover")}
            >
              <Avatar id={p.id} name={p.name} size="xs" />
              {p.name}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
