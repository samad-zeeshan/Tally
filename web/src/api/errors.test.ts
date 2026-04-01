import { describe, expect, it } from "vitest";
import { classify, messageFor } from "./errors";

describe("messageFor", () => {
  it("INSUFFICIENT_FUNDS is terminal with a plain sentence", () => {
    expect(messageFor("INSUFFICIENT_FUNDS", "server text")).toBe("Not enough money in the source account.");
  });
  it("unknown 4xx code falls back to the server message", () => {
    expect(messageFor("SOMETHING_NEW", "the server said this")).toBe("the server said this");
  });
});

describe("classify", () => {
  it("network failure with null status is transient", () => {
    expect(classify(null, "NETWORK")).toBe("transient");
  });
  it("5xx is transient regardless of code", () => {
    expect(classify(500, "INTERNAL")).toBe("transient");
    expect(classify(503, "WHATEVER")).toBe("transient");
  });
  it("4xx codes are terminal", () => {
    expect(classify(400, "AMOUNT_NOT_POSITIVE")).toBe("terminal");
    expect(classify(409, "IDEMPOTENCY_KEY_CONFLICT")).toBe("terminal");
    expect(classify(401, "AUTH_MISSING")).toBe("terminal");
  });
});
