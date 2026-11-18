interface TelemetrySparklineProps {
  label: string;
  values: number[];
  unit: string;
  min?: number;
  max?: number;
  colorClass?: string;
  strokeColor: string;
}

export function TelemetrySparkline({
  label,
  values,
  unit,
  min = 0,
  max = 100,
  strokeColor,
}: TelemetrySparklineProps) {
  const width = 260;
  const height = 56;
  const latest = values.length > 0 ? values[values.length - 1] : null;
  const span = Math.max(1, max - min);

  const points = values
    .map((v, idx) => {
      const x = values.length <= 1 ? width : (idx / (values.length - 1)) * width;
      const norm = Math.max(0, Math.min(1, (v - min) / span));
      const y = height - 4 - norm * (height - 8);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');

  return (
    <div className="rounded-lg border border-border bg-muted/30 p-3">
      <div className="flex items-center justify-between text-xs">
        <span className="text-muted-foreground">{label}</span>
        <span className="mono tabular font-semibold">
          {latest !== null ? `${latest.toFixed(1)}${unit}` : '-'}
        </span>
      </div>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="mt-2 h-14 w-full overflow-visible"
        aria-label={`${label}趋势图`}
      >
        <line x1="0" y1={height / 2} x2={width} y2={height / 2} stroke="var(--border)" strokeDasharray="3 3" />
        {values.length >= 2 && (
          <polyline
            fill="none"
            stroke={strokeColor}
            strokeWidth="2"
            strokeLinejoin="round"
            strokeLinecap="round"
            points={points}
          />
        )}
      </svg>
    </div>
  );
}
