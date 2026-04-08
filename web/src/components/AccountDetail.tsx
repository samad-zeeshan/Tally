// Balance, trend, and paginated statement for the selected account. Switching accounts shows a full
// skeleton; a refresh after a transfer keeps the current data on screen and swaps it in silently, with
// only a thin sweep along the card's top edge admitting that work is happening.
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { ApiError, getAccount, getStatement } from "../api/client";
import type { Account, StatementEntry } from "../api/types";
import { formatMinor } from "../lib/money";
import { formatDateTime, timeAgo } from "../lib/format";
import { AnimatedNumber } from "./ui/AnimatedNumber";
import { Button } from "./ui/Button";
import { EmptyState } from "./ui/EmptyState";
import { Skeleton } from "./ui/Skeleton";
import { Sparkline } from "./ui/Sparkline";
import { BookIcon } from "./ui/icons";

const WORLD_ID = "00000000-0000-0000-0000-000000000000";

interface Props {
  accountId: string | null;
  accounts: Account[];
  refreshSeq: number;
}

export function AccountDetail({ accountId, accounts, refreshSeq }: Props) {
  const [account, setAccount] = useState<Account | null>(null);
  const [entries, setEntries] = useState<StatementEntry[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const lastIdRef = useRef<string | null>(null);
  // Each row's reveal delay is assigned once, the first time that posting renders, so a Load more
  // staggers only the appended page and never restarts rows already on screen.
  const rowDelays = useRef(new Map<number, number>());

  const names = useMemo(() => new Map(accounts.map((a) => [a.id, a.name])), [accounts]);

  useEffect(() => {
    if (accountId === null) {
      setAccount(null);
      setEntries([]);
      setCursor(null);
      setError(null);
      lastIdRef.current = null;
      return;
    }
    const switched = accountId !== lastIdRef.current;
    lastIdRef.current = accountId;
    if (switched) {
      setAccount(null);
      setEntries([]);
      setCursor(null);
      rowDelays.current.clear();
    }
    setLoading(true);
    setError(null);
    let cancelled = false;
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
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
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
    setLoadingMore(true);
    try {
      const page = await getStatement(accountId, cursor);
      setEntries((previous) => [...previous, ...page.entries]);
      setCursor(page.nextCursor);
    } catch (caught) {
      setError((caught as ApiError).message);
    } finally {
      setLoadingMore(false);
    }
  }, [accountId, cursor]);

  // Entries arrive newest first; the trend wants them in the order they happened.
  const trend = useMemo(() => [...entries].reverse().map((entry) => entry.balanceAfterMinor), [entries]);

  const counterparty = (id: string) => {
    if (id === WORLD_ID) {
      return <span className="pill world">world</span>;
    }
    return names.get(id) ?? <span className="account-meta">{id.slice(0, 8)}…</span>;
  };

  if (accountId === null) {
    return (
      <section className="card card-detail">
        <h2>Statement</h2>
        <EmptyState icon={<BookIcon />} title="Select an account" hint="Its balance, trend, and full statement will show here." />
      </section>
    );
  }

  const initialLoad = loading && account === null;
  const now = Date.now();

  // Delays are handed out in render order, so the first unseen row of a batch leads and the cap keeps
  // deep pages from waiting out a long queue.
  let unseenInThisRender = 0;
  const delayFor = (postingId: number): string => {
    let delay = rowDelays.current.get(postingId);
    if (delay === undefined) {
      delay = Math.min(unseenInThisRender++, 8) * 40;
      rowDelays.current.set(postingId, delay);
    }
    return `${delay}ms`;
  };

  return (
    <section
      className={"card card-detail" + (loading && account !== null ? " refreshing" : "")}
      aria-busy={loading || undefined}
    >
      <h2>{account ? account.name : "Statement"}</h2>
      {error && (
        <p className="notice notice-error" role="alert">
          {error}
        </p>
      )}

      {initialLoad ? (
        <div style={{ marginTop: 12 }}>
          <Skeleton lines={5} tall />
        </div>
      ) : (
        account && (
          <>
            <div className="balance-row">
              <div>
                <AnimatedNumber className={"balance-big" + (account.balanceMinor < 0 ? " amount negative" : "")} value={account.balanceMinor} />
                <span className="balance-caption">
                  current balance · {entries.length} {entries.length === 1 ? "entry" : "entries"} below
                </span>
              </div>
              {trend.length > 1 && (
                <Sparkline values={trend} ariaLabel={`Balance after each of the last ${trend.length} postings`} />
              )}
            </div>
            {entries.length === 0 ? (
              <EmptyState icon={<BookIcon />} title="No entries yet" hint="Transfers in and out will land here, newest first." />
            ) : (
              <>
                <div className="table-scroll">
                  <table className="statement">
                    <thead>
                      <tr>
                        <th scope="col">When</th>
                        <th scope="col">Counterparty</th>
                        <th scope="col" className="amount">Amount</th>
                        <th scope="col" className="amount">Balance</th>
                      </tr>
                    </thead>
                    <tbody>
                      {entries.map((entry) => (
                        <tr key={entry.postingId} style={{ "--row-delay": delayFor(entry.postingId) } as CSSProperties}>
                          <td>
                            <time dateTime={entry.createdAt} title={formatDateTime(entry.createdAt)}>
                              {timeAgo(entry.createdAt, now)}
                            </time>
                          </td>
                          <td>{counterparty(entry.counterpartyAccountId)}</td>
                          <td className="amount">
                            <span className={"pill " + (entry.amountMinor < 0 ? "neg" : "pos")}>
                              {entry.amountMinor > 0 ? "+" : ""}
                              {formatMinor(entry.amountMinor)}
                            </span>
                          </td>
                          <td className="amount balance-after">{formatMinor(entry.balanceAfterMinor)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {cursor !== null && (
                  <div className="load-more-row">
                    <Button variant="secondary" loading={loadingMore} onClick={loadMore}>
                      Load more
                    </Button>
                  </div>
                )}
              </>
            )}
          </>
        )
      )}
    </section>
  );
}
