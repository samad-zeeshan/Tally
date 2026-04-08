// The transfer form. It owns the retry UX: while a transient failure is showing, the frozen intent can
// be retried under the same key, but any field change abandons it (a different tuple under the same key
// would be a key conflict), and abandoning means the user cannot know if the first attempt applied.
import { useEffect, useState, type FormEvent } from "react";
import { armDroppedResponse, disarmDroppedResponse } from "../api/client";
import type { Account } from "../api/types";
import { formatMinor, parseAmountToMinor } from "../lib/money";
import { useTransferIntent } from "../hooks/useTransferIntent";
import { Button } from "./ui/Button";
import { Field } from "./ui/Field";
import { SwapIcon, ZapIcon } from "./ui/icons";
import type { ToastKind } from "./ui/Toasts";

interface Props {
  accounts: Account[];
  onChanged: () => void;
  onToast: (kind: ToastKind, message: string) => void;
}

const QUICK_AMOUNTS = [500, 2500, 10000];

interface FieldErrors {
  from?: string;
  to?: string;
  amount?: string;
}

export function TransferForm({ accounts, onChanged, onToast }: Props) {
  const { state, submit, retry, edited } = useTransferIntent();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [abandoned, setAbandoned] = useState(false);
  const [chaos, setChaos] = useState(false);

  const inFlight = state.phase === "inFlight";
  const fromAccount = accounts.find((account) => account.id === from) ?? null;
  const parsed = parseAmountToMinor(amount);
  // Live preview of the source balance after this transfer, all integer math on minor units.
  const remaining = fromAccount !== null && parsed !== null && parsed > 0 ? fromAccount.balanceMinor - parsed : null;

  // On success: clear the form, say so, and let App refresh balances. Guarded so it fires only on the
  // transition into success.
  useEffect(() => {
    if (state.phase !== "success") {
      return;
    }
    setFrom("");
    setTo("");
    setAmount("");
    setAbandoned(false);
    setChaos(false); // the dropped-response fault self-clears after one attempt
    onToast("success", state.replayed ? "Retry deduplicated: the transfer applied exactly once" : "Transfer applied");
    onChanged();
  }, [state, onChanged, onToast]);

  // While a transient failure is showing, editing any field abandons the intent so its key is never
  // reused for a different body; the notice then admits the first attempt's outcome is unknown.
  function markEdited() {
    if (state.phase === "failedTransient") {
      edited();
      setAbandoned(true);
    }
  }

  function editField(setter: (value: string) => void, value: string) {
    markEdited();
    setFieldErrors({});
    setter(value);
  }

  function swap() {
    markEdited();
    setFrom(to);
    setTo(from);
  }

  function applyChip(minor: number) {
    editField(setAmount, formatMinor(minor));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAbandoned(false);
    const errors: FieldErrors = {};
    if (from === "") {
      errors.from = "Choose the source account.";
    }
    if (to === "") {
      errors.to = "Choose the destination account.";
    } else if (to === from) {
      errors.to = "Choose two different accounts.";
    }
    const amountMinor = parseAmountToMinor(amount);
    if (amountMinor === null || amountMinor <= 0) {
      errors.amount = "An amount like 12.34, greater than zero.";
    }
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0 || amountMinor === null) {
      return;
    }
    submit({ fromAccountId: from, toAccountId: to, amountMinor });
  }

  const accountOptions = accounts.map((account) => (
    <option key={account.id} value={account.id}>
      {account.name} · {formatMinor(account.balanceMinor)}
    </option>
  ));

  return (
    <section className="card transfer card-transfer">
      <div className="card-header">
        <h2>Transfer</h2>
        {import.meta.env.DEV && (
          <label className="chaos" title="The request still reaches the server; only the response is dropped. Retry proves the dedup.">
            <input
              type="checkbox"
              checked={chaos}
              onChange={(event) => {
                setChaos(event.target.checked);
                (event.target.checked ? armDroppedResponse : disarmDroppedResponse)();
              }}
            />
            <ZapIcon /> drop the next response
          </label>
        )}
      </div>

      <form onSubmit={handleSubmit}>
        <div className="transfer-grid">
          <Field label="From" error={fieldErrors.from}>
            {(a11y) => (
              <select {...a11y} value={from} onChange={(event) => editField(setFrom, event.target.value)} disabled={inFlight}>
                <option value="">Select an account</option>
                {accountOptions}
              </select>
            )}
          </Field>
          <button
            type="button"
            className="icon-btn swap-btn"
            onClick={swap}
            disabled={inFlight}
            aria-label="Swap the two accounts"
            title="Swap accounts"
          >
            <SwapIcon />
          </button>
          <Field label="To" error={fieldErrors.to}>
            {(a11y) => (
              <select {...a11y} value={to} onChange={(event) => editField(setTo, event.target.value)} disabled={inFlight}>
                <option value="">Select an account</option>
                {accountOptions}
              </select>
            )}
          </Field>
        </div>

        <div className="amount-row">
          <Field label="Amount" error={fieldErrors.amount}>
            {(a11y) => (
              <input
                {...a11y}
                value={amount}
                onChange={(event) => editField(setAmount, event.target.value)}
                placeholder="12.34"
                inputMode="decimal"
                disabled={inFlight}
              />
            )}
          </Field>
          <div className="chips" role="group" aria-label="Quick amounts">
            {QUICK_AMOUNTS.map((minor) => (
              <button key={minor} type="button" className="chip" onClick={() => applyChip(minor)} disabled={inFlight}>
                {formatMinor(minor)}
              </button>
            ))}
            <button
              type="button"
              className="chip"
              onClick={() => fromAccount && applyChip(fromAccount.balanceMinor)}
              disabled={inFlight || fromAccount === null || fromAccount.balanceMinor <= 0}
            >
              Max
            </button>
          </div>
        </div>

        {/* The live preview and the action share one row: consequence on the left, commit on the right. */}
        <div className="actions-row">
          <p className={"preview" + (remaining !== null && remaining < 0 ? " warn" : "")} aria-live="polite">
            {remaining !== null &&
              fromAccount !== null &&
              (remaining < 0
                ? `Exceeds ${fromAccount.name}'s balance by ${formatMinor(-remaining)}; the ledger will reject it.`
                : `${fromAccount.name} will hold ${formatMinor(remaining)} after this transfer.`)}
          </p>
          <Button type="submit" loading={inFlight}>
            {inFlight ? "Sending" : "Send transfer"}
          </Button>
        </div>
      </form>

      <div aria-live="polite">
        {state.phase === "failedTransient" && (
          <div className="notice notice-warn">
            <span>{state.message}</span>
            <Button variant="secondary" onClick={retry}>
              Retry
            </Button>
          </div>
        )}

        {state.phase === "failedTerminal" && <p className="notice notice-error">{state.message}</p>}

        {abandoned && (
          <p className="notice notice-warn">The previous transfer may or may not have gone through. Check the statement.</p>
        )}
      </div>
    </section>
  );
}
