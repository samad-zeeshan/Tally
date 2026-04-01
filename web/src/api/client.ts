// The typed fetch layer. Three things it is careful about: every amount off the wire runs through a
// safe-integer guard; the bundled token is a demo-only credential (noted where it is read); and replayed
// is read from the Idempotency-Replayed header, never the status, because a replay repeats the 201.
import { classify, messageFor, type FailureKind } from "./errors";
import type { Account, ApiErrorBody, StatementPage, Transfer, TransferRequest } from "./types";

export class ApiError extends Error {
  readonly kind: FailureKind;
  readonly code: string;
  readonly status: number | null;

  constructor(code: string, message: string, kind: FailureKind, status: number | null) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.kind = kind;
    this.status = status;
  }
}

// import.meta.env.VITE_* is baked into the served JavaScript and readable by anyone who opens the page.
// Acceptable only because this is a dev/demo credential for a local backend; a real client would get a
// short-lived session token from a login flow, never a bundled secret. The trade is owned by ADR-0015.
const TOKEN = import.meta.env.VITE_API_TOKEN;

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  if (TOKEN) {
    headers.Authorization = `Bearer ${TOKEN}`;
  }
  return headers;
}

async function send(path: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(path, init);
  } catch {
    // fetch rejects only when the response never arrived; the outcome is unknown, so this is transient.
    throw new ApiError("NETWORK", messageFor("NETWORK", ""), "transient", null);
  }
}

async function fail(response: Response): Promise<never> {
  let code = "UNKNOWN";
  let serverMessage = "";
  try {
    const body = (await response.json()) as { error?: ApiErrorBody };
    code = body.error?.code ?? code;
    serverMessage = body.error?.message ?? "";
  } catch {
    // A non-JSON error body leaves the fallback code in place.
  }
  throw new ApiError(code, messageFor(code, serverMessage), classify(response.status, code), response.status);
}

function safeAmount(value: number, status: number): number {
  if (!Number.isSafeInteger(value)) {
    // A long past 2^53 should never arrive (Stage 6 caps every amount at 10^12), so this is a loud
    // tripwire, not an expected path: fail rather than silently round.
    throw new ApiError("UNSAFE_AMOUNT", messageFor("UNSAFE_AMOUNT", ""), "terminal", status);
  }
  return value;
}

function toAccount(raw: any, status: number): Account {
  return { id: raw.id, name: raw.name, balanceMinor: safeAmount(raw.balanceMinor, status), createdAt: raw.createdAt };
}

function toTransfer(raw: any, status: number): Transfer {
  return {
    id: raw.id,
    fromAccountId: raw.fromAccountId,
    toAccountId: raw.toAccountId,
    amountMinor: safeAmount(raw.amountMinor, status),
    createdAt: raw.createdAt,
  };
}

function toStatementPage(raw: any, status: number): StatementPage {
  return {
    accountId: raw.accountId,
    nextCursor: raw.nextCursor ?? null,
    entries: (raw.entries as any[]).map((entry) => ({
      postingId: entry.postingId,
      transferId: entry.transferId,
      counterpartyAccountId: entry.counterpartyAccountId,
      amountMinor: safeAmount(entry.amountMinor, status),
      balanceAfterMinor: safeAmount(entry.balanceAfterMinor, status),
      createdAt: entry.createdAt,
    })),
  };
}

export async function createAccount(name: string, openingBalanceMinor?: number): Promise<Account> {
  const body: Record<string, unknown> = { name };
  if (openingBalanceMinor !== undefined) {
    body.openingBalanceMinor = openingBalanceMinor;
  }
  const response = await send("/api/accounts", {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    await fail(response);
  }
  return toAccount(await response.json(), response.status);
}

export async function getAccount(id: string): Promise<Account> {
  const response = await send(`/api/accounts/${id}`, { headers: authHeaders() });
  if (!response.ok) {
    await fail(response);
  }
  return toAccount(await response.json(), response.status);
}

export async function listAccounts(): Promise<Account[]> {
  const response = await send("/api/accounts", { headers: authHeaders() });
  if (!response.ok) {
    await fail(response);
  }
  const raw = (await response.json()) as { accounts: any[] };
  return raw.accounts.map((account) => toAccount(account, response.status));
}

export async function getStatement(accountId: string, cursor?: string): Promise<StatementPage> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
  const response = await send(`/api/accounts/${accountId}/statement${query}`, { headers: authHeaders() });
  if (!response.ok) {
    await fail(response);
  }
  return toStatementPage(await response.json(), response.status);
}

let droppedResponseArmed = false;

// Dev-only fault: arm a single dropped response so the retry path can prove server-side dedup live.
export function armDroppedResponse(): void {
  droppedResponseArmed = true;
}

export async function createTransfer(
  req: TransferRequest,
  idempotencyKey: string,
): Promise<{ transfer: Transfer; replayed: boolean }> {
  const response = await send("/api/transfers", {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json", "Idempotency-Key": idempotencyKey }),
    body: JSON.stringify(req),
  });
  // The fault fires AFTER the real fetch: the request reached the server and applied, we just drop the
  // response. That is the only case that proves anything about deduplication. Self-clearing, one attempt.
  if (import.meta.env.DEV && droppedResponseArmed) {
    droppedResponseArmed = false;
    throw new ApiError("NETWORK_SIMULATED", messageFor("NETWORK_SIMULATED", ""), "transient", null);
  }
  if (!response.ok) {
    await fail(response);
  }
  const replayed = response.headers.get("Idempotency-Replayed") === "true";
  return { transfer: toTransfer(await response.json(), response.status), replayed };
}
