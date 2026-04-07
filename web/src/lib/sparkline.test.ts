import { describe, expect, it } from "vitest";
import { sparklinePaths } from "./sparkline";

const firstY = (line: string) => Number(line.split(" ")[1]);

describe("sparklinePaths", () => {
  it("returns null with nothing to draw", () => {
    expect(sparklinePaths([], 240, 56)).toBeNull();
  });

  it("draws a single value as a flat full-width line at mid-height", () => {
    const p = sparklinePaths([500], 240, 56)!;
    expect(p.line).toBe("M4 28 L236 28");
    expect(p.lastX).toBe(236);
    expect(p.lastY).toBe(28);
  });

  it("centers a flat series instead of hugging an edge", () => {
    const p = sparklinePaths([100, 100, 100], 240, 56)!;
    expect(firstY(p.line)).toBe(28);
    expect(p.lastY).toBe(28);
  });

  it("puts a rising series higher at the end, in svg coordinates", () => {
    const p = sparklinePaths([0, 50, 100], 240, 56)!;
    expect(p.lastY).toBeLessThan(firstY(p.line));   // svg y grows downward
    expect(firstY(p.line)).toBe(52);                // min sits at the bottom pad
    expect(p.lastY).toBe(4);                        // max sits at the top pad
  });

  it("closes the area path down to the baseline", () => {
    const p = sparklinePaths([1, 2, 3], 240, 56)!;
    expect(p.area.startsWith(p.line)).toBe(true);
    expect(p.area.endsWith("L4 56 Z")).toBe(true);
  });
});
