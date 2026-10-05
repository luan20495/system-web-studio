import { useEffect, useState } from "react";
import { Button, Card, Heading, Input, Layout, Navbar, Stack, Text } from "@company/ui";
import { useRuntimeConfig } from "@company/app-sdk";

type Item = { id: number; title: string; created_at: string };

/** Starting point of a server app: the UI calls its own API (./api/…), which runs in the platform's isolated runtime with its own database. */
export function App() {
  const config = useRuntimeConfig();
  const [items, setItems] = useState<Item[] | null>(null);
  const [title, setTitle] = useState("");
  const [error, setError] = useState<string | null>(null);
  async function load() {
    try { const r = await fetch("./api/items"); if (!r.ok) throw new Error(`HTTP ${r.status}`); setItems(await r.json() as Item[]); }
    catch (e) { setError(`Không tải được dữ liệu (${(e as Error).message}).`); }
  }
  useEffect(() => { void load(); }, []);
  async function add() {
    setError(null);
    const r = await fetch("./api/items", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title }) });
    if (!r.ok) { setError("Không thêm được."); return; }
    setTitle(""); void load();
  }
  return (
    <Layout header={<Navbar brand={config.appName} items={[{ label: "Danh sách", href: "#", current: true }]}/>}>
      <Stack gap="lg" align="center">
        <Card padding="lg">
          <Heading level={1}>Ứng dụng có máy chủ</Heading>
          <Text tone="muted">Dữ liệu lưu trong cơ sở dữ liệu riêng của ứng dụng.</Text>
          <Stack direction="row" gap="sm" align="end">
            <Input label="Mục mới" value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)}/>
            <Button onClick={() => void add()} disabled={!title.trim()}>Thêm</Button>
          </Stack>
          {error ? <Text>⚠ {error}</Text> : null}
          {items === null ? <Text>Đang tải…</Text> : items.length === 0 ? <Text tone="muted">Chưa có mục nào.</Text> :
            <ul>{items.map((i) => <li key={i.id}>{i.title}</li>)}</ul>}
        </Card>
      </Stack>
    </Layout>
  );
}
