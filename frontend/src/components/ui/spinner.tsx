import { cn } from "@/lib/cn";

export function Spinner({ size = 16, className, label }: { size?: number; className?: string; label?: string }) {
  return (
    <span role={label ? "status" : undefined} className={cn("inline-flex", className)}>
      <svg
        width={size}
        height={size}
        viewBox="0 0 16 16"
        className="animate-spin motion-reduce:animate-none"
        aria-hidden="true"
      >
        <circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" strokeOpacity=".25" strokeWidth="2" />
        <path d="M14.5 8A6.5 6.5 0 0 0 8 1.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      </svg>
      {label && <span className="sr-only">{label}</span>}
    </span>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <span aria-hidden="true" className={cn("block animate-pulse rounded-sm bg-surface-selected motion-reduce:animate-none", className)} />;
}
