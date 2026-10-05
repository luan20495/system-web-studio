import { useEffect, useState } from "react";
import { Button, Card, Heading, Input, Layout, Navbar, Select, Stack, Table, Text } from "@company/ui";
import { useRuntimeConfig } from "@company/app-sdk";

type Rec = { id: number; title: string; status: string; note: string; created_by: string | null; updated_by: string | null; updated_at: string };
const STATUS: Record<string, string> = { OPEN: "Mới", IN_PROGRESS: "Đang xử lý", DONE: "Xong" };

/** Internal tool: a table of records with status, owner and history, stored in the app's own database (server: server/server.ts). */
export function App() {
  const config = useRuntimeConfig();
  const [rows, setRows] = useState<Rec[]>([]); const [title, setTitle] = useState(""); const [error, setError] = useState<string | null>(null);
  const load = () => fetch("./api/records").then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status))))).then((d) => setRows(d as Rec[])).catch((e) => setError(`Không tải được (${(e as Error).message}).`));
  useEffect(() => { void load(); }, []);
  async function send(method: string, path: string, body?: unknown) {
    setError(null);
    const r = await fetch(path, { method, headers: { "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
    if (!r.ok) setError("Không lưu được."); else void load();
  }
  return (
    <Layout header={<Navbar brand={config.appName} items={[{ label: "Hồ sơ", href: "#", current: true }]}/>}>
      <Stack gap="lg">
        <Card padding="lg">
          <Heading level={1}>Công cụ nội bộ</Heading>
          <Stack direction="row" gap="sm" align="end">
            <Input label="Tiêu đề hồ sơ" value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)}/>
            <Button disabled={!title.trim()} onClick={() => void send("POST", "./api/records", { title }).then(() => setTitle(""))}>Thêm</Button>
          </Stack>
          {error ? <Text>⚠ {error}</Text> : null}
        </Card>
        <Table<Rec> caption="Hồ sơ" rows={rows} rowKey={(r) => String(r.id)} columns={[
          { key: "title", header: "Tiêu đề" },
          { key: "status", header: "Trạng thái", render: (r) => <Select label="" aria-label={`Trạng thái ${r.title}`} value={r.status} onChange={(e) => void send("PATCH", `./api/records/${r.id}`, { status: e.target.value })}
            options={Object.entries(STATUS).map(([value, label]) => ({ value, label }))}/> },
          { key: "updated_by", header: "Cập nhật bởi" },
          { key: "updated_at", header: "Lúc", render: (r) => new Date(r.updated_at).toLocaleString("vi-VN") }
        ]}/>
      </Stack>
    </Layout>
  );
}
