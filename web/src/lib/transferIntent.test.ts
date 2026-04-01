import { describe, expect, it } from "vitest";
import { initialIntent, transferIntentReducer, type IntentState } from "./transferIntent";
import type { Transfer, TransferRequest } from "../api/types";

const body: TransferRequest = { fromAccountId: "a", toAccountId: "b", amountMinor: 500 };
const transfer: Transfer = {
  id: "t1",
  fromAccountId: "a",
  toAccountId: "b",
  amountMinor: 500,
  createdAt: "2026-01-01T00:00:00Z",
};

const submit = (key: string) => ({ type: "SUBMIT" as const, key, body });

describe("transferIntentReducer", () => {
  it("submit freezes the key and body and goes inFlight", () => {
    expect(transferIntentReducer(initialIntent, submit("k1"))).toEqual({ phase: "inFlight", key: "k1", body });
  });

  it("transient failure keeps the key and body", () => {
    const inFlight = transferIntentReducer(initialIntent, submit("k1"));
    const s = transferIntentReducer(inFlight, { type: "RESOLVE_TRANSIENT", message: "m" });
    expect(s).toEqual({ phase: "failedTransient", key: "k1", body, message: "m" });
  });

  // The frozen body must survive so the retry carries the same tuple under the same key.
  it("retry after a transient failure reuses the frozen key", () => {
    let s: IntentState = transferIntentReducer(initialIntent, submit("k1"));
    s = transferIntentReducer(s, { type: "RESOLVE_TRANSIENT", message: "m" });
    s = transferIntentReducer(s, { type: "RETRY" });
    expect(s).toEqual({ phase: "inFlight", key: "k1", body });
  });

  it("terminal failure discards the key", () => {
    const inFlight = transferIntentReducer(initialIntent, submit("k1"));
    const s = transferIntentReducer(inFlight, { type: "RESOLVE_TERMINAL", message: "no" });
    expect(s).toEqual({ phase: "failedTerminal", message: "no" });
    expect("key" in s).toBe(false);
  });

  it("a new submit after terminal failure carries a fresh key", () => {
    let s: IntentState = transferIntentReducer(initialIntent, submit("k1"));
    s = transferIntentReducer(s, { type: "RESOLVE_TERMINAL", message: "no" });
    s = transferIntentReducer(s, submit("k2"));
    expect(s).toEqual({ phase: "inFlight", key: "k2", body });
  });

  it("editing during a transient failure abandons the intent", () => {
    let s: IntentState = transferIntentReducer(initialIntent, submit("k1"));
    s = transferIntentReducer(s, { type: "RESOLVE_TRANSIENT", message: "m" });
    s = transferIntentReducer(s, { type: "EDIT" });
    expect(s).toEqual({ phase: "idle" });
  });

  // replayed rides through from the Idempotency-Replayed header the caller read.
  it("success retires the intent and records the replayed flag", () => {
    const inFlight = transferIntentReducer(initialIntent, submit("k1"));
    const s = transferIntentReducer(inFlight, { type: "RESOLVE_OK", transfer, replayed: true });
    expect(s).toEqual({ phase: "success", transfer, replayed: true });
  });

  it("events that do not apply leave the state unchanged", () => {
    expect(transferIntentReducer(initialIntent, { type: "RETRY" })).toBe(initialIntent);
    const inFlight = transferIntentReducer(initialIntent, submit("k1"));
    expect(transferIntentReducer(inFlight, { type: "EDIT" })).toBe(inFlight);
    expect(transferIntentReducer(initialIntent, { type: "RESOLVE_OK", transfer, replayed: false })).toBe(initialIntent);
  });
});
