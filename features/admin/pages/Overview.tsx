"use client";

import { AuditTable } from "./AuditTable";
import { AiMonthCard } from "./AiUsagePages";
import Link from "next/link";
import { api } from "@/lib/http-api";
import { Circle, CircleCheck, LoadGate } from "@xweb/ui";
import { portalHref } from "@xweb/permissions";
import { useA, useAdminConsole } from "../console/context";
import { useLoad } from "../../useLoad";
import { Card, Kpi, num } from "../../ui";
import { DEFAULT_TENANT_ID } from "../adminModel";
import { PageHead } from "../PageHead";

// ------------------------------------------------------------------ overview
export function Overview() {
  const { owns, portal } = useAdminConsole();
  const A = useA();
  const { data, error, loading, reload } = useLoad(() => api.admin.overview(), []);
  if (!data) return <LoadGate load={{ data, error, loading, reload }} level={1} label="tổng quan">{() => null}</LoadGate>;
  const o = data!;
  return (<>
    <PageHead title="Tổng quan" sub="Số liệu thật từ cơ sở dữ liệu của nền tảng."/>
    {portal === "platform" ? <PlatformChecklist/> : portal === "all" ? <SetupChecklist users={o.users} projects={o.projects}/> : null}
    <div className="kpiGrid">
      <Kpi label="Người dùng" value={num(o.users)} hint={`${num(o.activeUsers)} đang hoạt động · ${num(o.disabledUsers)} bị khóa`}/>
      <Kpi label="Đăng nhập 30 ngày" value={num(o.usersLoggedIn30d)}/>
      <Kpi label="Workspace" value={num(o.workspaces)}/>
      <Kpi label="Ứng dụng" value={num(o.projects)} hint={`${num(o.publishedProjects)} đã xuất bản (demo)`}/>
      <Kpi label="Lượt AI hôm nay" value={num(o.aiRequestsToday)} hint={`${num(o.aiRequestsMonth)} trong tháng`}/>
      <Kpi label="Phiên bản tạo hôm nay" value={num(o.versionsToday)}/>
    </div>
    <div className="grid2">
      <Card title="Hoạt động gần đây" actions={<Link className="btn sm" href={A("/audit")}>Xem tất cả</Link>}><AuditTable rows={o.recentActivity} compact/></Card>
      {owns("ai") ? <AiMonthCard/> : null}
    </div>
  </>);
}

/**
 * "Thiết lập nền tảng": what the platform operator (SYSTEM_ADMIN) does first. It is NOT the company checklist: a platform operator has no business access, so "add users" / "create the first website"
 * (Builder Studio) would send them to a screen they cannot use. Only existing routes: companies, AI providers, models, limits.
 */
export function PlatformChecklist() {
  const A = useA();
  const tenants = useLoad(() => api.admin.tenants(), []);
  const providers = useLoad(() => api.admin.aiProviders(), []);
  const limits = useLoad(() => api.admin.aiLimits(), []);
  if (!tenants.data || !providers.data || !limits.data) return null;
  const items: { done: boolean; label: string; href: string; action: string }[] = [
    { done: tenants.data.some((t) => t.id !== DEFAULT_TENANT_ID && t.status !== "DELETED"), label: "Tạo công ty đầu tiên", href: A("/tenants"), action: "Tạo công ty" },
    { done: providers.data.some((p) => p.configured), label: "Thêm nhà cung cấp AI", href: A("/ai/providers"), action: "Thêm nhà cung cấp" },
    { done: providers.data.some((p) => p.models.some((m) => m.enabled)), label: "Chọn mô hình mặc định", href: A("/ai/models"), action: "Chọn mô hình" },
    { done: limits.data.customized, label: "Thiết lập hạn mức AI", href: A("/ai/limits"), action: "Thiết lập" },
  ];
  if (items.every((i) => i.done)) return null;
  return (
    <Card title="Thiết lập nền tảng">
      <p className="hint">Hòan thành các bước sau để nền tảng sẵn sàng cho các công ty. Tài khoản quản trị của từng công ty được tạo ở trang của công ty đó.</p>
      <ol className="checklist">{items.map((i) => (
        <li key={i.label} className={i.done ? "done" : ""}><span aria-hidden="true" className="xp-checkIcon">{i.done ? <CircleCheck size={16}/> : <Circle size={16}/>}</span> <b>{i.label}</b>{" "}
          {i.done ? <small className="muted">Đã xong</small> : <Link className="btn sm" href={i.href}>{i.action}</Link>}</li>))}</ol>
    </Card>
  );
}

/** "Thiết lập ban đầu": what a new company admin does first, each step linking to the exact screen. Hidden once everything is done. */
export function SetupChecklist({ users, projects }: { users: number; projects: number }) {
  const A = useA();
  const providers = useLoad(() => api.admin.aiProviders(), []);
  const limits = useLoad(() => api.admin.aiLimits(), []);
  if (!providers.data || !limits.data) return null;
  const items: { done: boolean; label: string; href: string; action: string }[] = [
    { done: true, label: "Tài khoản quản trị", href: A("/users"), action: "Xem" },
    { done: providers.data.some((p) => p.configured), label: "Thêm nhà cung cấp AI", href: A("/ai/providers"), action: "Thêm nhà cung cấp" },
    { done: providers.data.some((p) => p.models.some((m) => m.enabled)), label: "Chọn mô hình mặc định", href: A("/ai/models"), action: "Chọn mô hình" },
    { done: limits.data.customized, label: "Thiết lập hạn mức AI", href: A("/ai/limits"), action: "Thiết lập" },
    { done: users > 1, label: "Thêm người dùng", href: A("/users"), action: "Thêm người dùng" },
    { done: projects > 0, label: "Tạo ứng dụng đầu tiên", href: portalHref("studio"), action: "Mở Builder Studio" }
  ];
  if (items.every((i) => i.done)) return null;
  return (
    <Card title="Thiết lập ban đầu">
      <p className="hint">Hòan thành các bước sau để công ty bắt đầu dùng được AI Software Factory.</p>
      <ol className="checklist">{items.map((i) => (
        <li key={i.label} className={i.done ? "done" : ""}><span aria-hidden="true" className="xp-checkIcon">{i.done ? <CircleCheck size={16}/> : <Circle size={16}/>}</span> <b>{i.label}</b>{" "}
          {i.done ? <small className="muted">Đã xong</small> : <Link className="btn sm" href={i.href}>{i.action}</Link>}</li>))}</ol>
    </Card>
  );
}
