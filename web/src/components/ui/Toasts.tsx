// Toast shelf. Each card owns its own dismiss timer, pauses on hover with the remaining time kept, and
// announces politely; errors interrupt with role=alert and stay up longer. Dismissal plays a short exit
// animation and only then removes the card from state.
import { useCallback, useEffect, useRef, useState } from "react";
import { CircleCheckIcon, AlertIcon, XIcon } from "./icons";

export type ToastKind = "success" | "error" | "info";

export interface ToastItem {
  id: string;
  kind: ToastKind;
  message: string;
}

interface ShelfProps {
  toasts: ToastItem[];
  onDismiss: (id: string) => void;
}

export function ToastShelf({ toasts, onDismiss }: ShelfProps) {
  if (toasts.length === 0) {
    return null;
  }
  return (
    <div className="toast-shelf">
      {toasts.map((toast) => (
        <ToastCard key={toast.id} toast={toast} onDismiss={onDismiss} />
      ))}
    </div>
  );
}

function ToastCard({ toast, onDismiss }: { toast: ToastItem; onDismiss: (id: string) => void }) {
  const duration = toast.kind === "error" ? 7000 : 4200;
  const remaining = useRef(duration);
  const startedAt = useRef(0);
  const timer = useRef(0);
  const [leaving, setLeaving] = useState(false);

  const arm = useCallback(() => {
    startedAt.current = Date.now();
    timer.current = window.setTimeout(() => setLeaving(true), remaining.current);
  }, []);

  useEffect(() => {
    arm();
    return () => clearTimeout(timer.current);
  }, [arm]);

  const pause = () => {
    clearTimeout(timer.current);
    remaining.current -= Date.now() - startedAt.current;
  };

  return (
    <div
      className={`toast toast-${toast.kind}` + (leaving ? " toast-leaving" : "")}
      role={toast.kind === "error" ? "alert" : "status"}
      onMouseEnter={pause}
      onMouseLeave={arm}
      onAnimationEnd={(event) => {
        // The card removes itself only after the exit animation; the enter animation ends here too,
        // so match on the leaving state, not just any animation finishing.
        if (leaving && event.animationName === "slide-out") {
          onDismiss(toast.id);
        }
      }}
    >
      <span className="toast-icon">{toast.kind === "error" ? <AlertIcon /> : <CircleCheckIcon />}</span>
      <span className="toast-msg">{toast.message}</span>
      <button className="toast-close" onClick={() => setLeaving(true)} aria-label="Dismiss notification">
        <XIcon />
      </button>
      <span className="toast-progress" style={{ animationDuration: `${duration}ms` }} aria-hidden="true" />
    </div>
  );
}
