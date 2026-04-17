// Layout and the little shared state: the account list, the selected account, a refresh counter bumped
// after any change, and the toast shelf. Props down, callbacks up; no router, no state library.
import { useCallback, useEffect, useState } from "react";
import { ApiError, listAccounts } from "./api/client";
import type { Account } from "./api/types";
import { AccountsPanel } from "./components/AccountsPanel";
import { AccountDetail } from "./components/AccountDetail";
import { BooksBadge } from "./components/BooksBadge";
import { ExplainerPanel } from "./components/ExplainerPanel";
import { TransferForm } from "./components/TransferForm";
import { ErrorBoundary } from "./components/ui/ErrorBoundary";
import { ThemeToggle } from "./components/ui/ThemeToggle";
import { ToastShelf, type ToastItem, type ToastKind } from "./components/ui/Toasts";

export default function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [refreshSeq, setRefreshSeq] = useState(0);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  // The listing already excludes world, so there is nothing to filter client-side. The stale list stays
  // on screen while a refresh runs, so a transfer never blanks the page.
  useEffect(() => {
    let cancelled = false;
    listAccounts()
      .then((list) => {
        if (!cancelled) {
          setAccounts(list);
          setLoadError(null);
        }
      })
      .catch((caught: ApiError) => {
        if (!cancelled) {
          setLoadError(caught.message);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [refreshSeq]);

  const onChanged = useCallback(() => setRefreshSeq((seq) => seq + 1), []);

  const pushToast = useCallback((kind: ToastKind, message: string) => {
    // Keep the shelf short; the oldest toast makes room rather than stacking forever.
    setToasts((current) => [...current.slice(-3), { id: crypto.randomUUID(), kind, message }]);
  }, []);

  const dismissToast = useCallback((id: string) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  // The page background carries a faint accent spotlight that trails the pointer (see body's gradient).
  // Mouse-only chrome: skipped for coarse pointers and for reduced-motion users, who keep the resting
  // glow at the top of the page.
  useEffect(() => {
    if (!window.matchMedia("(pointer: fine)").matches || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      return;
    }
    const root = document.documentElement;
    let frame = 0;
    let x = 0;
    let y = 0;
    const onMove = (event: PointerEvent) => {
      x = event.clientX;
      y = event.clientY;
      if (frame) {
        return; // one style write per frame, however fast the pointer moves
      }
      frame = requestAnimationFrame(() => {
        frame = 0;
        root.style.setProperty("--glow-x", `${x}px`);
        root.style.setProperty("--glow-y", `${y}px`);
      });
    };
    window.addEventListener("pointermove", onMove);
    return () => {
      window.removeEventListener("pointermove", onMove);
      cancelAnimationFrame(frame);
    };
  }, []);

  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            Σ
          </span>
          Tally
          <span className="tagline">every payment written down twice</span>
        </div>
        <div className="header-actions">
          <BooksBadge refreshSeq={refreshSeq} />
          <ThemeToggle />
        </div>
      </header>
      <main id="main" className="container">
        {loadError && (
          <p className="notice notice-error" role="alert">
            {loadError}
          </p>
        )}
        <ErrorBoundary>
          <ExplainerPanel onToast={pushToast} onSelect={setSelectedId} onChanged={onChanged} />
          {/* The detail panel is the hero: it holds the tall right column while the accounts list and
              the transfer form share the left rail (grid areas in styles.css). */}
          <div className="grid">
            <AccountsPanel
              accounts={accounts}
              selectedId={selectedId}
              loading={loading}
              onSelect={setSelectedId}
              onChanged={onChanged}
              onToast={pushToast}
            />
            <AccountDetail accountId={selectedId} accounts={accounts} refreshSeq={refreshSeq} />
            <TransferForm accounts={accounts} onChanged={onChanged} onToast={pushToast} />
          </div>
        </ErrorBoundary>
      </main>
      <footer className="footer">every payment is written down twice, once leaving and once arriving, so the book always totals 0.00</footer>
      <ToastShelf toasts={toasts} onDismiss={dismissToast} />
    </>
  );
}
