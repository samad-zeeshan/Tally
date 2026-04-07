// Date and relative-time formatting. timeAgo takes now as a parameter so it stays pure and testable.

const DATE_TIME = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

export function formatDateTime(iso: string): string {
  return DATE_TIME.format(new Date(iso));
}

export function timeAgo(iso: string, nowMs: number): string {
  // A clock skewed slightly ahead of the server reads as "just now", never a negative age.
  const seconds = Math.floor(Math.max(0, nowMs - Date.parse(iso)) / 1000);
  if (seconds < 60) {
    return "just now";
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h ago`;
  }
  return `${Math.floor(hours / 24)}d ago`;
}
