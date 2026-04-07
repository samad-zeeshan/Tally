// Layout and the little shared state: the account list, the selected account, a refresh counter bumped
// after any change, and the toast shelf. Props down, callbacks up; no router, no state library.
import { useCallback, useEffect, useState } from "react";
import { ApiError, listAccounts } from "./api/client";
import type { Account } from "./api/types";
import { AccountsPanel } from "./components/AccountsPanel";
import { AccountDetail } from "./components/AccountDetail";
import { BooksBadge } from "./components/BooksBadge";
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
          <span className="tagline">double-entry money movement</span>
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
          </div>
          <TransferForm accounts={accounts} onChanged={onChanged} onToast={pushToast} />
        </ErrorBoundary>
      </main>
      <footer className="footer">every transfer is two postings that sum to zero — the whole book always nets to 0.00</footer>
      <ToastShelf toasts={toasts} onDismiss={dismissToast} />
    </>
  );
}
