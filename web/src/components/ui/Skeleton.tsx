// Shimmer placeholders shown while data loads, shaped like the content they stand in for.

interface SkeletonProps {
  lines?: number;
  tall?: boolean;
}

export function Skeleton({ lines = 3, tall = false }: SkeletonProps) {
  return (
    <div className="skeleton" aria-hidden="true">
      {Array.from({ length: lines }, (_, i) => (
        <span
          key={i}
          className={"skel-line" + (tall && i === 0 ? " tall" : "")}
          // Ragged edges read as content, not as a striped box.
          style={{ width: `${100 - ((i * 17) % 35)}%` }}
        />
      ))}
    </div>
  );
}
