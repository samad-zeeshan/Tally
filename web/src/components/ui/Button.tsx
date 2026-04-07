// The one button. Variants are styling only; loading disables the button and swaps in a spinner so a
// double-submit is impossible while a request is in flight.
import type { ComponentPropsWithoutRef } from "react";

interface ButtonProps extends ComponentPropsWithoutRef<"button"> {
  variant?: "primary" | "secondary" | "ghost";
  loading?: boolean;
}

export function Button({ variant = "primary", loading = false, className, children, disabled, type, ...rest }: ButtonProps) {
  return (
    <button
      // Explicit default: a bare <button> inside a form submits it, which is never what a kit button means.
      type={type ?? "button"}
      className={["btn", `btn-${variant}`, className].filter(Boolean).join(" ")}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading && <span className="spinner" aria-hidden="true" />}
      {children}
    </button>
  );
}
