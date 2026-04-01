// Layout and the little shared state: the account list, the selected account, and a refresh counter
// bumped after any create or transfer. Props down, callbacks up; no router, no state library, no context.
import { useCallback, useEffect, useState } from "react";
import { ApiError, listAccounts } from "./api/client";
import type { Account } from "./api/types";
import { AccountsPanel } from "./components/AccountsPanel";
import { AccountDetail } from "./components/AccountDetail";
import { TransferForm } from "./components/TransferForm";

export default function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [refreshSeq, setRefreshSeq] = useState(0);
  const [loadError, setLoadError] = useState<string | null>(null);

  // The listing already excludes world, so there is nothing to filter client-side.
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
      });
    return () => {
      cancelled = true;
    };
  }, [refreshSeq]);

  const onChanged = useCallback(() => setRefreshSeq((seq) => seq + 1), []);

  return (
    <>
      <header className="header">
        <span className="brand">Tally</span>
        <span className="tagline">double-entry money movement</span>
      </header>
      <main className="container">
        {loadError && <p className="notice notice-error">{loadError}</p>}
        <div className="grid">
          <AccountsPanel accounts={accounts} selectedId={selectedId} onSelect={setSelectedId} onChanged={onChanged} />
          <AccountDetail accountId={selectedId} refreshSeq={refreshSeq} />
        </div>
        <TransferForm accounts={accounts} onChanged={onChanged} />
      </main>
    </>
  );
}
