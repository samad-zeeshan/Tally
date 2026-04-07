// Copies a value to the clipboard and flashes a check. Sits beside a row button, never inside one:
// nested interactive elements are invalid HTML and unreachable by keyboard.
import { useEffect, useRef, useState } from "react";
import { CheckIcon, CopyIcon } from "./icons";

interface CopyButtonProps {
  text: string;
  label: string;
}

export function CopyButton({ text, label }: CopyButtonProps) {
  const [copied, setCopied] = useState(false);
  const timer = useRef(0);

  useEffect(() => () => clearTimeout(timer.current), []);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      clearTimeout(timer.current);
      timer.current = window.setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard denied (permissions, insecure context): the id is still visible to select by hand.
    }
  };

  return (
    <button
      type="button"
      className={"copy-btn" + (copied ? " copied" : "")}
      onClick={copy}
      aria-label={copied ? "Copied" : label}
      title={copied ? "Copied" : label}
    >
      {copied ? <CheckIcon /> : <CopyIcon />}
    </button>
  );
}
