"use client";
/**
 * Publish / rollback / unpublish dialog, on the C2 HTTP contract (docs/parallel/c2/PUBLISH_API_CONTRACT.md @ 8d40218). The pure rules are in packages/api-client/src/release.ts.
 * Everything here is UX: the server decides (409 SCOPE_BUSY, 409 ROLLBACK_STALE, 403 …). `SiteInfo.operation` only mirrors who holds the scope; `pointerVersion` is shown for debugging and
 * is NEVER sent. A rollback that was accepted is not "done" until the 200 answer AND the site's pointer say so; a deployment is a success only at RUNNING.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/http-api";
import type { Deployment, SiteInfo } from "@/lib/http-types";
import {
  ReleaseKeyBook, SITE_IDLE_POLL_MS, SITE_POLL_MS, deploymentLabel, explainFailedDeployment, explainReleaseError, isBusyDeployment, isDeploymentSuccess, isReleaseBusy, isTerminalDeployment, operationBanner, publishBody,
  rollbackBody, rollbackCandidates, type ReleaseErrorView,
} from "@xweb/api-client";
import { useOverlayDialog } from "./useOverlayDialog";
import { webUrl } from "./siteAccessModel";
import { confirm, fmtDate, RadioGroup, ReasonButton } from "@xweb/ui";
import type { AppDefinitionV2 } from "@xweb/types";
import { diffAnnounced, parsePublicQueriesEvent, publicDataBlockers, publishApproval } from "./builder/core/publicData";


/** the calls the dialog makes (injected so the browser harness can drive every state; the default is the real client) */
export type ReleaseCalls = Pick<typeof api, "publish" | "getDeployment" | "listDeployments" | "site" | "rollbackSite" | "unpublishSite">;

const MAX_POLL_FAILURES = 5;
const NEEDS_PUBLISH = "Cần quyền xuất bản (APP_PUBLISH).";

export function PublishModal({ workspaceId, projectId, revision, current, versionNumber, onClose, onUnauthorized, allowed = ["PRIVATE", "PUBLIC"], canPublish, calls = api, timing, draft }: {
  /** the document that will be published (the draft at `revision`). With it the dialog lists what becomes public; without it (code apps) there is no public-data approval. */
  draft?: AppDefinitionV2 | null;
  workspaceId: string; projectId: string; revision: number; current: "PRIVATE" | "PUBLIC"; versionNumber?: number; onClose: () => void; onUnauthorized: () => void;
  /** visibilities this project may be published with (policy); default both */
  allowed?: ("PRIVATE" | "PUBLIC")[];
  /** APP_PUBLISH from the resolved permission set (UX only). Publish, rollback and unpublish are ONE lifecycle: APP_EDIT never stands in for it. */
  canPublish: boolean;
  calls?: ReleaseCalls;
  /** test seam: polling intervals in ms (defaults: SITE_POLL_MS while an operation is shown, SITE_IDLE_POLL_MS otherwise) */
  timing?: { busy?: number; idle?: number };
}) {
  const busyMs = timing?.busy ?? SITE_POLL_MS, idleMs = timing?.idle ?? SITE_IDLE_POLL_MS;
  const [visibility, setVisibility] = useState<"PRIVATE" | "PUBLIC">(allowed.includes(current) ? current : allowed[0]);
  const [deployment, setDeployment] = useState<Deployment | null>(null);
  const [error, setError] = useState<ReleaseErrorView | { kind: "text"; title: string; detail: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [site, setSite] = useState<SiteInfo | null>(null);
  const [history, setHistory] = useState<Deployment[]>([]);
  const [pending, setPending] = useState<null | { kind: "ROLLBACK" | "UNPUBLISH"; deploymentId?: string }>(null);
  const [note, setNote] = useState<string | null>(null);
  const [siteFailures, setSiteFailures] = useState(0);
  // polling is a chain of timeouts driven by these counters, not by the identity of the answers (an identical answer must not stop the chain)
  const [siteTick, setSiteTick] = useState(0), [depTick, setDepTick] = useState(0);
  // One key per LOGICAL request: the same payload keeps its key (a retry after a lost answer can never create a second operation), a different payload gets a new one,
  // and after an outcome the user wants to repeat the key is rotated (a replay would only return the old outcome).
  const publishKeys = useRef(new ReleaseKeyBook()), rollbackKeys = useRef(new ReleaseKeyBook()), unpublishKeys = useRef(new ReleaseKeyBook());
  // PAGE_SCHEMA public data: what THIS draft would make public, computed by the same rule as the server's allow-list (core/publicData.ts). The acknowledgement is a LOCAL confirmation (it is not a
  // field of POST /publish: review M1) and it is only asked for when a public query exists; it is tied to that exact list, so editing the queries asks again.
  const approval = draft ? publishApproval(draft) : null;
  const blockers = draft ? publicDataBlockers(draft) : [];
  const approvalKey = approval && approval.required ? approval.queries.map((q) => q.id).join(",") : "";
  const [ackKey, setAckKey] = useState<string | null>(null);
  const acknowledged = !approval?.required || ackKey === approvalKey;
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);

  const loadSite = useCallback(async () => {
    try {
      const [s, h] = await Promise.all([calls.site(workspaceId, projectId), calls.listDeployments(workspaceId, projectId).catch(() => null)]);
      if (!alive.current) return; setSite(s); if (h) setHistory(h); setSiteFailures(0); setSiteTick((n) => n + 1);
    } catch (e) { if (!alive.current) return; if (e instanceof ApiError && e.status === 401) onUnauthorized(); else { setSiteFailures((n) => n + 1); setSiteTick((n) => n + 1); } }
  }, [calls, workspaceId, projectId, onUnauthorized]);
  useEffect(() => { void loadSite(); }, [loadSite]);

  // SiteInfo.operation != null: another release operation holds the scope (also after a browser refresh in the middle of one): mirror it and poll until it ends.
  const operationHeld = isReleaseBusy(site);
  useEffect(() => {
    if (!operationHeld || siteFailures >= MAX_POLL_FAILURES) return;
    const t = setTimeout(() => void loadSite(), busyMs * (1 + siteFailures));
    return () => clearTimeout(t);
  }, [operationHeld, siteTick, siteFailures, loadSite, busyMs]);

  const deploymentBusy = deployment !== null && isBusyDeployment(deployment.status);
  // idle: a slow refresh, so an operation started elsewhere shows up (never while this dialog is itself running something)
  useEffect(() => {
    if (operationHeld || pending !== null || submitting || deploymentBusy || siteFailures >= MAX_POLL_FAILURES) return;
    const t = setTimeout(() => void loadSite(), idleMs);
    return () => clearTimeout(t);
  }, [operationHeld, pending, submitting, deploymentBusy, siteTick, siteFailures, loadSite, idleMs]);
  const locked = submitting || pending !== null || operationHeld || deploymentBusy;
  const dialog = useOverlayDialog("Xuất bản website", deploymentBusy || pending ? null : onClose);        // cannot be dismissed with Escape mid-operation

  // Poll the deployment until it ends (terminal = RUNNING | FAILED | ROLLED_BACK). ROLLING_BACK is busy, never success. A failed poll says nothing about the deployment itself.
  const [pollFailures, setPollFailures] = useState(0);
  useEffect(() => {
    if (!deployment || isTerminalDeployment(deployment.status) || pollFailures >= MAX_POLL_FAILURES) return;
    const t = setTimeout(() => {
      calls.getDeployment(workspaceId, projectId, deployment.id).then((d) => { setPollFailures(0); setDeployment(d); setDepTick((n) => n + 1); }).catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 401) { onUnauthorized(); return; }
        setPollFailures((n) => n + 1);
      });
    }, 800 * (1 + pollFailures * 2));
    return () => clearTimeout(t);
  }, [deployment, depTick, pollFailures, calls, workspaceId, projectId, onUnauthorized]);
  useEffect(() => { if (deployment && isTerminalDeployment(deployment.status)) void loadSite(); }, [deployment, loadSite]);

  const real = site ? site.provider !== "mock" : false;
  const fail = (e: unknown, keys?: ReleaseKeyBook) => {
    if (e instanceof ApiError && e.status === 401) { onUnauthorized(); return; }
    const v = explainReleaseError(e instanceof ApiError ? e : { status: 0 });
    if (v.newKey || (!v.retry && keys)) keys?.rotate();   // a refused request must not be replayed with the same key; a safe-to-retry one keeps it
    setError(v); if (v.reload) void loadSite();
  };

  async function start() {
    if (locked || !canPublish || !acknowledged) return;
    setSubmitting(true); setError(null); setNote(null);
    try { setDeployment(await calls.publish(workspaceId, projectId, visibility, revision, publishKeys.current.keyFor(publishBody(visibility, revision)))); setPollFailures(0); }
    catch (e) { fail(e, publishKeys.current); } finally { setSubmitting(false); }
  }
  function publishAgain() { publishKeys.current.rotate(); setDeployment(null); setError(null); setNote(null); setPollFailures(0); }

  async function rollback(target: Deployment, confirmed = false) {
    if (locked || !canPublish) return;
    // switching the LIVE site is not undone by closing this dialog: say what changes, and name the versions (M-019)
    if (!confirmed && !(await confirm({ title: `Phục vụ lại phiên bản ${target.versionNumber}?`, message: `Website đang chạy ${site?.currentVersionNumber ? `phiên bản ${site.currentVersionNumber}` : "bản hiện tại"} sẽ được thay bằng phiên bản ${target.versionNumber} ngay lập tức. Bạn có thể phục vụ lại bản mới hơn sau đó.`, confirmLabel: "Phục vụ lại bản này", danger: true }))) return;
    const expected = site?.currentDeploymentId ?? null;
    const req = rollbackBody({ deploymentId: target.id, expectedActiveDeploymentId: expected });
    setPending({ kind: "ROLLBACK", deploymentId: target.id }); setError(null); setNote(null);
    try {
      const info = await calls.rollbackSite(workspaceId, projectId, req, rollbackKeys.current.keyFor(req));
      if (!alive.current) return;
      setSite(info); rollbackKeys.current.rotate();
      // 200 = the site serves the restored release. Still verify it from the answer itself, never from the request having been accepted.
      setNote(info.currentDeploymentId === target.id ? `Đã phục vụ lại phiên bản ${target.versionNumber}.` : "Máy chủ trả lời thành công nhưng bản đang chạy không phải bản đã chọn. Hãy kiểm tra lại trạng thái.");
      void loadSite();
    } catch (e) {
      fail(e, rollbackKeys.current);
      if (e instanceof ApiError && e.status === 0) {   // unknown outcome: ask the server what is true now
        try { const s = await calls.site(workspaceId, projectId); if (alive.current) { setSite(s); if (s.currentDeploymentId === target.id && !s.operation) { setError(null); setNote(`Thao tác đã hoàn tất trên máy chủ: đang phục vụ phiên bản ${target.versionNumber}.`); } } } catch { /* the error above stays */ }
      }
    } finally { if (alive.current) setPending(null); }
  }

  async function unpublish() {
    if (locked || !canPublish) return;
    if (!(await confirm({ title: "Gỡ trang xuống?", message: "Địa chỉ của website sẽ báo không tìm thấy cho tới khi bạn xuất bản lại hoặc phục vụ lại một bản cũ.", confirmLabel: "Gỡ trang xuống", danger: true }))) return;
    const expected = site?.currentDeploymentId ?? null;
    setPending({ kind: "UNPUBLISH" }); setError(null); setNote(null);
    try {
      const info = await calls.unpublishSite(workspaceId, projectId, expected, unpublishKeys.current.keyFor({ expected }));
      if (!alive.current) return; setSite(info); unpublishKeys.current.rotate(); setNote("Trang đã được gỡ xuống."); void loadSite();
    } catch (e) { fail(e, unpublishKeys.current); } finally { if (alive.current) setPending(null); }
  }

  // retry affordance only where the contract allows it (SCOPE_BUSY: Retry-After 5 s; replay-safe unknown outcomes)
  const [retryIn, setRetryIn] = useState(0);
  const retryAfter = error && error.kind !== "text" && error.retry ? (error.retryAfterSeconds ?? 0) : 0;
  useEffect(() => { setRetryIn(retryAfter); }, [error, retryAfter]);
  useEffect(() => { if (retryIn <= 0) return; const t = setTimeout(() => setRetryIn((n) => n - 1), 1000); return () => clearTimeout(t); }, [retryIn]);
  const lastAction = useRef<null | (() => void)>(null);

  const candidates = rollbackCandidates(history, site?.currentDeploymentId);
  const banner = operationBanner(site?.operation);
  const failed = deployment && deployment.status === "FAILED" ? explainFailedDeployment(deployment) : null;

  return (
    <div className="overlay modalOverlay"><div className="modal" {...dialog.props} data-testid="release-modal" aria-busy={locked}>
      <h2 id={dialog.titleId}>Xuất bản website</h2>
      <p>{versionNumber ? `Phiên bản ${versionNumber}` : "Phiên bản hiện tại"} sẽ được đưa qua kiểm tra chính sách, bảo mật, build và triển khai.</p>
      {!canPublish ? <p className="hint" role="note" data-testid="release-no-permission">Bạn chỉ có quyền xem: xuất bản, hoàn tác và gỡ trang cần quyền xuất bản (APP_PUBLISH). Máy chủ kiểm tra quyền ở mọi lệnh gọi.</p> : null}
      {banner ? <p className="hint" role="status" aria-live="polite" data-testid="release-operation" data-kind={site!.operation!.kind}>{banner}</p> : null}
      {siteFailures >= MAX_POLL_FAILURES ? <p role="alert" className="hint" data-testid="site-reconnect">Mất kết nối khi đọc trạng thái trang. <button type="button" className="btn sm" onClick={() => { setSiteFailures(0); void loadSite(); }}>Tải lại trạng thái</button></p> : null}
      {!deployment && site && !real ? <p className="hint">Hiện tại môi trường xuất bản là <b>mô phỏng</b>: hệ thống tạo URL thử nghiệm, chưa có website thật nào được phục vụ.</p> : null}
      {!deployment && real ? <div className="siteBox" data-testid="site-box">{site!.online && site!.url
        ? <p>Đang phục vụ phiên bản {site!.currentVersionNumber ?? "—"} ({site!.visibility === "PRIVATE" ? "riêng tư — chỉ thành viên, đăng nhập bằng tài khoản công ty" : "công khai"}) tại{" "}
            <a href={webUrl(site!.url)} target="_blank" rel="noopener noreferrer">{site!.url}</a></p>
        : <p className="hint">{site!.slug ? "Trang đang được gỡ xuống." : "Chưa xuất bản lần nào."} Xuất bản sẽ tạo một trang tĩnh thật trên máy chủ.</p>}
        <small className="hint" data-testid="pointer-version">pointerVersion {site!.pointerVersion} (chỉ để quan sát, không gửi lại máy chủ)</small></div> : null}
      {!allowed.includes("PUBLIC") && !deployment ? <p className="hint">Quản trị viên đang tắt xuất bản <b>công khai</b> cho loại ứng dụng này; chỉ xuất bản riêng tư (thành viên đăng nhập bằng tài khoản công ty).</p> : null}
      {!deployment && approval?.required ? (
        <div className="publicBox" data-testid="public-queries-box" role="group" aria-label="Dữ liệu sẽ được công khai">
          <b data-testid="public-queries-count" data-count={approval.queries.length}>Dữ liệu sẽ được công khai ({approval.queries.length} truy vấn)</b>
          <p className="hint" data-testid="public-queries-warning">{approval.warning}</p>
          <ul data-testid="public-queries-list">{approval.queries.map((q) => (
            <li key={q.id} data-testid={`public-query:${q.id}`}><code>{q.id}</code> — {q.name} · khe {q.slotName} · {q.boundBy.length ? `dùng ở ${q.boundBy.join(", ")}` : "chưa gắn vào thành phần nào"}</li>))}</ul>
          {visibility !== "PUBLIC" ? <p className="hint" data-testid="public-queries-private-note">Trang đang xuất bản ở chế độ riêng tư: trang không nhận địa chỉ dữ liệu (apiBase) nên khách chưa thấy dữ liệu, nhưng danh sách truy vấn công khai vẫn được ghi vào bản phát hành này.</p> : null}
          <label className="checkRow"><input type="checkbox" data-testid="publish-ack" disabled={locked || !canPublish} checked={acknowledged} onChange={(e) => setAckKey(e.target.checked ? approvalKey : null)}/><span>Tôi xác nhận dữ liệu của các truy vấn trên được phép công khai.</span></label>
        </div>) : null}
      {!deployment && blockers.length ? (
        <div className="formError" role="alert" data-testid="public-data-blockers"><b>Máy chủ có thể từ chối bản này</b>
          <ul>{blockers.slice(0, 6).map((b, i) => <li key={i}>{b.message}</li>)}</ul></div>) : null}
      {!deployment ? (
        // M-030: a radio group (one Tab stop, arrow keys, "1 of 2, checked") instead of two class-only buttons
        <RadioGroup legend="Ai xem được website sau khi xuất bản?" value={visibility} onChange={setVisibility} disabled={locked || !canPublish}
          options={allowed.map((v) => ({ value: v, label: v === "PRIVATE" ? "Riêng tư" : "Công khai", hint: v === "PRIVATE" ? "Chỉ thành viên được cấp quyền." : "Mọi người có thể truy cập." }))}/>
      ) : (
        <div className="deployBox" role="status" aria-live="polite" data-testid="deployment" data-status={deployment.status}>
          <b data-testid="deployment-status">{deploymentLabel(deployment.status)}</b>
          <ol>{deployment.events.map((ev, i) => <li key={i}>{deploymentLabel(ev.status)}{ev.message ? ` — ${ev.message}` : ""}</li>)}</ol>
          {deployment.status === "ROLLING_BACK" ? <p className="hint" data-testid="deployment-rolling-back">Đang hoàn tác bước chuyển bản. Đây chưa phải thành công; trạng thái cuối sẽ là “Thất bại”.</p> : null}
          {(() => {
            const ev = deployment.events.find((x) => x.status === "PUBLIC_QUERIES");
            if (!ev) return null;
            const frozen = parsePublicQueriesEvent(ev.message), d = diffAnnounced(approval?.required ? approval.queries.map((q) => q.id) : [], frozen);
            return <div className="hint" role="status" data-testid="public-queries-event">Máy chủ đã ghi vào bản phát hành: <b>{frozen.join(", ") || "—"}</b>
              {d.onlyAnnounced.length || d.onlyFrozen.length ? <span className="formError" data-testid="public-queries-mismatch"> Khác với danh sách đã hiện trước khi xuất bản ({d.onlyAnnounced.length ? `thiếu: ${d.onlyAnnounced.join(", ")}` : ""}{d.onlyAnnounced.length && d.onlyFrozen.length ? "; " : ""}{d.onlyFrozen.length ? `thêm: ${d.onlyFrozen.join(", ")}` : ""}).</span> : null}</div>;
          })()}
          {isDeploymentSuccess(deployment.status) && deployment.url ? (deployment.mock ? <p><b>Demo deployment</b> — chưa có website thật nào được phục vụ. Địa chỉ thử nghiệm: <code>{deployment.url}</code></p>
            : <p data-testid="deployment-success">Website đã lên: <a href={webUrl(deployment.url)} target="_blank" rel="noopener noreferrer">{deployment.url}</a></p>) : null}
          {failed ? <div className="formError" role="alert" data-testid="deployment-failed" data-code={failed.code ?? ""}><b>{failed.title}</b><p>{failed.detail}</p></div> : null}
          {deployment.status === "ROLLED_BACK" ? <p className="hint" data-testid="deployment-rolled-back">Bản này đã được thay bằng một bản khác và không thể phục vụ lại; xuất bản lại phiên bản đó nếu cần.</p> : null}
          {pollFailures >= MAX_POLL_FAILURES && deploymentBusy ? <p role="alert" className="hint">Không đọc được trạng thái triển khai. Triển khai có thể vẫn đang chạy trên máy chủ; bấm “Kiểm tra lại”.</p> : null}
        </div>
      )}
      {!deployment && real && (candidates.length > 0 || site?.currentDeploymentId) ? <details className="siteHistory" open={candidates.length > 0}><summary>Các lần xuất bản ({history.filter((h) => h.status === "RUNNING" && !h.mock).length})</summary>
        <ul>{history.filter((h) => h.status === "RUNNING" && !h.mock).map((h) => <li key={h.id} data-testid={`release:${h.id}`}>
          <span>Phiên bản {h.versionNumber} · {h.visibility === "PRIVATE" ? "riêng tư" : "công khai"} · {fmtDate(h.createdAt)}</span>
          {site?.currentDeploymentId === h.id ? <b>Đang phục vụ</b>
            : <ReasonButton className="btn ghost" data-testid={`rollback:${h.id}`} unavailable={locked || !canPublish} reason={!canPublish ? NEEDS_PUBLISH : undefined} reasonPlacement="inline"
                onClick={() => { lastAction.current = () => void rollback(h, true); void rollback(h); }}>{pending?.kind === "ROLLBACK" && pending.deploymentId === h.id ? "Đang hoàn tác…" : "Phục vụ lại bản này"}</ReasonButton>}
        </li>)}</ul>
        {history.some((h) => h.status === "ROLLED_BACK") ? <p className="hint">Các bản “đã hoàn tác” không còn phục vụ lại được; xuất bản lại phiên bản đó nếu cần.</p> : null}</details> : null}
      {note ? <p className="hint" role="status" data-testid="release-note">{note}</p> : null}
      {error ? <div className="formError" role="alert" data-testid="release-error" data-kind={error.kind}><b>{error.title}</b><p>{error.detail}</p>
        {error.kind !== "text" && error.retry ? <button type="button" className="btn sm" data-testid="release-retry" disabled={retryIn > 0 || locked} onClick={() => { setError(null); (lastAction.current ?? (() => void start()))(); }}>{retryIn > 0 ? `Thử lại sau ${retryIn}s` : "Thử lại"}</button> : null}
        {error.kind !== "text" && error.reload ? <button type="button" className="btn sm" data-testid="release-reload" onClick={() => { setError(null); void loadSite(); }}>Tải lại trạng thái</button> : null}</div> : null}
      <div className="modalActions">
        {!deployment && real && site?.online ? <ReasonButton className="btn ghost" data-testid="unpublish" unavailable={locked || !canPublish} reason={!canPublish ? NEEDS_PUBLISH : undefined} onClick={() => { lastAction.current = () => void unpublish(); void unpublish(); }}>{pending?.kind === "UNPUBLISH" ? "Đang gỡ…" : "Gỡ trang xuống"}</ReasonButton> : null}
        <button className="btn ghost" onClick={onClose} disabled={deploymentBusy || pending !== null}>{deployment ? "Đóng" : "Hủy"}</button>
        {!deployment ? <ReasonButton className="btn primary" data-testid="publish" unavailable={locked || !canPublish || !acknowledged}
            reason={!canPublish ? NEEDS_PUBLISH : locked ? undefined : !acknowledged ? "Hãy xác nhận dữ liệu công khai ở trên trước khi xuất bản." : undefined}
            onClick={() => { lastAction.current = null; void start(); }}>{submitting ? "Đang gửi…" : "Xuất bản"}</ReasonButton>
          : deploymentBusy && pollFailures >= MAX_POLL_FAILURES ? <button className="btn primary" data-testid="publish-recheck" onClick={() => setPollFailures(0)}>Kiểm tra lại</button>
          : deploymentBusy ? <button className="btn primary" disabled>Đang xử lý…</button>
          : canPublish && deployment.status !== "RUNNING" ? <button className="btn primary" data-testid="publish-again" onClick={publishAgain}>Xuất bản lại</button> : null}
      </div>
    </div></div>
  );
}
