// Turns a backend error code into a user message and a transient/terminal classification. The rule in
// one sentence: a definite server no (4xx, or a 2xx that failed the client's own safe-integer guard) is
// terminal; only a lost response or a 5xx is transient, and the idempotency key makes retrying those safe.

export type FailureKind = "transient" | "terminal";

const MESSAGES: Record<string, string> = {
  INSUFFICIENT_FUNDS: "Not enough money in the source account.",
  ACCOUNT_NOT_FOUND: "That account does not exist.",
  RESERVED_ACCOUNT: "That account cannot take part in a transfer.",
  SAME_ACCOUNT: "Choose two different accounts.",
  IDEMPOTENCY_KEY_CONFLICT: "This transfer was already submitted with different details. Start a new one.",
  AUTH_MISSING: "The API token is missing. Set VITE_API_TOKEN and restart the dev server.",
  AUTH_INVALID: "The API token is wrong. Check VITE_API_TOKEN and restart the dev server.",
  UNSAFE_AMOUNT: "The server returned an amount too large to show safely.",
  NETWORK: "Could not reach the server. Your transfer was not lost, you can retry safely.",
  NETWORK_SIMULATED: "Could not reach the server. Your transfer was not lost, you can retry safely.",
  INTERNAL: "The server hit an error. It is safe to retry.",
};

// Keys on status only: retrying the identical request cannot change a definite 4xx no, so it is terminal.
// A null status (response never arrived) or a 5xx (the server's own fault, outcome unknown) is transient.
export function classify(status: number | null, _code: string): FailureKind {
  return status === null || status >= 500 ? "transient" : "terminal";
}

// Keys on code. Known codes get a written message; granular validation codes read cleanly already, so
// their server message passes through, with a last-resort fallback for an unknown code.
export function messageFor(code: string, serverMessage: string): string {
  return MESSAGES[code] ?? serverMessage ?? "The request was rejected.";
}
