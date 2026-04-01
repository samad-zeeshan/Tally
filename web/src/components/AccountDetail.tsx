// Balance and paginated statement for the selected account. Reloads on selection or after any change,
// and appends the next keyset page on Load more.
import { useCallback, useEffect, useState } from "react";
import { ApiError, getAccount, getStatement } from "../api/client";
import type { Account, StatementEntry } from "../api/types";
import { formatMinor } from "../lib/money";

interface Props {
  accountId: string | null;
  refreshSeq: number;
}

export function AccountDetail({ accountId, refreshSeq }: Props) {
  const [account, setAccount] = useState<Account | null>(null);
  const [entries, setEntries] = useState<StatementEntry[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (accountId === null) {
      setAccount(null);
      setEntries([]);
      setCursor(null);
      return;
    }
    let cancelled = false;
    setError(null);
    Promise.all([getAccount(accountId), getStatement(accountId)])
      .then(([loaded, page]) => {
        if (cancelled) {
          return;
        }
        setAccount(loaded);
        setEntries(page.entries);
        setCursor(page.nextCursor);
      })
      .catch((caught: ApiError) => {
        if (!cancelled) {
          setError(caught.message);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [accountId, refreshSeq]);

  const loadMore = useCallback(async () => {
    if (accountId === null || cursor === null) {
      return;
    }
    try {
      const page = await getStatement(accountId, cursor);
      setEntries((previous) => [...previous, ...page.entries]);
      setCursor(page.nextCursor);
    } catch (caught) {
      setError((caught as ApiError).message);
    }
  }, [accountId, cursor]);

  if (accountId === null) {
    return (
      <section className="card">
        <h2>Statement</h2>
        <p className="muted">Select an account to see its balance and statement.</p>
      </section>
    );
  }

  return (
    <section className="card">
      <h2>{account ? account.name : "Statement"}</h2>
      {error && <p className="notice notice-error">{error}</p>}
      {account && <p className="balance">{formatMinor(account.balanceMinor)}</p>}
      <table className="statement">
        <thead>
          <tr>
            <th>Date</th>
            <th>Counterparty</th>
            <th className="amount">Amount</th>
            <th className="amount">Balance</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.postingId}>
              <td>{new Date(entry.createdAt).toLocaleString()}</td>
              <td className="account-id">{entry.counterpartyAccountId.slice(0, 8)}…</td>
              <td className={"amount" + (entry.amountMinor < 0 ? " negative" : "")}>{formatMinor(entry.amountMinor)}</td>
              <td className="amount">{formatMinor(entry.balanceAfterMinor)}</td>
            </tr>
          ))}
          {entries.length === 0 && (
            <tr>
              <td colSpan={4} className="empty">No entries yet.</td>
            </tr>
          )}
        </tbody>
      </table>
      {cursor !== null && (
        <button type="button" className="secondary" onClick={loadMore}>Load more</button>
      )}
    </section>
  );
}
