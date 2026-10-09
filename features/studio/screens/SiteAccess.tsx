"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/http-api";
import { errText, StateView } from "../../ui";
import { S } from "../base";
import { safeSitePath, safeTicketRedirect } from "../siteAccessModel";

/**
 * A private published site sends visitors here (ADR 0009). Signed in on the Studio, a member gets a single-use ticket that the sites
 * host exchanges for its own session; the Studio cookie never leaves this origin. Only the server-built redirect is followed.
 */
export function SiteAccess() {
  const params = useSearchParams();
  const site = params.get("site") ?? ""; const path = safeSitePath(params.get("path"));   // M-051: same rule as the server; an odd path opens the site root
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (!/^[a-z0-9][a-z0-9-]{1,79}$/.test(site)) { setErr("Liên kết không hợp lệ."); return; }
    api.siteAccessTicket(site, path).then((r) => {
      // M-051: follow only a plain https ticket link to the sites host (never javascript:, data:, credentials in the URL)
      const to = safeTicketRedirect(r?.redirect);
      if (to) window.location.assign(to); else setErr("Máy chủ trả về liên kết không hợp lệ. Hãy thử lại hoặc báo quản trị viên.");
    })
      .catch((x: unknown) => setErr(x instanceof ApiError && x.status === 404 ? "Bạn không có quyền xem trang riêng tư này, hoặc trang không còn tồn tại." : errText(x, "Không mở được trang.")));
  }, [site, path]);
  return err ? <StateView level={1} kind="forbidden" title="Không mở được trang" detail={<p>{err}</p>} action={<Link className="btn" href={S()}>Về Studio</Link>}/>
    : <StateView level={1} kind="loading" title="Đang mở trang riêng tư…"/>;
}
