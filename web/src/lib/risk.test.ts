import { describe, expect, it } from "vitest";
import { riskLevel, ruleLabel } from "./risk";

describe("riskLevel", () => {
  it("is clear when no rule fired", () => {
    expect(riskLevel(0, 40)).toBe("clear");
  });
  it("is watch below the threshold", () => {
    expect(riskLevel(15, 40)).toBe("watch");
    expect(riskLevel(39, 40)).toBe("watch");
  });
  it("is flagged at and above the threshold, the same line the server uses", () => {
    expect(riskLevel(40, 40)).toBe("flagged");
    expect(riskLevel(100, 40)).toBe("flagged");
  });
});

describe("ruleLabel", () => {
  it("names each baseline rule in plain words", () => {
    expect(ruleLabel("velocity")).toBe("many payments in 10 minutes");
    expect(ruleLabel("amount_deviation")).toBe("far above the usual amount");
    expect(ruleLabel("new_counterparty")).toBe("new payee");
    expect(ruleLabel("round_amount")).toBe("round amount");
    expect(ruleLabel("time_of_day")).toBe("unusual hour");
  });
  it("falls back to the rule name with spaces for a rule it does not know", () => {
    expect(ruleLabel("pass_through")).toBe("pass through");
  });
});
