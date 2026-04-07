// The full icon set, inline SVG on currentColor so themes restyle them for free. Decorative by
// default: every icon is aria-hidden and gets its meaning from the text or label beside it.
import type { ReactNode } from "react";

function icon(path: ReactNode, size = 16) {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {path}
    </svg>
  );
}

export const PlusIcon = () => icon(<path d="M12 5v14M5 12h14" />);
export const WalletIcon = () =>
  icon(
    <>
      <rect x="3" y="6" width="18" height="13" rx="2" />
      <path d="M3 10h18M16 15h1" />
    </>,
    22,
  );
export const BookIcon = () =>
  icon(
    <>
      <path d="M4 4h9a4 4 0 0 1 4 4v12H8a4 4 0 0 1-4-4V4Z" />
      <path d="M8 8h5M8 12h5" />
    </>,
    22,
  );
export const SwapIcon = () => icon(<path d="M7 4v13M7 4 4 7m3-3 3 3M17 20V7m0 13 3-3m-3 3-3-3" />, 18);
export const SunIcon = () =>
  icon(
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4m11.4-11.4 1.4-1.4" />
    </>,
    18,
  );
export const MoonIcon = () => icon(<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />, 18);
export const CopyIcon = () =>
  icon(
    <>
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M5 15V5a2 2 0 0 1 2-2h10" />
    </>,
    14,
  );
export const CheckIcon = () => icon(<path d="m4 12.5 5 5L20 6.5" />, 14);
export const XIcon = () => icon(<path d="M6 6l12 12M18 6 6 18" />, 14);
export const ZapIcon = () => icon(<path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z" />, 14);
export const CircleCheckIcon = () =>
  icon(
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m8.5 12.5 2.5 2.5 5-6" />
    </>,
    18,
  );
export const AlertIcon = () =>
  icon(
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5V13m0 3.5h.01" />
    </>,
    18,
  );
