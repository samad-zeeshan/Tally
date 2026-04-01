// The transfer form. It owns the retry UX: while a transient failure is showing, the frozen intent can
// be retried under the same key, but any field change abandons it (a different tuple under the same key
// would be a key conflict), and abandoning means the user cannot know if the first attempt applied.
import { useEffect, useState, type FormEvent } from "react";
import { armDroppedResponse } from "../api/client";
import type { Account } from "../api/types";
import { parseAmountToMinor } from "../lib/money";
import { useTransferIntent } from "../hooks/useTransferIntent";

interface Props {
  accounts: Account[];
  onChanged: () => void;
}

export function TransferForm({ accounts, onChanged }: Props) {
  const { state, submit, retry, edited } = useTransferIntent();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [abandoned, setAbandoned] = useState(false);

  const inFlight = state.phase === "inFlight";

  // On success: clear the form and tell App to refresh balances. Fires once per transition to success.
  useEffect(() => {
    if (state.phase === "success") {
      setFrom("");
      setTo("");
      setAmount("");
      setAbandoned(false);
      onChanged();
    }
  }, [state.phase, onChanged]);

  // While a transient failure is showing, editing any field abandons the intent so its key is never
  // reused for a different body; the notice then admits the first attempt's outcome is unknown.
  function editField(setter: (value: string) => void, value: string) {
    if (state.phase === "failedTransient") {
      edited();
      setAbandoned(true);
    }
    setter(value);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFormError(null);
    setAbandoned(false);
    if (from === "" || to === "") {
      setFormError("Choose both accounts.");
      return;
    }
    if (from === to) {
      setFormError("Choose two different accounts.");
      return;
    }
    const amountMinor = parseAmountToMinor(amount);
    if (amountMinor === null || amountMinor <= 0) {
      setFormError("Enter an amount like 12.34.");
      return;
    }
    submit({ fromAccountId: from, toAccountId: to, amountMinor });
  }

  return (
    <section className="card transfer">
      <h2>Transfer</h2>
      <form onSubmit={handleSubmit} className="transfer-form">
        <label>
          From
          <select value={from} onChange={(event) => editField(setFrom, event.target.value)} disabled={inFlight}>
            <option value="">Select an account</option>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>{account.name}</option>
            ))}
          </select>
        </label>
        <label>
          To
          <select value={to} onChange={(event) => editField(setTo, event.target.value)} disabled={inFlight}>
            <option value="">Select an account</option>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>{account.name}</option>
            ))}
          </select>
        </label>
        <label>
          Amount
          <input
            value={amount}
            onChange={(event) => editField(setAmount, event.target.value)}
            placeholder="12.34"
            inputMode="decimal"
            disabled={inFlight}
          />
        </label>
        <button type="submit" className="primary" disabled={inFlight}>
          {inFlight ? "Submitting…" : "Send transfer"}
        </button>
      </form>

      {import.meta.env.DEV && (
        <label className="dev-fault">
          <input type="checkbox" onChange={(event) => event.target.checked && armDroppedResponse()} />
          Simulate a dropped response on next submit
        </label>
      )}

      {formError && <p className="notice notice-error">{formError}</p>}

      {state.phase === "failedTransient" && (
        <div className="notice notice-warn">
          <span>{state.message}</span>
          <button type="button" className="secondary" onClick={retry}>Retry</button>
        </div>
      )}

      {state.phase === "failedTerminal" && <p className="notice notice-error">{state.message}</p>}

      {abandoned && (
        <p className="notice notice-warn">The previous transfer may or may not have gone through. Check the statement.</p>
      )}

      {state.phase === "success" && (
        <p className="notice notice-success">
          {state.replayed
            ? "Applied once. The retry was deduplicated by the server."
            : "Transfer applied."}
        </p>
      )}
    </section>
  );
}
