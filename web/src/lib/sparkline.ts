// Pure geometry for the balance sparkline: values in, SVG path strings out. Kept free of React and
// the DOM so the scaling and the edge cases (empty, single point, flat series) are unit-testable.

export interface SparklinePaths {
  line: string;
  area: string;
  lastX: number;
  lastY: number;
}

const round2 = (n: number): number => Math.round(n * 100) / 100;

export function sparklinePaths(values: number[], width: number, height: number, pad = 4): SparklinePaths | null {
  if (values.length === 0) {
    return null;
  }
  // One value still draws: duplicate it so the line spans the full width.
  const points = values.length === 1 ? [values[0], values[0]] : values;
  const min = Math.min(...points);
  const max = Math.max(...points);
  const innerH = height - pad * 2;
  const step = (width - pad * 2) / (points.length - 1);
  const coords = points.map((value, i) => {
    // A flat series sits mid-height rather than hugging an arbitrary edge.
    const y = max === min ? height / 2 : pad + innerH - ((value - min) / (max - min)) * innerH;
    return { x: round2(pad + i * step), y: round2(y) };
  });
  const line = coords.map((p, i) => `${i === 0 ? "M" : "L"}${p.x} ${p.y}`).join(" ");
  const last = coords[coords.length - 1];
  const area = `${line} L${last.x} ${height} L${coords[0].x} ${height} Z`;
  return { line, area, lastX: last.x, lastY: last.y };
}
