// S15 보드: drag cards between columns (changes status/assignee/version through the API, workflow enforced), or on a
// basic board, lists the team fills by hand. A new card from a column gets the column's value. Filters narrow every
// column; anyone can look through other filters, board.manage saves them. With board.manage: rename the board, add
// columns (a status, member or version not yet there, or a named list), move and remove them.
import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type KeyboardEventHandler, type PointerEventHandler } from "react";
import { Outlet, useMatch, useNavigate, useParams } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import {
  closestCorners,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
  type KeyboardCoordinateGetter,
} from "@dnd-kit/core";
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { ArrowLeft, ArrowRight, Columns3, Filter, GripVertical, ListPlus, MoreHorizontal, Pencil, Plus, RotateCcw, Save, Trash2, X } from "lucide-react";
import { api, isApiError, type Board, type BoardColumn, type QueryFilter, type QuerySpec, type WorkPackage } from "@/api";
import { invalidateWorkPackages, qk, useBoard, useBoardLook, useBoardMutations, useFilterOptions, useProjects, useWpQuery } from "@/api/hooks";
import { FilterPanel } from "@/features/work-packages/filter-panel";
import { Field, Input } from "@/components/ui/field";
import { Button } from "@/components/ui/button";
import { Badge, Counter } from "@/components/ui/badges";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { WorkPackageCard } from "@/components/domain/work-package-card";
import { cn } from "@/lib/cn";
import { ErrorState, PageHeader, useDocumentTitle } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { WorkPackageDetail, asDetailTab } from "@/features/work-packages/detail";

const cardId = (n: number) => `card-${n}`;
/** A long column draws this many cards first and the rest on request (성능 예산: 1,000 cards at once took 1.5 s and 250 MB). */
const COLUMN_PAGE = 50;
/** Where a card dropped on the column itself (not on a card) goes: the end, or the end of what is drawn when the column is cut short. */
const dropEnd = (count: number, limit: number) => (count > limit ? limit - 1 : count);

/**
 * ←/→ jump to the neighbouring column, ↑/↓ move within the column. dnd-kit's sortable getter alone
 * only moves inside one list, which left keyboard users unable to change a card's column.
 */
const boardKeyboardCoordinates: KeyboardCoordinateGetter = (event, args) => {
  if (event.code !== "ArrowRight" && event.code !== "ArrowLeft") return sortableKeyboardCoordinates(event, args);
  const { collisionRect, droppableContainers, droppableRects } = args.context;
  if (!collisionRect) return undefined;
  event.preventDefault();
  const right = event.code === "ArrowRight";
  const candidates = droppableContainers
    .getEnabled()
    .filter((c) => !String(c.id).startsWith("card-"))
    .map((c) => droppableRects.get(c.id))
    .filter((r): r is NonNullable<typeof r> => !!r && (right ? r.left > collisionRect.left + 1 : r.left < collisionRect.left - 1))
    .sort((a, b) => Math.abs(a.left - collisionRect.left) - Math.abs(b.left - collisionRect.left));
  const target = candidates[0];
  if (!target) return undefined;
  return { x: target.left + (target.width - collisionRect.width) / 2, y: target.top + 8 };
};
const numberOf = (id: string | number) => Number(String(id).replace("card-", ""));

function toCard(wp: WorkPackage) {
  return {
    number: wp.number,
    subject: wp.subject,
    projectName: wp.project.name,
    type: wp.type,
    status: wp.status,
    assignee: wp.assignee ?? undefined,
    startDate: wp.startDate ?? undefined,
    dueDate: wp.dueDate ?? undefined,
    overdue: wp._meta.overdue,
    autoScheduled: !wp.scheduleManually,
  };
}

/**
 * Pointer: drag from anywhere on the card (a click without movement still opens the link).
 * Keyboard: the grip button lifts the card (Space), arrows move it, Space drops, Esc cancels;
 * Enter on the card link keeps opening the item.
 */
function SortableCard({ wp, href, disabled, onRemove }: { wp: WorkPackage; href: string; disabled: boolean; onRemove?: () => void }) {
  const { t } = useTranslation();
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({ id: cardId(wp.number), disabled });
  return (
    <li ref={setNodeRef} style={{ transform: CSS.Transform.toString(transform), transition }} className={cn(isDragging && "opacity-40")} onPointerDown={listeners?.onPointerDown as PointerEventHandler<HTMLLIElement> | undefined}>
      <WorkPackageCard
        wp={toCard(wp)}
        href={href}
        handle={
          <>
          {onRemove && (
            <button
              type="button"
              onClick={onRemove}
              aria-label={t("boards.removeCard", { number: wp.number })}
              className="inline-flex size-6 items-center justify-center rounded-sm text-fg-muted hover:bg-surface-hover hover:text-fg"
            >
              <X className="size-4" aria-hidden="true" />
            </button>
          )}
          {!disabled && (
            <button
              type="button"
              ref={setActivatorNodeRef}
              {...attributes}
              onKeyDown={listeners?.onKeyDown as KeyboardEventHandler<HTMLButtonElement> | undefined}
              aria-roledescription={t("boards.cardRole")}
              aria-label={t("boards.moveCard", { number: wp.number, subject: wp.subject })}
              className="inline-flex size-6 cursor-grab items-center justify-center rounded-sm text-fg-muted hover:bg-surface-hover active:cursor-grabbing"
            >
              <GripVertical className="size-4" aria-hidden="true" />
            </button>
          )}
          </>
        }
      />
    </li>
  );
}

/** Renaming the board or a column without a value: one text field. */
function NameDialog({ title, label, initial, onClose, onSave }: { title: string; label: string; initial: string; onClose: () => void; onSave: (name: string) => Promise<unknown> }) {
  const { t } = useTranslation();
  const [name, setName] = useState(initial);
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const save = async () => {
    if (!name.trim()) return setError(t("boards.nameRequired"));
    setBusy(true);
    try {
      await onSave(name.trim());
      onClose();
    } catch (e) {
      setError(isApiError(e) ? (e.fieldErrors[0]?.message ?? e.message) : t("app.errorBody"));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={title}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={busy} onClick={() => void save()}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      <form onSubmit={(e) => (e.preventDefault(), void save())}>
        <Field label={label} required error={error}>
          <Input autoFocus maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
      </form>
    </Dialog>
  );
}

type ColumnActions = { left?: () => void; right?: () => void; rename?: () => void; remove: () => void };

/** The column's quick-create row: a title, Enter makes the card (with the column's value), Esc closes. */
function NewCard({ column, onCreate, onClose }: { column: BoardColumn; onCreate: (subject: string) => Promise<unknown>; onClose: () => void }) {
  const { t } = useTranslation();
  const [subject, setSubject] = useState("");
  const [error, setError] = useState<string | null>(null);
  const sending = useRef(false); // Enter pressed twice makes one card
  const ref = useRef<HTMLInputElement>(null);
  const submit = async () => {
    if (sending.current) return;
    if (!subject.trim()) return setError(t("wp.subjectRequired"));
    sending.current = true;
    try {
      await onCreate(subject.trim());
      setSubject("");
      setError(null);
      ref.current?.focus();
    } catch (e) {
      setError(isApiError(e) ? (e.fieldErrors[0]?.message ?? e.message) : t("app.errorBody"));
    } finally {
      sending.current = false;
    }
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") (e.preventDefault(), void submit());
    else if (e.key === "Escape") onClose();
  };
  const errorId = `new-card-error-${column.id}`;
  return (
    <div className="mt-2 rounded-md border border-border-input bg-bg p-2">
      <label className="sr-only" htmlFor={`new-card-${column.id}`}>{t("boards.newCardLabel", { column: column.title })}</label>
      <Input
        id={`new-card-${column.id}`}
        ref={ref}
        autoFocus
        size="sm"
        maxLength={255}
        value={subject}
        placeholder={t("boards.newCardPlaceholder")}
        aria-invalid={!!error}
        aria-describedby={error ? errorId : undefined}
        onChange={(e) => setSubject(e.target.value)}
        onKeyDown={onKey}
        onBlur={() => !subject.trim() && onClose()}
      />
      {error && <p id={errorId} className="mt-1 text-xs text-danger">{error}</p>}
    </div>
  );
}

function Column({
  column,
  hrefOf,
  canDrag,
  onCreate,
  onAddExisting,
  onRemoveCard,
  actions,
  limit,
  onMore,
}: {
  column: BoardColumn;
  hrefOf: (wp: WorkPackage) => string;
  canDrag: boolean;
  /** a new card in this column (omitted without the right to create) */
  onCreate?: (subject: string) => Promise<unknown>;
  /** basic boards: put an existing work package here */
  onAddExisting?: () => void;
  /** basic boards: take a card off the board */
  onRemoveCard?: (wp: WorkPackage) => void;
  actions?: ColumnActions;
  /** cards drawn; the rest wait behind 더 보기 */
  limit: number;
  onMore: () => void;
}) {
  const { t } = useTranslation();
  const { setNodeRef, isOver } = useDroppable({ id: column.id });
  const drawn = column.cards.length > limit ? column.cards.slice(0, limit) : column.cards;
  const hidden = column.cards.length - drawn.length;
  const [creating, setCreating] = useState(false);
  const addLabel = t("boards.addCard", { column: column.title });
  return (
    <section aria-label={column.title} className={cn("flex w-board-col shrink-0 flex-col rounded-lg bg-surface p-2", isOver && "outline-2 outline-accent")}>
      <header className="flex items-center justify-between px-1 pt-1 pb-2">
        <h2 className="flex min-w-0 items-center gap-2 text-sm font-semibold text-fg">
          {column.color && <span className="size-2.5 shrink-0 rounded-full" style={{ backgroundColor: column.color }} aria-hidden="true" />}
          <span className="truncate">{column.title}</span>
          <Counter>{column.cards.length}</Counter>
        </h2>
        <div className="flex shrink-0 items-center">
          {onAddExisting ? (
            <Menu
              align="end"
              trigger={<Button variant="ghost" size="sm" iconOnly aria-label={addLabel} icon={<Plus />} />}
              items={[
                ...(onCreate ? [{ label: t("boards.newCard"), icon: <Plus />, onSelect: () => setCreating(true) }] : []),
                { label: t("boards.addExisting"), icon: <ListPlus />, onSelect: onAddExisting },
              ]}
            />
          ) : (
            onCreate && <Button variant="ghost" size="sm" iconOnly aria-label={addLabel} aria-expanded={creating} icon={<Plus />} onClick={() => setCreating(true)} />
          )}
          {actions && (
            <Menu
              align="end"
              trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("boards.columnMenu", { column: column.title })} icon={<MoreHorizontal />} />}
              items={[
                ...(actions.left ? [{ label: t("boards.columnLeft"), icon: <ArrowLeft />, onSelect: actions.left }] : []),
                ...(actions.right ? [{ label: t("boards.columnRight"), icon: <ArrowRight />, onSelect: actions.right }] : []),
                ...(actions.rename ? [{ label: t("boards.columnRename"), icon: <Pencil />, onSelect: actions.rename }] : []),
                { kind: "separator" as const },
                { label: t("boards.columnRemove"), icon: <Trash2 />, danger: true, onSelect: actions.remove },
              ]}
            />
          )}
        </div>
      </header>
      <SortableContext items={drawn.map((c) => cardId(c.number))} strategy={verticalListSortingStrategy}>
        <ul ref={setNodeRef} className="flex min-h-24 flex-1 flex-col gap-2">
          {drawn.map((wp) => (
            <SortableCard
              key={wp.id}
              wp={wp}
              href={hrefOf(wp)}
              disabled={!canDrag || (!onRemoveCard && !wp._meta.allowedActions.edit)}
              onRemove={onRemoveCard && (() => onRemoveCard(wp))}
            />
          ))}
          {column.cards.length === 0 && !creating && (
            <li className="list-none">
              <EmptyState compact title={t("boards.emptyColumn")}>
                {t("boards.emptyColumnBody")}
              </EmptyState>
            </li>
          )}
        </ul>
      </SortableContext>
      {hidden > 0 && (
        <Button variant="ghost" size="sm" className="mt-2 w-full" onClick={onMore} aria-label={t("boards.moreLabel", { column: column.title, count: Math.min(COLUMN_PAGE * 2, hidden), total: hidden })}>
          {t("boards.more", { count: Math.min(COLUMN_PAGE * 2, hidden) })}
        </Button>
      )}
      {creating && onCreate && <NewCard column={column} onCreate={onCreate} onClose={() => setCreating(false)} />}
    </section>
  );
}

/** Basic boards: find a work package of the project by title or #number and put it in a list. */
function AddExistingDialog({ board, column, projectId, onClose, onPut }: { board: Board; column: BoardColumn; projectId: string; onClose: () => void; onPut: (wp: WorkPackage) => Promise<unknown> }) {
  const { t } = useTranslation();
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  useEffect(() => {
    const id = setTimeout(() => setQ(text.trim()), 250);
    return () => clearTimeout(id);
  }, [text]);
  const spec: QuerySpec = { v: 1, filters: [], columns: ["number", "subject"], sort: [{ field: "updatedAt", dir: "desc" }], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false, text: q || undefined };
  const result = useWpQuery(spec, projectId, 1, 20);
  const onBoard = new Set(board.columns.flatMap((c) => c.cards.map((w) => w.number)));
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t("boards.addExistingTitle", { column: column.title })} footer={<Button onClick={onClose}>{t("app.close")}</Button>}>
      <Field label={t("boards.searchWp")}>
        <Input autoFocus type="search" value={text} onChange={(e) => setText(e.target.value)} />
      </Field>
      <ul className="mt-3 flex max-h-80 flex-col divide-y divide-border-muted overflow-y-auto" aria-busy={result.isFetching}>
        {result.data?.rows.map(({ wp }) => (
          <li key={wp.id} className="flex items-center justify-between gap-3 py-2">
            <span className="min-w-0 truncate text-sm">
              <span className="text-fg-muted tabular-nums">#{wp.number}</span> <span className="text-fg">{wp.subject}</span>
            </span>
            {onBoard.has(wp.number) && board.columns.find((c) => c.cards.some((w) => w.number === wp.number))?.id === column.id ? (
              <Badge>{t("boards.onBoard")}</Badge>
            ) : (
              <Button size="sm" aria-label={`#${wp.number} ${wp.subject} ${t("boards.put")}`} onClick={() => void onPut(wp).catch(() => undefined)}>
                {t("boards.put")}
              </Button>
            )}
          </li>
        ))}
        {result.data && result.data.rows.length === 0 && <li className="py-6 text-center text-sm text-fg-muted">{t("boards.noMatches")}</li>}
      </ul>
    </Dialog>
  );
}

/** 상위-하위 보드: a column is a work package of the project; its children are the cards. */
function ParentColumnDialog({ board, projectId, onClose, onPick }: { board: Board; projectId: string; onClose: () => void; onPick: (wp: WorkPackage) => Promise<unknown> }) {
  const { t } = useTranslation();
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  useEffect(() => {
    const id = setTimeout(() => setQ(text.trim()), 250);
    return () => clearTimeout(id);
  }, [text]);
  const spec: QuerySpec = { v: 1, filters: [], columns: ["number", "subject"], sort: [{ field: "number", dir: "asc" }], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false, text: q || undefined };
  const result = useWpQuery(spec, projectId, 1, 20);
  const used = new Set(board.columns.map((c) => c.valueId));
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t("boards.addParentColumn")} description={t("boards.addParentColumnHint")} footer={<Button onClick={onClose}>{t("app.close")}</Button>}>
      <Field label={t("boards.searchWp")}>
        <Input autoFocus type="search" value={text} onChange={(e) => setText(e.target.value)} />
      </Field>
      <ul className="mt-3 flex max-h-80 flex-col divide-y divide-border-muted overflow-y-auto" aria-busy={result.isFetching}>
        {result.data?.rows.map(({ wp }) => (
          <li key={wp.id} className="flex items-center justify-between gap-3 py-2">
            <span className="min-w-0 truncate text-sm">
              <span className="text-fg-muted tabular-nums">#{wp.number}</span> <span className="text-fg">{wp.subject}</span>
              {wp.childCount > 0 && <span className="ml-1.5 text-xs text-fg-muted">{t("boards.childCount", { count: wp.childCount })}</span>}
            </span>
            {used.has(wp.id) ? (
              <Badge>{t("boards.onBoard")}</Badge>
            ) : (
              <Button size="sm" aria-label={t("boards.useAsColumn", { name: `#${wp.number} ${wp.subject}` })} onClick={() => void onPick(wp).catch(() => undefined)}>
                {t("boards.useAsColumnShort")}
              </Button>
            )}
          </li>
        ))}
        {result.data && result.data.rows.length === 0 && <li className="py-6 text-center text-sm text-fg-muted">{t("boards.noMatches")}</li>}
      </ul>
    </Dialog>
  );
}

const isText = (f: QueryFilter) => f.field === "subject" && f.op === "contains";

export function BoardPage() {
  const { t } = useTranslation();
  const { id } = useParams();
  const project = useCurrentProject();
  const navigate = useNavigate();
  const toast = useToast();
  const qc = useQueryClient();
  const saved = useBoard(id!);
  // filters being looked through (not saved); null = the saved ones
  const [draft, setDraft] = useState<QueryFilter[] | null>(null);
  const look = useBoardLook(id!, draft);
  const board = draft !== null && look.data ? look : saved;
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [adding, setAdding] = useState<BoardColumn | null>(null);
  const { remove } = useBoardMutations(project.id);
  const options = useFilterOptions(project.id).data;
  const subprojects = useProjects({ pageSize: 100 });
  const [pickingParent, setPickingParent] = useState(false);
  const [naming, setNaming] = useState<{ kind: "board" } | { kind: "column"; column: BoardColumn } | { kind: "newColumn" } | null>(null);
  const [columns, setColumns] = useState<BoardColumn[] | null>(null);
  const [active, setActive] = useState<WorkPackage | null>(null);
  const [origin, setOrigin] = useState<string | null>(null);
  // cards drawn per column (COLUMN_PAGE until 더 보기); kept while the board refreshes after a move
  const [limits, setLimits] = useState<Record<string, number>>({});
  const limitOf = (columnId: string) => limits[columnId] ?? COLUMN_PAGE;
  useEffect(() => setColumns(board.data?.columns ?? null), [board.data]);
  useDocumentTitle(board.data ? `${board.data.name} · ${project.name}` : undefined);
  const base = `/projects/${project.identifier}/boards/${id}`;
  const details = useMatch(`${base}/details/:number/*`);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }), useSensor(KeyboardSensor, { coordinateGetter: boardKeyboardCoordinates }));

  const columnOf = useMemo(() => {
    const map = new Map<string, string>();
    columns?.forEach((c) => {
      map.set(c.id, c.id);
      c.cards.forEach((w) => map.set(cardId(w.number), c.id));
    });
    return map;
  }, [columns]);
  const titleOf = (cid: string | undefined) => columns?.find((c) => c.id === cid)?.title ?? "";

  if (board.isPending)
    return (
      <div className="flex gap-4 px-content pt-16 md:px-8">
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} className="h-80 w-board-col" />
        ))}
      </div>
    );
  if (board.isError) return <ErrorState error={board.error} onRetry={() => board.refetch()} />;
  const b = board.data;
  const cols = columns ?? b.columns;
  const savedFilters = saved.data?.filters ?? [];
  const shownFilters = draft ?? savedFilters;
  const looking = draft !== null && JSON.stringify(draft) !== JSON.stringify(savedFilters);
  const panelSpec: QuerySpec = {
    v: 1, filters: shownFilters.filter((f) => !isText(f)), columns: [], sort: [], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false,
    text: shownFilters.find(isText)?.values?.[0],
  };
  const onPanelSpec = (s: QuerySpec) => setDraft([...s.filters.filter((f) => !isText(f)), ...(s.text ? [{ field: "subject", op: "contains", values: [s.text] } as QueryFilter] : [])]);
  /** A board the server answered with after a change: shown at once unless looking through other filters, then fetched again. */
  const show = (next: Board) => {
    qc.setQueryData(qk.board(b.id), next);
    void qc.invalidateQueries({ queryKey: [...qk.board(b.id), "look"] });
  };

  const onDragStart = (e: DragStartEvent) => {
    const n = numberOf(e.active.id);
    setActive(cols.flatMap((c) => c.cards).find((w) => w.number === n) ?? null);
    setOrigin(columnOf.get(String(e.active.id)) ?? null);
  };

  const onDragOver = ({ active: a, over }: DragOverEvent) => {
    if (!over) return;
    const from = columnOf.get(String(a.id));
    const to = columnOf.get(String(over.id));
    if (!from || !to || from === to) return;
    setColumns((prev) => {
      if (!prev) return prev;
      const card = prev.find((c) => c.id === from)!.cards.find((w) => cardId(w.number) === a.id)!;
      return prev.map((c) => {
        if (c.id === from) return { ...c, cards: c.cards.filter((w) => w.number !== card.number) };
        if (c.id === to) {
          const idx = c.cards.findIndex((w) => cardId(w.number) === over.id);
          const cards = [...c.cards];
          cards.splice(idx >= 0 ? idx : dropEnd(cards.length, limitOf(c.id)), 0, card);
          return { ...c, cards };
        }
        return c;
      });
    });
  };

  const onDragEnd = async ({ active: a, over }: DragEndEvent) => {
    const moved = active;
    setActive(null);
    if (!over || !moved || !columns) return setColumns(b.columns);
    const to = columnOf.get(String(over.id))!;
    let next = columns;
    // reorder inside the target column
    const col = next.find((c) => c.id === to)!;
    const oldIdx = col.cards.findIndex((w) => cardId(w.number) === a.id);
    const overIdx = col.cards.findIndex((w) => cardId(w.number) === over.id);
    if (oldIdx >= 0 && overIdx >= 0 && oldIdx !== overIdx) {
      const cards = [...col.cards];
      const [x] = cards.splice(oldIdx, 1);
      cards.splice(overIdx, 0, x);
      next = next.map((c) => (c.id === to ? { ...c, cards } : c));
      setColumns(next);
    }
    const finalCol = next.find((c) => c.id === to)!;
    const idx = finalCol.cards.findIndex((w) => w.number === moved.number);
    if (to === origin && oldIdx === overIdx) return; // dropped where it started
    try {
      show(await api.boards.move(b.id, { number: moved.number, toColumnId: to, beforeNumber: finalCol.cards[idx + 1]?.number ?? null, lockVersion: moved.lockVersion }));
      invalidateWorkPackages(qc, moved.number);
    } catch (err) {
      setColumns(b.columns);
      toast({
        tone: "danger",
        title: isApiError(err) && err.status === 422 ? t("boards.moveRejected") : isApiError(err) && err.isConflict ? t("app.conflict") : t("app.errorTitle"),
        description: isApiError(err) ? err.message : undefined,
      });
      void board.refetch();
    }
  };

  /** A change that answers with the whole board: show it, or say why not (and rethrow for a dialog to keep its error). */
  const apply = async (p: Promise<Board>, done?: string) => {
    try {
      show(await p);
      void qc.invalidateQueries({ queryKey: qk.boards(project.id) });
      if (done) toast({ tone: "success", title: done });
    } catch (err) {
      toast({ tone: "danger", title: isApiError(err) ? err.title : t("app.errorTitle"), description: isApiError(err) && err.message !== err.title ? err.message : undefined });
      throw err;
    }
  };
  const quietly = (p: Promise<unknown>) => void p.catch(() => undefined);
  const used = new Set(b.columns.map((c) => c.valueId).filter(Boolean));
  const candidates =
    b.kind === "status"
      ? (options?.statuses ?? []).map((s) => ({ id: s.id, name: s.name }))
      : b.kind === "assignee"
        ? (options?.users ?? []).map((u) => ({ id: u.id, name: u.name }))
        : b.kind === "version"
          ? (options?.versions ?? []).map((v) => ({ id: v.id, name: v.name }))
          : b.kind === "subproject"
            ? (subprojects.data?.items ?? []).filter((p) => p.parent?.id === project.id && !p.archivedAt).map((p) => ({ id: p.id, name: p.name }))
            : [];
  const addable = candidates.filter((c) => !used.has(c.id));
  const columnActions = (c: BoardColumn, i: number): ColumnActions | undefined =>
    b.canManage
      ? {
          left: i > 0 ? () => quietly(apply(api.boards.updateColumn(c.id, { position: i - 1 }))) : undefined,
          right: i < cols.length - 1 ? () => quietly(apply(api.boards.updateColumn(c.id, { position: i + 1 }))) : undefined,
          rename: c.valueId ? undefined : () => setNaming({ kind: "column", column: c }),
          remove: () => quietly(apply(api.boards.removeColumn(c.id), t("boards.columnRemoved", { column: c.title }))),
        }
      : undefined;

  const hrefOf = (wp: WorkPackage) => `${base}/details/${wp.number}/overview`;
  const basic = b.kind === "basic";
  // a basic board is the board manager's to arrange; the others move cards by changing the work package
  const canDrag = basic ? b.canManage : true;
  const canCreate = can(project, "wp.create") && (!basic || b.canManage);
  const createIn = (c: BoardColumn) => async (subject: string) => {
    const made = await api.boards.createCard(b.id, c.id, subject);
    show(made.board);
    invalidateWorkPackages(qc, made.workPackage.number);
    toast({ tone: "success", title: t("boards.cardCreated", { number: made.workPackage.number }), description: made.workPackage.subject });
  };

  return (
    <div className="flex min-h-full">
      <div className="min-w-0 flex-1 px-content pb-10 md:px-8">
        <PageHeader
          title={b.name}
          breadcrumbs={[{ label: project.name, href: `/projects/${project.identifier}` }, { label: t("nav.boards"), href: `/projects/${project.identifier}/boards` }, { label: b.name }]}
          actions={
            <>
              <Button aria-expanded={filtersOpen} aria-controls="board-filters" icon={<Filter />} onClick={() => setFiltersOpen((o) => !o)}>
                {t("boards.filters")} {shownFilters.length > 0 && <Counter>{shownFilters.length}</Counter>}
              </Button>
              {b.canManage && (
              <>
                {basic && (
                  <Button icon={<Columns3 />} onClick={() => setNaming({ kind: "newColumn" })}>
                    {t("boards.addColumn")}
                  </Button>
                )}
                {b.kind === "parent_child" && (
                  <Button icon={<Columns3 />} onClick={() => setPickingParent(true)}>
                    {t("boards.addColumn")}
                  </Button>
                )}
                {!basic && b.kind !== "parent_child" && (
                  <Menu
                    align="end"
                    trigger={
                      <Button icon={<Columns3 />} disabled={!addable.length} title={addable.length ? undefined : t("boards.noColumnsToAdd")}>
                        {t("boards.addColumn")}
                      </Button>
                    }
                    items={addable.map((c) => ({ label: c.name, onSelect: () => quietly(apply(api.boards.addColumn(b.id, { valueId: c.id }), t("boards.columnAdded", { column: c.name }))) }))}
                  />
                )}
                <Menu
                  align="end"
                  trigger={<Button iconOnly aria-label={t("boards.boardMenu")} icon={<MoreHorizontal />} />}
                  items={[
                    { label: t("boards.rename"), icon: <Pencil />, onSelect: () => setNaming({ kind: "board" }) },
                    { kind: "separator" as const },
                    { label: t("boards.delete"), icon: <Trash2 />, danger: true, onSelect: () => remove.mutate(b.id, { onSuccess: () => navigate(`/projects/${project.identifier}/boards`) }) },
                  ]}
                />
              </>
              )}
            </>
          }
        >
          <p className="mt-1 text-sm text-fg-muted">{t(`boards.kindHints.${b.kind}`)}</p>
        </PageHeader>
        <div id="board-filters">
          {filtersOpen && <FilterPanel spec={panelSpec} onSpec={onPanelSpec} options={options} />}
          {looking && (
            <div className="mb-3 flex flex-wrap items-center gap-2 rounded-md bg-surface px-3 py-2 text-sm" role="status">
              <span className="text-fg-muted">{t("boards.filtersLook")}</span>
              {b.canManage && (
                <Button size="sm" variant="primary" icon={<Save />} onClick={() => quietly(apply(api.boards.update(b.id, { filters: draft! }), t("boards.filtersSaved")).then(() => setDraft(null)))}>
                  {t("boards.saveFilters")}
                </Button>
              )}
              <Button size="sm" icon={<RotateCcw />} onClick={() => setDraft(null)}>
                {t("boards.resetFilters")}
              </Button>
            </div>
          )}
        </div>
        <DndContext
          sensors={sensors}
          collisionDetection={closestCorners}
          onDragStart={onDragStart}
          onDragOver={onDragOver}
          onDragEnd={(e) => void onDragEnd(e)}
          onDragCancel={() => (setActive(null), setColumns(b.columns))}
          accessibility={{
            screenReaderInstructions: { draggable: t("boards.dragInstructions") },
            announcements: {
              onDragStart: ({ active: a }) => t("boards.announce.start", { number: numberOf(a.id) }),
              onDragOver: ({ over }) => (over ? t("boards.announce.over", { column: titleOf(columnOf.get(String(over.id))) }) : t("boards.announce.nowhere")),
              onDragEnd: ({ over }) => (over ? t("boards.announce.end", { column: titleOf(columnOf.get(String(over.id))) }) : t("boards.announce.nowhere")),
              onDragCancel: () => t("boards.announce.cancel"),
            },
          }}
        >
          <div className="relative flex gap-3 overflow-x-auto pb-4">
            {cols.map((c, i) => (
              <Column
                key={c.id}
                column={c}
                hrefOf={hrefOf}
                canDrag={canDrag}
                onCreate={canCreate ? createIn(c) : undefined}
                onAddExisting={basic && b.canManage ? () => setAdding(c) : undefined}
                onRemoveCard={basic && b.canManage ? (wp) => quietly(apply(api.boards.removeCard(b.id, wp.number), t("boards.cardRemoved", { number: wp.number }))) : undefined}
                actions={columnActions(c, i)}
                limit={limitOf(c.id)}
                onMore={() => setLimits((l) => ({ ...l, [c.id]: (l[c.id] ?? COLUMN_PAGE) + COLUMN_PAGE * 2 }))}
              />
            ))}
          </div>
          <DragOverlay>{active ? <WorkPackageCard wp={toCard(active)} dragging className="w-[284px]" /> : null}</DragOverlay>
        </DndContext>
        {pickingParent && (
          <ParentColumnDialog
            board={b}
            projectId={project.id}
            onClose={() => setPickingParent(false)}
            onPick={(wp) => apply(api.boards.addColumn(b.id, { valueId: wp.id }), t("boards.columnAdded", { column: `#${wp.number} ${wp.subject}` })).then(() => setPickingParent(false))}
          />
        )}
        {naming?.kind === "board" && (
          <NameDialog title={t("boards.rename")} label={t("boards.name")} initial={b.name} onClose={() => setNaming(null)} onSave={(name) => apply(api.boards.update(b.id, { name }), t("app.saved"))} />
        )}
        {naming?.kind === "newColumn" && (
          <NameDialog
            title={t("boards.addColumn")}
            label={t("boards.columnName")}
            initial=""
            onClose={() => setNaming(null)}
            onSave={(name) => apply(api.boards.addColumn(b.id, { name }), t("boards.columnAdded", { column: name }))}
          />
        )}
        {adding && (
          <AddExistingDialog
            board={b}
            column={adding}
            projectId={project.id}
            onClose={() => setAdding(null)}
            onPut={(wp) => apply(api.boards.addCard(b.id, adding.id, wp.number), t("boards.cardAdded", { number: wp.number, column: adding.title }))}
          />
        )}
        {naming?.kind === "column" && (
          <NameDialog
            title={t("boards.columnRename")}
            label={t("boards.columnName")}
            initial={naming.column.title}
            onClose={() => setNaming(null)}
            onSave={(name) => apply(api.boards.updateColumn(naming.column.id, { name }), t("app.saved"))}
          />
        )}
      </div>
      {details && (
        <aside
          aria-label={t("wp.detailsPanel")}
          className="fixed inset-x-0 top-header bottom-0 z-(--pms-z-modal) flex flex-col bg-bg md:sticky md:inset-auto md:top-header md:z-auto md:h-[calc(100dvh-var(--pms-layout-header-h))] md:w-split md:shrink-0 md:border-l md:border-border-muted"
        >
          <Outlet />
        </aside>
      )}
    </div>
  );
}

export function BoardDetailRoute() {
  const { id, number, tab } = useParams();
  const project = useCurrentProject();
  const n = Number(number);
  const base = `/projects/${project.identifier}/boards/${id}`;
  const current = asDetailTab(tab, "overview");
  return (
    <WorkPackageDetail
      key={n}
      number={n}
      mode="panel"
      tab={current}
      tabHref={(x) => `${base}/details/${n}/${x}`}
      closeHref={base}
      fullHref={`/projects/${project.identifier}/work-packages/${n}/${current === "overview" ? "activity" : current}`}
      afterDeleteHref={base}
    />
  );
}
