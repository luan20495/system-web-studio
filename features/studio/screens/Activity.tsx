"use client";

import Link from "next/link";
import { api } from "@/lib/http-api";
import { useLoad } from "../../useLoad";
import { actionLabel, ago, Card, ErrorState, StateView } from "../../ui";
import { S } from "../base";

export function Activity() {
  const { data, error, loading, reload } = useLoad(() => api.myActivity(50), []);
  return (<>
    <div className="pageHead"><div><h1>Hoạt động của tôi</h1><p>Lấy từ nhật ký kiểm toán của hệ thống.</p></div></div>
    <Card>{error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.length === 0 ? <StateView kind="empty" title="Chưa có hoạt động"/> : (
      <ul className="activityList">{data!.map((a) => <li key={a.id}><span className="muted">{ago(a.createdAt)}</span><b>{actionLabel(a.action)}</b>{a.projectId ? <Link href={S(`/projects/${a.projectId}`)}>mở ứng dụng</Link> : null}</li>)}</ul>
    )}</Card>
  </>);
}
