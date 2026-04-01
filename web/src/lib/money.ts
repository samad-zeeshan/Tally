// Display formatting and parsing for money. Money is integer minor units in the client too: a JS number
// is a double, so amounts only ever hold integers and only ever see integer operations. No parseFloat,
// no decimal multiplication anywhere.
import type { MinorUnits } from "../api/types";

// 1234 -> "12.34", -905 -> "-9.05". Sign from the integer, then integer division and remainder.
export function formatMinor(amount: MinorUnits): string {
  const negative = amount < 0;
  const abs = Math.abs(amount);
  const units = Math.trunc(abs / 100);
  const cents = abs % 100;
  return (negative ? "-" : "") + units + "." + String(cents).padStart(2, "0");
}

// "12.34" -> 1234, bad input -> null. Integer math only: parseFloat("0.29") * 100 is 28.999... in doubles,
// so the string is split on the dot and the pieces are combined as integers.
export function parseAmountToMinor(input: string): MinorUnits | null {
  const trimmed = input.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) {
    return null;
  }
  const [whole, fraction = ""] = trimmed.split(".");
  const cents = (fraction + "00").slice(0, 2);
  const minor = Number(whole) * 100 + Number(cents);
  return Number.isSafeInteger(minor) ? minor : null;
}
