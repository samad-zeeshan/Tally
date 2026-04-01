// The create form (name plus an optional opening balance) and the account list. The opening-balance
// field funds a new account through the world-funded creation path, so the demo needs no seeding step.
import { useState, type FormEvent } from "react";
import { ApiError, createAccount } from "../api/client";
import type { Account } from "../api/types";
import { formatMinor, parseAmountToMinor } from "../lib/money";

interface Props {
  accounts: Account[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  onChanged: () => void;
}

export function AccountsPanel({ accounts, selectedId, onSelect, onChanged }: Props) {
  const [name, setName] = useState("");
  const [opening, setOpening] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    let openingMinor: number | undefined;
    if (opening.trim() !== "") {
      const parsed = parseAmountToMinor(opening);
      if (parsed === null) {
        setError("Opening balance must be an amount like 12.34.");
        return;
      }
      openingMinor = parsed;
    }
    setBusy(true);
    try {
      await createAccount(name.trim(), openingMinor);
      setName("");
      setOpening("");
      onChanged();
    } catch (caught) {
      setError((caught as ApiError).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>Accounts</h2>
      <form onSubmit={handleCreate} className="create-form">
        <input
          aria-label="Account name"
          placeholder="Account name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          required
        />
        <input
          aria-label="Opening balance"
          placeholder="Opening balance (optional)"
          value={opening}
          onChange={(event) => setOpening(event.target.value)}
          inputMode="decimal"
        />
        <button type="submit" className="primary" disabled={busy || name.trim() === ""}>Create</button>
      </form>
      {error && <p className="notice notice-error">{error}</p>}
      <ul className="account-list">
        {accounts.map((account) => (
          <li key={account.id}>
            <button
              type="button"
              className={"account-row" + (account.id === selectedId ? " selected" : "")}
              onClick={() => onSelect(account.id)}
            >
              <span className="account-name">{account.name}</span>
              <span className="account-id">{account.id.slice(0, 8)}…</span>
              <span className="amount">{formatMinor(account.balanceMinor)}</span>
            </button>
          </li>
        ))}
        {accounts.length === 0 && <li className="empty">No accounts yet. Create one above.</li>}
      </ul>
    </section>
  );
}
