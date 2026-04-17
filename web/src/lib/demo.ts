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
    toast("info", `Demo: sending ${formatMinor(AMOUNT_MINOR)} under one label, then losing the reply`);
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
        ? "Demo: sending the very same payment again under that same label anyway"
        : "Demo: the reply never arrived, so we send the same payment again under that label",
    );
    const { replayed } = await createTransfer(body, key);
    changed();
    select(to.id);
    await sleep(900);
    toast(
      replayed ? "success" : "error",
      replayed
        ? "The server handed back the first answer again: paid exactly once, not twice"
        : "Unexpected: the server did not recognise the repeat",
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
        ? `Recount: ${report.accountsChecked} accounts added up again from their own lines, none disagreed, book totals ${formatMinor(report.globalSumMinor)}`
        : `The recount found ${report.drifts.length} account(s) that do not add up`,
    );
  } catch (caught) {
    toast("error", `Demo stopped: ${(caught as Error).message}`);
  }
}
