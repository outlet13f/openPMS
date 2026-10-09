import { CalendarClock } from "lucide-react";
import { cn } from "@/lib/cn";
import { Avatar } from "@/components/ui/avatar";
import { StatusPill, TypeLabel } from "@/components/ui/badges";

export interface CardWorkPackage {
  number: number;
  subject: string;
  projectName: string;
  type: { name: string; color: string };
  status?: { name: string; color: string };
  assignee?: { id: string; name: string };
  startDate?: string;
  dueDate?: string;
  overdue?: boolean;
  autoScheduled?: boolean;
}

function fmt(d: string) {
  const [y, m, day] = d.split("-");
  return `${y}.${m}.${day}`;
}

/**
 * Board card and mobile list card. The whole card is one link (opens split view);
 * on boards it is also the drag handle (dnd-kit: Space to lift, arrows to move, Space to drop).
 */
export function WorkPackageCard({ wp, showStatus, dragging, className }: { wp: CardWorkPackage; showStatus?: boolean; dragging?: boolean; className?: string }) {
  return (
    <article
      className={cn(
        "relative rounded-md border border-border bg-bg p-3 shadow-card",
        "before:absolute before:inset-x-0 before:top-0 before:h-0.5 before:rounded-t-md before:bg-(--type-color)",
        "hover:border-border-input focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-focus-ring",
        dragging && "rotate-1 shadow-drag",
        className,
      )}
      style={{ ["--type-color" as string]: wp.type.color }}
    >
      <div className="mb-1 flex items-center gap-2 text-xs text-fg-muted">
        {showStatus && wp.status && <StatusPill size="sm" name={wp.status.name} color={wp.status.color} />}
        <span className="tabular-nums">#{wp.number}</span>
        <span aria-hidden="true">·</span>
        <span className="truncate">{wp.projectName}</span>
      </div>
      <h3 className="text-sm leading-5 text-fg">
        <a href={`#wp-${wp.number}`} className="outline-none after:absolute after:inset-0">
          <TypeLabel name={wp.type.name} color={wp.type.color} className="mr-1.5" />
          {wp.subject}
        </a>
      </h3>
      <div className="mt-2 flex items-center justify-between gap-2">
        {wp.assignee ? <Avatar id={wp.assignee.id} name={wp.assignee.name} size="xs" /> : <span className="text-xs text-fg-muted">담당자 없음</span>}
        {(wp.startDate || wp.dueDate) && (
          <span className="inline-flex items-center gap-1 text-xs text-fg-muted tabular-nums">
            {wp.autoScheduled && <CalendarClock className="size-3.5" aria-label="자동 일정" />}
            {wp.startDate && fmt(wp.startDate)}
            {wp.startDate && wp.dueDate && " – "}
            {wp.dueDate && <span className={cn(wp.overdue && "font-semibold text-danger")}>{fmt(wp.dueDate)}</span>}
            {wp.overdue && <span className="sr-only">(기한 지남)</span>}
          </span>
        )}
      </div>
    </article>
  );
}
