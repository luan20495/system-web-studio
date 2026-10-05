import { useEffect, useState } from "react";
import { Button, Card, Heading, Input, Layout, Navbar, Stack, Table, Text } from "@company/ui";
import { useRuntimeConfig } from "@company/app-sdk";

type Req = { id: number; title: string; amount: string; state: string; requested_by: string | null; created_at: string };
const STATE: Record<string, string> = { SUBMITTED: "Chờ duyệt", APPROVED: "Đã duyệt", REJECTED: "Từ chối" };

/** Approval workflow: submit → approve/reject (approvers from the APPROVERS secret), every step recorded on the server. */
export function App() {
  const config = useRuntimeConfig();
  const [rows, setRows] = useState<Req[]>([]); const [title, setTitle] = useState(""); const [amount, setAmount] = useState("0"); const [msg, setMsg] = useState<string | null>(null);
  const load = () => fetch("./api/requests").then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status))))).then((d) => setRows(d as Req[])).catch((e) => setMsg(`Không tải được (${(e as Error).message}).`));
  useEffect(() => { void load(); }, []);
  async function post(path: string, body: unknown) {
    setMsg(null);
    const r = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const d = await r.json().catch(() => ({})) as { error?: string };
    if (!r.ok) setMsg(d.error ?? "Không thực hiện được."); else void load();
  }
  return (
    <Layout header={<Navbar brand={config.appName} items={[{ label: "Đề nghị", href: "#", current: true }]}/>}>
      <Stack gap="lg">
        <Card padding="lg">
          <Heading level={1}>Quy trình duyệt</Heading>
          <Stack direction="row" gap="sm" align="end">
            <Input label="Nội dung đề nghị" value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)}/>
            <Input label="Số tiền" type="number" min={0} value={amount} onChange={(e) => setAmount(e.target.value)}/>
            <Button disabled={!title.trim()} onClick={() => void post("./api/requests", { title, amount: Number(amount) }).then(() => setTitle(""))}>Gửi</Button>
          </Stack>
          {msg ? <Text>⚠ {msg}</Text> : null}
        </Card>
        <Table<Req> caption="Đề nghị" rows={rows} rowKey={(r) => String(r.id)} columns={[
          { key: "title", header: "Nội dung" }, { key: "amount", header: "Số tiền" }, { key: "requested_by", header: "Người gửi" },
          { key: "state", header: "Trạng thái", render: (r) => r.state === "SUBMITTED" ? <Stack direction="row" gap="xs">
            <Button size="sm" onClick={() => void post(`./api/requests/${r.id}/decision`, { decision: "APPROVE" })}>Duyệt</Button>
            <Button size="sm" variant="outline" onClick={() => void post(`./api/requests/${r.id}/decision`, { decision: "REJECT" })}>Từ chối</Button></Stack> : STATE[r.state] }
        ]}/>
      </Stack>
    </Layout>
  );
}
