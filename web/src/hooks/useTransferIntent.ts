// useReducer plus the fetch side effects around the pure transfer-intent reducer. The reducer owns the
// key lifecycle; this hook mints the key at submit, fires the request, and maps ApiError.kind onto the
// resolve events. A resolution that lands after a newer submit or edit is dropped.
import { useCallback, useReducer, useRef } from "react";
import { ApiError, createTransfer } from "../api/client";
import { initialIntent, transferIntentReducer } from "../lib/transferIntent";
import type { TransferRequest } from "../api/types";

export function useTransferIntent() {
  const [state, dispatch] = useReducer(transferIntentReducer, initialIntent);
  // The key currently in flight. A resolution whose key no longer matches has been superseded, so ignore it.
  const activeKey = useRef<string | null>(null);

  const run = useCallback(async (key: string, body: TransferRequest) => {
    activeKey.current = key;
    try {
      const { transfer, replayed } = await createTransfer(body, key);
      if (activeKey.current === key) {
        dispatch({ type: "RESOLVE_OK", transfer, replayed });
      }
    } catch (error) {
      if (activeKey.current !== key) {
        return;
      }
      const apiError = error instanceof ApiError ? error : new ApiError("UNKNOWN", "Something went wrong.", "terminal", null);
      dispatch(apiError.kind === "transient"
        ? { type: "RESOLVE_TRANSIENT", message: apiError.message }
        : { type: "RESOLVE_TERMINAL", message: apiError.message });
    }
  }, []);

  // One key minted per intended transfer, here, at submit. It enters the reducer as event payload.
  const submit = useCallback((body: TransferRequest) => {
    const key = crypto.randomUUID();
    dispatch({ type: "SUBMIT", key, body });
    void run(key, body);
  }, [run]);

  // Retry reuses the frozen key and body from the failed intent, never the current form values.
  const retry = useCallback(() => {
    if (state.phase === "failedTransient") {
      const { key, body } = state;
      dispatch({ type: "RETRY" });
      void run(key, body);
    }
  }, [state, run]);

  const edited = useCallback(() => {
    dispatch({ type: "EDIT" });
    activeKey.current = null;   // abandon the intent: any late resolution of the old key is now ignored
  }, []);

  return { state, submit, retry, edited };
}
