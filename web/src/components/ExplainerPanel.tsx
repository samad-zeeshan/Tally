// A short what-is-this card above the grid: three claims about the ledger and a strip of buttons that
// prove each one live against the demo accounts. A native <details> so collapsing needs no state; one
// demo runs at a time so their toast narrations cannot interleave.
import { useState } from "react";
import { runIdempotencyDemo, runReconciliationCheck, runSampleTransfer, type DemoHooks } from "../lib/demo";
import { Button } from "./ui/Button";
import type { ToastKind } from "./ui/Toasts";

interface Props {
  onToast: (kind: ToastKind, message: string) => void;
  onSelect: (id: string) => void;
  onChanged: () => void;
}

type DemoName = "transfer" | "idempotency" | "reconcile";

const DEMOS: Record<DemoName, (hooks: DemoHooks) => Promise<void>> = {
  transfer: runSampleTransfer,
  idempotency: runIdempotencyDemo,
  reconcile: runReconciliationCheck,
};

export function ExplainerPanel({ onToast, onSelect, onChanged }: Props) {
  const [running, setRunning] = useState<DemoName | null>(null);

  async function run(name: DemoName) {
    if (running !== null) {
      return;
    }
    setRunning(name);
    try {
      await DEMOS[name]({ toast: onToast, select: onSelect, changed: onChanged });
    } finally {
      setRunning(null);
    }
  }

  const demoButton = (name: DemoName, label: string) => (
    <Button variant="secondary" loading={running === name} disabled={running !== null} onClick={() => run(name)}>
      {label}
    </Button>
  );

  return (
    <details className="card explainer" open>
      <summary>
        <h2>How Tally works</h2>
        <span className="explainer-hint">a double-entry ledger you can poke</span>
      </summary>
      <ul className="explainer-points">
        <li>
          <strong>Double entry.</strong> Every transfer is two balanced postings, one out and one in, so
          money moves but is never created. The whole book always sums to zero; the badge in the header
          rechecks that after every change.
        </li>
        <li>
          <strong>Idempotent transfers.</strong> Each transfer carries a unique key. If the response gets
          lost, retrying under the same key applies the transfer exactly once, never twice.
        </li>
        <li>
          <strong>The ledger is the truth.</strong> Balances, statements, and the reconciliation report
          are all derived from the same postings, so they cannot disagree.
        </li>
      </ul>
      <div className="try-strip" role="group" aria-label="Live demos">
        <span className="try-label">Try it</span>
        {demoButton("transfer", "Make a sample transfer")}
        {demoButton("idempotency", "Lose a response, retry safely")}
        {demoButton("reconcile", "Check the books")}
      </div>
    </details>
  );
}
