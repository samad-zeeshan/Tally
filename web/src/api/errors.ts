// Turns a backend error code into a user message and a transient or terminal classification.
//
// Only a failure whose outcome is unknown is worth retrying, and the idempotency key is what makes
// retrying one safe.

export type FailureKind = "transient" | "terminal";

const MESSAGES: Record<string, string> = {
  INSUFFICIENT_FUNDS: "Not enough money in the source account.",
  ACCOUNT_NOT_FOUND: "That account does not exist.",
  RESERVED_ACCOUNT: "That account cannot take part in a transfer.",
  SAME_ACCOUNT: "Choose two different accounts.",
  IDEMPOTENCY_KEY_CONFLICT: "This transfer was already submitted with different details. Start a new one.",
  AUTH_MISSING: "The API token is missing. Set VITE_API_TOKEN and restart the dev server.",
  AUTH_INVALID: "The API token is wrong. Check VITE_API_TOKEN and restart the dev server.",
  RATE_LIMITED: "Too many requests. Wait a moment, then retry.",
  UNSAFE_AMOUNT: "The server returned an amount too large to show safely.",
  NETWORK: "Could not reach the server. Your transfer was not lost, you can retry safely.",
  NETWORK_SIMULATED: "Could not reach the server. Your transfer was not lost, you can retry safely.",
  INTERNAL: "The server hit an error. It is safe to retry.",
};

// Status is enough: a definite 4xx no cannot be changed by asking again, while a null status or a 5xx
// leaves the outcome unknown. 429 is the one 4xx that waiting does change, because the throttle runs
// ahead of the handler, so nothing applied and the frozen key can go out again after the Retry-After.
export function classify(status: number | null, _code: string): FailureKind {
  return status === null || status === 429 || status >= 500 ? "transient" : "terminal";
}

// The granular validation codes already read cleanly, so their server message passes straight through
// rather than being restated here.
export function messageFor(code: string, serverMessage: string): string {
  return MESSAGES[code] ?? serverMessage ?? "The request was rejected.";
}
