// The idempotency-key state machine, the centerpiece of the client. One key per intended transfer,
// reused on every retry, so a dropped response can never double-pay. The key enters as event payload
// (minted by the caller at submit) so this reducer stays pure and unit-testable.
import type { Transfer, TransferRequest } from "../api/types";

export type IntentState =
  | { phase: "idle" }
  | { phase: "inFlight"; key: string; body: TransferRequest }
  | { phase: "failedTransient"; key: string; body: TransferRequest; message: string }
  | { phase: "failedTerminal"; message: string }
  | { phase: "success"; transfer: Transfer; replayed: boolean };

export type IntentEvent =
  | { type: "SUBMIT"; key: string; body: TransferRequest } // key minted by the caller
  | { type: "RETRY" }
  | { type: "RESOLVE_OK"; transfer: Transfer; replayed: boolean }
  | { type: "RESOLVE_TRANSIENT"; message: string }
  | { type: "RESOLVE_TERMINAL"; message: string }
  | { type: "EDIT" };

export const initialIntent: IntentState = { phase: "idle" };

export function transferIntentReducer(state: IntentState, event: IntentEvent): IntentState {
  switch (event.type) {
    case "SUBMIT":
      // A submit is a new intent from any resting phase; it takes the caller's fresh key and frozen body.
      if (state.phase === "idle" || state.phase === "failedTerminal" || state.phase === "success") {
        return { phase: "inFlight", key: event.key, body: event.body };
      }
      return state;
    case "RESOLVE_OK":
      // replayed came from the Idempotency-Replayed header. The key has done its job and is retired.
      if (state.phase === "inFlight") {
        return { phase: "success", transfer: event.transfer, replayed: event.replayed };
      }
      return state;
    case "RESOLVE_TRANSIENT":
      // Outcome unknown, so the key and frozen body must survive to make a retry safe.
      if (state.phase === "inFlight") {
        return { phase: "failedTransient", key: state.key, body: state.body, message: event.message };
      }
      return state;
    case "RESOLVE_TERMINAL":
      // The server answered definitively; the key is discarded. The backend remembers a consuming
      // outcome's key, so a later edited submit must be a brand new intent with a new key.
      if (state.phase === "inFlight") {
        return { phase: "failedTerminal", message: event.message };
      }
      return state;
    case "RETRY":
      // The guarantee: one key per intent, however many attempts. Reuse the frozen body, not form values,
      // or the same key would carry a different tuple and hit a key conflict.
      if (state.phase === "failedTransient") {
        return { phase: "inFlight", key: state.key, body: state.body };
      }
      return state;
    case "EDIT":
      // Changing the fields is a different intent; abandon the old key rather than reuse it for a new body.
      if (state.phase === "failedTransient") {
        return { phase: "idle" };
      }
      return state;
    default:
      // Total reducer: an event that does not apply to the current phase leaves the state unchanged.
      return state;
  }
}
