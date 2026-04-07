import { describe, expect, it } from "vitest";
import { timeAgo } from "./format";

const NOW = Date.parse("2026-07-05T12:00:00Z");
const at = (iso: string) => timeAgo(iso, NOW);

describe("timeAgo", () => {
  it("reads fresh timestamps as just now", () => {
    expect(at("2026-07-05T11:59:50Z")).toBe("just now");
    expect(at("2026-07-05T11:59:01Z")).toBe("just now");
  });
  it("counts minutes and hours", () => {
    expect(at("2026-07-05T11:59:00Z")).toBe("1m ago");
    expect(at("2026-07-05T11:01:00Z")).toBe("59m ago");
    expect(at("2026-07-05T09:00:00Z")).toBe("3h ago");
  });
  it("counts whole days past 24 hours", () => {
    expect(at("2026-07-03T12:00:00Z")).toBe("2d ago");
  });
  it("clamps a future timestamp to just now instead of a negative age", () => {
    expect(at("2026-07-05T12:00:30Z")).toBe("just now");
  });
});
