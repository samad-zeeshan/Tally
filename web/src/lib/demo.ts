// The scripted actions behind the explainer's "Try it" buttons. Each one drives the same API client
// the rest of the UI uses, against two reusable demo accounts, and narrates itself through toasts.
// Money only ever moves from the richer demo account to the poorer one, so repeated runs converge
// instead of draining a fixed sender dry.
import { armDroppedResponse, createAccount, createTransfer, getReconciliation, listAccounts } from "../api/client";
import type { Account } from "../api/types";
import { formatMinor } from "./money";
import type { ToastKind } from "../components/ui/Toasts";

export interface DemoHooks {
  toast: (kind: ToastKind, message: string) => void;
  select: (id: string) => void;
  changed: () => void;
}

// Hyphenated: the backend's NAME_CHARSET admits letters, digits, spaces and ".,'&-", no parentheses.
const AVA = "ava-demo";
const NOAH = "noah-demo";
const AMOUNT_MINOR = 4250;

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

// Finds the demo pair or opens them (funded by the world account); re-runs reuse the same accounts.
async function ensureDemoPair(changed: () => void): Promise<{ from: Account; to: Account }> {
  const existing = await listAccounts();
  let ava = existing.find((a) => a.name === AVA);
  let noah = existing.find((a) => a.name === NOAH);
  if (ava === undefined) {
    ava = await createAccount(AVA, 75000);
    changed();
  }
  if (noah === undefined) {
    noah = await createAccount(NOAH, 20000);
    changed();
  }
  return ava.balanceMinor >= noah.balanceMinor ? { from: ava, to: noah } : { from: noah, to: ava };
}

export async function runSampleTransfer({ toast, select, changed }: DemoHooks): Promise<void> {
  try {
    const { from, to } = await ensureDemoPair(changed);
    toast("info", `Demo: sending ${formatMinor(AMOUNT_MINOR)} from ${from.name} to ${to.name}`);
    await createTransfer(
      { fromAccountId: from.id, toAccountId: to.id, amountMinor: AMOUNT_MINOR },
      crypto.randomUUID(),
    );
    changed();
    select(to.id);
    toast("success", `${to.name} gained ${formatMinor(AMOUNT_MINOR)}: a new statement row, and the header still sums to 0.00`);
  } catch (caught) {
    toast("error", `Demo stopped: ${(caught as Error).message}`);
  }
}

// The idempotency story: one key, two sends. In dev the first response is genuinely dropped by the
// same fault the chaos toggle uses; in a production build the first send just succeeds and the second
// acts as a duplicate retry. Either way the server must answer the retry with a replay, not a second
// transfer.
export async function runIdempotencyDemo({ toast, select, changed }: DemoHooks): Promise<void> {
  try {
    const { from, to } = await ensureDemoPair(changed);
    const key = crypto.randomUUID();
    const body = { fromAccountId: from.id, toAccountId: to.id, amountMinor: AMOUNT_MINOR };
    toast("info", `Demo: sending ${formatMinor(AMOUNT_MINOR)} under one idempotency key, then losing the response`);
    if (import.meta.env.DEV) {
      armDroppedResponse();
    }
    let firstArrived = true;
    try {
      await createTransfer(body, key);
    } catch {
      firstArrived = false; // the response was lost, so the client cannot know if the transfer applied
    }
    changed();
    await sleep(1400);
    toast(
      "info",
      firstArrived
        ? "Demo: retrying the same request under the same key anyway"
        : "Demo: the response never arrived, so we retry under the same key",
    );
    const { replayed } = await createTransfer(body, key);
    changed();
    select(to.id);
    await sleep(900);
    toast(
      replayed ? "success" : "error",
      replayed
        ? "The server replayed the first outcome: applied exactly once, no double transfer"
        : "Unexpected: the server did not report a replay",
    );
  } catch (caught) {
    toast("error", `Demo stopped: ${(caught as Error).message}`);
  }
}

export async function runReconciliationCheck({ toast }: DemoHooks): Promise<void> {
  try {
    const report = await getReconciliation();
    toast(
      report.consistent ? "success" : "error",
      report.consistent
        ? `Reconciliation: ${report.accountsChecked} accounts re-derived from postings, zero drift, book sums to ${formatMinor(report.globalSumMinor)}`
        : `Reconciliation found drift on ${report.drifts.length} account(s)`,
    );
  } catch (caught) {
    toast("error", `Demo stopped: ${(caught as Error).message}`);
  }
}
