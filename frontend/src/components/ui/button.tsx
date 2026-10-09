import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { Slot } from "radix-ui";
import { cn } from "@/lib/cn";
import { Spinner } from "./spinner";

const button = cva(
  [
    "relative inline-flex shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-md font-medium",
    "transition-colors duration-(--pms-motion-fast) ease-standard select-none",
    "disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50",
    "[&_svg]:size-4 [&_svg]:shrink-0",
  ],
  {
    variants: {
      variant: {
        primary: "bg-accent text-on-accent hover:bg-accent-hover",
        secondary: "border border-border bg-surface text-fg hover:bg-surface-hover active:bg-surface-selected",
        ghost: "text-fg hover:bg-surface-hover active:bg-surface-selected",
        danger: "bg-danger text-on-danger hover:bg-danger-hover",
        "danger-ghost": "text-danger hover:bg-danger-subtle",
        link: "h-auto px-0 text-accent underline-offset-2 hover:underline",
      },
      size: {
        sm: "h-control-sm px-2.5 text-xs",
        md: "h-control-md px-3 text-sm",
        lg: "h-control-lg px-4 text-sm",
      },
      iconOnly: { true: "px-0" },
    },
    compoundVariants: [
      { iconOnly: true, size: "sm", class: "w-control-sm" },
      { iconOnly: true, size: "md", class: "w-control-md" },
      { iconOnly: true, size: "lg", class: "w-control-lg" },
    ],
    defaultVariants: { variant: "secondary", size: "md" },
  },
);

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>,
    Omit<VariantProps<typeof button>, "iconOnly"> {
  /** Shows a spinner, keeps the label for screen readers and blocks clicks. */
  loading?: boolean;
  icon?: ReactNode;
  /** Icon-only buttons must pass `aria-label`; it is also used as the tooltip text. */
  iconOnly?: boolean;
  asChild?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { className, variant, size, loading, icon, iconOnly, asChild, children, disabled, type = "button", ...rest },
  ref,
) {
  if (asChild) {
    // The child element (e.g. a router <Link>) becomes the root; the icon is rendered inside it.
    return (
      <Slot.Root ref={ref} className={cn(button({ variant, size, iconOnly }), className)} {...rest}>
        {icon}
        <Slot.Slottable>{children}</Slot.Slottable>
      </Slot.Root>
    );
  }
  return (
    <button
      ref={ref}
      type={type}
      className={cn(button({ variant, size, iconOnly }), className)}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? (
        <>
          <span className="absolute inset-0 flex items-center justify-center">
            <Spinner size={14} />
          </span>
          <span className="invisible inline-flex items-center gap-1.5">
            {icon}
            {children}
          </span>
        </>
      ) : (
        <>
          {icon}
          {children}
        </>
      )}
    </button>
  );
});

export { button as buttonClasses };
