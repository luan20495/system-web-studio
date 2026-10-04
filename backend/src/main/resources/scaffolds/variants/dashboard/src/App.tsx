import { useEffect, useMemo, useState } from "react";
import { Card, Heading, Layout, Navbar, Select, Stack, Text } from "@company/ui";
import { useRuntimeConfig } from "@company/app-sdk";
import { SAMPLE, type Row } from "./data";

/**
 * Dashboard starting point: KPI cards and SVG charts (no chart library needed). Data: the sample in ./data.ts, or JSON from the API of a
 * server app on the same sites host (set DATA_URL in ./data.ts, e.g. "../my-tool/api/items").
 */
export function App() {
  const config = useRuntimeConfig();
  const [rows, setRows] = useState<Row[]>(SAMPLE.rows);
  const [metric, setMetric] = useState<"revenue" | "orders">("revenue");
  useEffect(() => {
    if (!SAMPLE.url) return;
    fetch(SAMPLE.url).then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status))))).then((d) => Array.isArray(d) && setRows(d as Row[])).catch(() => undefined);
  }, []);
  const total = useMemo(() => rows.reduce((a, r) => a + r[metric], 0), [rows, metric]);
  const max = Math.max(1, ...rows.map((r) => r[metric]));
  return (
    <Layout header={<Navbar brand={config.appName} items={[{ label: "Tổng quan", href: "#", current: true }]}/>}>
      <Stack gap="lg">
        <Stack direction="row" gap="md" wrap>
          <Card padding="md"><Text tone="muted">Tổng {metric === "revenue" ? "doanh thu" : "đơn hàng"}</Text><Heading level={2}>{total.toLocaleString("vi-VN")}</Heading></Card>
          <Card padding="md"><Text tone="muted">Số kỳ</Text><Heading level={2}>{rows.length}</Heading></Card>
          <Card padding="md"><Text tone="muted">Trung bình / kỳ</Text><Heading level={2}>{Math.round(total / Math.max(1, rows.length)).toLocaleString("vi-VN")}</Heading></Card>
        </Stack>
        <Card padding="lg" title="Theo kỳ">
          <Select label="Chỉ số" value={metric} onChange={(e) => setMetric(e.target.value as "revenue" | "orders")}
            options={[{ value: "revenue", label: "Doanh thu" }, { value: "orders", label: "Đơn hàng" }]}/>
          <svg role="img" aria-label={`Biểu đồ cột ${metric}`} viewBox={`0 0 ${rows.length * 60} 200`} width="100%" height="220">
            {rows.map((r, i) => { const h = Math.round((r[metric] / max) * 160);
              return <g key={r.label}><rect x={i * 60 + 10} y={180 - h} width={40} height={h} rx={4} fill="#0b6f63"/><text x={i * 60 + 30} y={196} textAnchor="middle" fontSize="11">{r.label}</text></g>; })}
          </svg>
        </Card>
      </Stack>
    </Layout>
  );
}
