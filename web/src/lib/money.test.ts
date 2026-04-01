import { describe, expect, it } from "vitest";
import { formatMinor, parseAmountToMinor } from "./money";

describe("formatMinor", () => {
  it("formats zero as 0.00", () => expect(formatMinor(0)).toBe("0.00"));
  it("formats whole units, 500 is 5.00", () => expect(formatMinor(500)).toBe("5.00"));
  it("formats sub-unit amounts, 7 is 0.07", () => expect(formatMinor(7)).toBe("0.07"));
  it("formats negatives, -905 is -9.05", () => expect(formatMinor(-905)).toBe("-9.05"));
});

describe("parseAmountToMinor", () => {
  it("parses 12.34 to 1234", () => expect(parseAmountToMinor("12.34")).toBe(1234));
  it("parses 12 and 12.3 with padding, 1200 and 1230", () => {
    expect(parseAmountToMinor("12")).toBe(1200);
    expect(parseAmountToMinor("12.3")).toBe(1230);
  });
  // The float trap: parseFloat("0.29") * 100 is 28.999... Guards against any future parseFloat rewrite.
  it("parses 0.29 to 29, the float trap case", () => expect(parseAmountToMinor("0.29")).toBe(29));
  it("rejects more than two decimals, negatives, empties, and junk", () => {
    for (const bad of ["12.345", "-5", "", "1,000", "abc"]) {
      expect(parseAmountToMinor(bad)).toBeNull();
    }
  });
  it("rejects amounts above the safe integer range", () => {
    expect(parseAmountToMinor("90071992547409931")).toBeNull();
  });
});
