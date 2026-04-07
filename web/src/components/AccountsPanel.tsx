// The create form (name plus an optional opening balance) and the account list. The opening-balance
// field funds a new account through the world-funded creation path, so the demo needs no seeding step.
import { useState, type FormEvent } from "react";
import { ApiError, createAccount } from "../api/client";
import type { Account } from "../api/types";
import { parseAmountToMinor } from "../lib/money";
import { AnimatedNumber } from "./ui/AnimatedNumber";
import { Button } from "./ui/Button";
import { CopyButton } from "./ui/CopyButton";
import { EmptyState } from "./ui/EmptyState";
import { Field } from "./ui/Field";
import { Skeleton } from "./ui/Skeleton";
import { WalletIcon } from "./ui/icons";
import type { ToastKind } from "./ui/Toasts";

interface Props {
  accounts: Account[];
  selectedId: string | null;
  loading: boolean;
  onSelect: (id: string) => void;
  onChanged: () => void;
  onToast: (kind: ToastKind, message: string) => void;
}

// A stable hue per account so the avatar color is recognizable across sessions.
function hueFromId(id: string): number {
  let hue = 0;
  for (let i = 0; i < id.length; i++) {
    hue = (hue * 31 + id.charCodeAt(i)) % 360;
  }
  return hue;
}

export function AccountsPanel({ accounts, selectedId, loading, onSelect, onChanged, onToast }: Props) {
  const [name, setName] = useState("");
  const [opening, setOpening] = useState("");
  const [openingError, setOpeningError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setOpeningError(null);
    let openingMinor: number | undefined;
    if (opening.trim() !== "") {
      const parsed = parseAmountToMinor(opening);
      if (parsed === null) {
        setOpeningError("An amount like 50.00, or leave it blank.");
        return;
      }
      openingMinor = parsed;
    }
    setBusy(true);
    try {
      const created = await createAccount(name.trim(), openingMinor);
      setName("");
      setOpening("");
      onToast("success", `Account "${created.name}" opened`);
      onChanged();
      onSelect(created.id);
    } catch (caught) {
      setError((caught as ApiError).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <div className="card-header">
        <h2>Accounts</h2>
        {accounts.length > 0 && <span className="count-chip">{accounts.length}</span>}
      </div>

      <form onSubmit={handleCreate}>
        <div className="create-grid">
          <Field label="Name">
            {(a11y) => (
              <input
                {...a11y}
                placeholder="e.g. Alice"
                value={name}
                onChange={(event) => setName(event.target.value)}
                required
              />
            )}
          </Field>
          <Field label="Opening balance" error={openingError}>
            {(a11y) => (
              <input
                {...a11y}
                placeholder="optional"
                value={opening}
                onChange={(event) => setOpening(event.target.value)}
                inputMode="decimal"
              />
            )}
          </Field>
          <Button type="submit" loading={busy} disabled={name.trim() === ""}>
            Open
          </Button>
        </div>
      </form>

      {error && (
        <p className="notice notice-error" role="alert">
          {error}
        </p>
      )}

      {loading && accounts.length === 0 ? (
        <div style={{ marginTop: 16 }}>
          <Skeleton lines={3} />
        </div>
      ) : accounts.length === 0 ? (
        <EmptyState
          icon={<WalletIcon />}
          title="No accounts yet"
          hint="Open the first account above; an opening balance is funded by the world account, so the book stays at zero."
        />
      ) : (
        <ul className="account-list">
          {accounts.map((account) => (
            <li key={account.id} className="account-item">
              <button
                type="button"
                className="account-row"
                aria-current={account.id === selectedId || undefined}
                onClick={() => onSelect(account.id)}
              >
                <span className="avatar" style={{ background: `hsl(${hueFromId(account.id)} 45% 42%)` }} aria-hidden="true">
                  {account.name.slice(0, 1)}
                </span>
                <span className="account-name">
                  {account.name}
                  <span className="account-meta">{account.id.slice(0, 8)}…</span>
                </span>
                <AnimatedNumber className={"amount" + (account.balanceMinor < 0 ? " negative" : "")} value={account.balanceMinor} />
              </button>
              <CopyButton text={account.id} label={`Copy ${account.name}'s account id`} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
