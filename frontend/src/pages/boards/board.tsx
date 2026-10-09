// S15 보드: drag cards between columns (changes status/assignee/version through the API, workflow enforced).
import { useEffect, useMemo, useState, type KeyboardEventHandler, type PointerEventHandler } from "react";
import { Link, Outlet, useMatch, useNavigate, useParams } from "react-router";
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
import { GripVertical, MoreHorizontal, Plus, Trash2 } from "lucide-react";
import { api, isApiError, type BoardColumn, type WorkPackage } from "@/api";
import { invalidateWorkPackages, qk, useBoard, useBoardMutations } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Counter } from "@/components/ui/badges";
import { EmptyState, useToast } from "@/components/ui/feedback";
import { Menu } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { WorkPackageCard } from "@/components/domain/work-package-card";
import { cn } from "@/lib/cn";
import { ErrorState, PageHeader, useDocumentTitle } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { WorkPackageDetail, type DetailTab } from "@/features/work-packages/detail";

const cardId = (n: number) => `card-${n}`;

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
function SortableCard({ wp, href, disabled }: { wp: WorkPackage; href: string; disabled: boolean }) {
  const { t } = useTranslation();
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({ id: cardId(wp.number), disabled });
  return (
    <li ref={setNodeRef} style={{ transform: CSS.Transform.toString(transform), transition }} className={cn(isDragging && "opacity-40")} onPointerDown={listeners?.onPointerDown as PointerEventHandler<HTMLLIElement> | undefined}>
      <WorkPackageCard
        wp={toCard(wp)}
        href={href}
        handle={
          !disabled && (
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
          )
        }
      />
    </li>
  );
}

function Column({ column, hrefOf, canDrag, addHref }: { column: BoardColumn; hrefOf: (wp: WorkPackage) => string; canDrag: boolean; addHref?: string }) {
  const { t } = useTranslation();
  const { setNodeRef, isOver } = useDroppable({ id: column.id });
  return (
    <section aria-label={column.title} className={cn("flex w-board-col shrink-0 flex-col rounded-lg bg-surface p-2", isOver && "outline-2 outline-accent")}>
      <header className="flex items-center justify-between px-1 pt-1 pb-2">
        <h2 className="flex min-w-0 items-center gap-2 text-sm font-semibold text-fg">
          {column.color && <span className="size-2.5 shrink-0 rounded-full" style={{ backgroundColor: column.color }} aria-hidden="true" />}
          <span className="truncate">{column.title}</span>
          <Counter>{column.cards.length}</Counter>
        </h2>
        {addHref && (
          <Button asChild variant="ghost" size="sm" iconOnly aria-label={t("boards.addCard", { column: column.title })} icon={<Plus />}>
            <Link to={addHref} />
          </Button>
        )}
      </header>
      <SortableContext items={column.cards.map((c) => cardId(c.number))} strategy={verticalListSortingStrategy}>
        <ul ref={setNodeRef} className="flex min-h-24 flex-1 flex-col gap-2">
          {column.cards.map((wp) => (
            <SortableCard key={wp.id} wp={wp} href={hrefOf(wp)} disabled={!canDrag || !wp._meta.allowedActions.edit} />
          ))}
          {column.cards.length === 0 && (
            <li className="list-none">
              <EmptyState compact title={t("boards.emptyColumn")}>
                {t("boards.emptyColumnBody")}
              </EmptyState>
            </li>
          )}
        </ul>
      </SortableContext>
    </section>
  );
}

export function BoardPage() {
  const { t } = useTranslation();
  const { id } = useParams();
  const project = useCurrentProject();
  const navigate = useNavigate();
  const toast = useToast();
  const qc = useQueryClient();
  const board = useBoard(id!);
  const { remove } = useBoardMutations(project.id);
  const [columns, setColumns] = useState<BoardColumn[] | null>(null);
  const [active, setActive] = useState<WorkPackage | null>(null);
  const [origin, setOrigin] = useState<string | null>(null);
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
          cards.splice(idx >= 0 ? idx : cards.length, 0, card);
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
      const saved = await api.boards.move(b.id, { number: moved.number, toColumnId: to, beforeNumber: finalCol.cards[idx + 1]?.number ?? null, lockVersion: moved.lockVersion });
      qc.setQueryData(qk.board(b.id), saved);
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

  const hrefOf = (wp: WorkPackage) => `${base}/details/${wp.number}/overview`;
  const canDrag = b.kind !== "basic";
  const addHref = can(project, "wp.create") ? `/projects/${project.identifier}/work-packages/new` : undefined;

  return (
    <div className="flex min-h-full">
      <div className="min-w-0 flex-1 px-content pb-10 md:px-8">
        <PageHeader
          title={b.name}
          breadcrumbs={[{ label: project.name, href: `/projects/${project.identifier}` }, { label: t("nav.boards"), href: `/projects/${project.identifier}/boards` }, { label: b.name }]}
          actions={
            b.canManage && (
              <Menu
                align="end"
                trigger={<Button iconOnly aria-label={t("app.more")} icon={<MoreHorizontal />} />}
                items={[{ label: t("boards.delete"), icon: <Trash2 />, danger: true, onSelect: () => remove.mutate(b.id, { onSuccess: () => navigate(`/projects/${project.identifier}/boards`) }) }]}
              />
            )
          }
        >
          <p className="mt-1 text-sm text-fg-muted">{t(`boards.kindHints.${b.kind}`)}</p>
        </PageHeader>
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
            {cols.map((c) => (
              <Column key={c.id} column={c} hrefOf={hrefOf} canDrag={canDrag} addHref={addHref} />
            ))}
          </div>
          <DragOverlay>{active ? <WorkPackageCard wp={toCard(active)} dragging className="w-[284px]" /> : null}</DragOverlay>
        </DndContext>
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
  const current = (["overview", "activity", "relations"].includes(tab ?? "") ? tab : "overview") as DetailTab;
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
