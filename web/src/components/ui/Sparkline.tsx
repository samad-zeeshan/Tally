// A small balance-trend chart: gradient area, line, and a dot on the latest point. The geometry lives
// in lib/sparkline where it is unit-tested; this component only renders it.
import { useId, useMemo } from "react";
import { sparklinePaths } from "../../lib/sparkline";

interface SparklineProps {
  values: number[];
  ariaLabel: string;
}

export function Sparkline({ values, ariaLabel }: SparklineProps) {
  const paths = useMemo(() => sparklinePaths(values, 240, 56), [values]);
  const gradientId = useId();
  if (paths === null) {
    return null;
  }
  return (
    <svg className="spark" viewBox="0 0 240 56" preserveAspectRatio="none" role="img" aria-label={ariaLabel}>
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="currentColor" stopOpacity="0.25" />
          <stop offset="1" stopColor="currentColor" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={paths.area} fill={`url(#${gradientId})`} stroke="none" />
      {/* non-scaling-stroke keeps the line crisp under the stretched viewBox */}
      <path d={paths.line} fill="none" stroke="currentColor" strokeWidth="2" vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={paths.lastX} cy={paths.lastY} r="2.5" fill="currentColor" />
    </svg>
  );
}
