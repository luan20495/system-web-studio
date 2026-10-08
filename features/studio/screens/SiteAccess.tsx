"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/http-api";
import { errText, StateView } from "../../ui";
import { S } from "../base";

/**
 * A private published site sends visitors here (ADR 0009). Signed in on the Studio, a member gets a single-use ticket that the sites
 * host exchanges for its own session; the Studio cookie never leaves this origin. Only the server-built redirect is followed.
 */
export function SiteAccess() {
  const params = useSearchParams();
  const site = params.get("site") ?? ""; const path = params.get("path") ?? "/";
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (!/^[a-z0-9][a-z0-9-]{1,79}$/.test(site)) { setErr("Liên kết không hợp lệ."); return; }
    api.siteAccessTicket(site, path).then((r) => { window.location.assign(r.redirect); })
      .catch((x: unknown) => setErr(x instanceof ApiError && x.status === 404 ? "Bạn không có quyền xem trang riêng tư này, hoặc trang không còn tồn tại." : errText(x, "Không mở được trang.")));
  }, [site, path]);
  return err ? <StateView level={1} kind="forbidden" title="Không mở được trang" detail={<p>{err}</p>} action={<Link className="btn" href={S()}>Về Studio</Link>}/>
    : <StateView level={1} kind="loading" title="Đang mở trang riêng tư…"/>;
}
