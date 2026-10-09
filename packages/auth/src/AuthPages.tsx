"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { api, ApiError } from "@xweb/api-client";
import type { AuthConfig } from "@xweb/types";
import { useSession } from "./session";
import { accessiblePortals, canAccessPortal, portalHref, portalOfPath, PORTAL_LABEL, rememberPortal, rememberedPortal, resolvePortalPostLogin, resolvePostLogin, safeNext, type Portal, type PortalId } from "@xweb/permissions";
import { Ban, BrandLogo, Clock, ErrorState, errText, Field, Inbox } from "@xweb/ui";

const SSO_ERRORS: Record<string, string> = {
  not_provisioned: "Tài khoản SSO của bạn chưa được cấp quyền. Liên hệ quản trị viên.", disabled: "Tài khoản đã bị vô hiệu hóa.",
  failed: "Đăng nhập SSO thất bại. Hãy thử lại.", no_identity: "Nhà cung cấp SSO không trả về danh tính hợp lệ."
};

function AuthFrame({ children, wide }: { children: React.ReactNode; wide?: boolean }) {
  return (
    <main className="authPage xp-bg-auth">
      <div className={`authPanel${wide ? " wide" : ""}`}>
        <div className="authBrand"><BrandLogo height={28}/></div>
        {children}
      </div>
      <p className="authFoot">Nền tảng nội bộ · truy cập được kiểm soát và ghi nhật ký</p>
    </main>
  );
}

/** The employee opens the one-time link (token in the URL fragment, never sent to a server log) and chooses a password. */
export function ActivatePage() {
  const router = useRouter();
  const [token] = useState(() => (typeof window === "undefined" ? "" : window.location.hash.replace(/^#/, "")));
  const [info, setInfo] = useState<{ username: string; displayName: string; purpose: string } | null>(null);
  const [bad, setBad] = useState(false); const [password, setPassword] = useState(""); const [again, setAgain] = useState("");
  const [busy, setBusy] = useState(false); const [error, setError] = useState<string | null>(null); const [done, setDone] = useState(false);
  useEffect(() => { if (!token) { setBad(true); return; } api.inspectActivation(token).then(setInfo).catch(() => setBad(true)); }, [token]);
  async function submit(e: FormEvent) {
    e.preventDefault(); setError(null);
    if (password !== again) { setError("Hai mật khẩu chưa giống nhau."); return; }
    setBusy(true);
    try { await api.completeActivation(token, password); window.history.replaceState(null, "", window.location.pathname); setDone(true); }
    catch (err) { setError(errText(err, "Chưa đặt được mật khẩu.")); } finally { setBusy(false); }
  }
  if (bad) return <AuthFrame><div className="authCenter"><h1>Liên kết không dùng được</h1><p className="authLead">Liên kết đã hết hạn, đã được dùng hoặc không đúng. Hãy nhờ quản trị viên tạo liên kết mới.</p><Link className="btn" href="/login">Về trang đăng nhập</Link></div></AuthFrame>;
  if (done) return <AuthFrame><div className="authCenter"><h1>Đã đặt mật khẩu</h1><p className="authLead">Bây giờ bạn có thể đăng nhập bằng tên đăng nhập <b>{info?.username}</b>.</p><button className="btn primary" onClick={() => router.replace("/login")}>Đăng nhập</button></div></AuthFrame>;
  if (!info) return <AuthFrame><div className="authCenter" role="status"><div className="spinner"/></div></AuthFrame>;
  return (
    <AuthFrame>
      <h1>{info.purpose === "RESET" ? "Đặt lại mật khẩu" : `Chào ${info.displayName}`}</h1>
      <p className="authLead">{info.purpose === "RESET" ? "Chọn mật khẩu mới cho" : "Chọn mật khẩu để kích hoạt tài khoản"} <b>{info.username}</b>.</p>
      <form className="authForm" onSubmit={(e) => void submit(e)}>
        <Field label="Mật khẩu"><input type="password" autoComplete="new-password" minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} required/></Field>
        <Field label="Nhập lại mật khẩu"><input type="password" autoComplete="new-password" value={again} onChange={(e) => setAgain(e.target.value)} required/></Field>
        <p className="hint">Tối thiểu 8 ký tự, gồm cả chữ và số.</p>
        {error ? <p className="formError" role="alert">{error}</p> : null}
        <button className="btn primary block" disabled={busy || !password}>{busy ? "Đang xử lý…" : "Lưu mật khẩu"}</button>
      </form>
    </AuthFrame>
  );
}

/** `fixedPortal` = the login of one dedicated web app (no portal picker); without it, the combined legacy login with the picker. */
export function LoginPage({ fixedPortal }: { fixedPortal?: PortalId } = {}) {
  const router = useRouter(); const params = useSearchParams(); const { setMe } = useSession();
  const next = safeNext(params.get("next"));
  const [portal, setPortal] = useState<Portal>(() => rememberedPortal() ?? (portalOfPath(next) === "admin" ? "admin" : "builder"));
  const [config, setConfig] = useState<AuthConfig | null>(null);
  const [mode, setMode] = useState<"login" | "signup">("login");
  const [username, setUsername] = useState(""); const [password, setPassword] = useState(""); const [displayName, setDisplayName] = useState(""); const [invite, setInvite] = useState("");
  const [busy, setBusy] = useState(false);
  const ssoError = params.get("sso_error");
  const [error, setError] = useState<string | null>(ssoError ? SSO_ERRORS[ssoError] ?? "Đăng nhập SSO thất bại." : null);
  // M-093 (a): a failed /auth/config is an error with a retry, never a guessed "password login only" form (fail closed: the sign-in methods are unknown until the server says)
  const [configError, setConfigError] = useState<unknown>(null);
  const loadConfig = useCallback(() => { setConfigError(null); api.authConfig().then(setConfig).catch((e: unknown) => { setConfig(null); setConfigError(e); }); }, []);
  useEffect(loadConfig, [loadConfig]);

  const choose = (p: Portal) => { setPortal(p); rememberPortal(p); };
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError(null); rememberPortal(portal);
    try {
      const user = mode === "signup" ? username.trim().toLowerCase() : username.trim();
      if (mode === "signup") await api.register(user, password, displayName.trim(), invite.trim());
      const me = await api.login(user, password);
      setMe(me);
      router.replace(fixedPortal ? resolvePortalPostLogin({ me, portal: fixedPortal, next }) : resolvePostLogin({ me, portal, next }));
    } catch (err) {
      if (err instanceof ApiError && err.code === "ACCOUNT_DISABLED") router.replace("/auth/no-access?reason=disabled");
      else setError(err instanceof ApiError && err.status === 401 ? "Sai tên đăng nhập hoặc mật khẩu." : errText(err, "Đăng nhập thất bại."));
      setPassword("");
    } finally { setBusy(false); }
  }

  return (
    <AuthFrame wide>
      <h1>{mode === "signup" ? "Tạo tài khoản" : "Chào mừng trở lại"}</h1>
      <p className="authLead">{mode === "signup" ? "Tài khoản mới có workspace riêng." : fixedPortal ? `Đăng nhập vào ${PORTAL_LABEL[fixedPortal]} bằng tài khoản công ty` : "Đăng nhập bằng tài khoản công ty"}</p>
      {fixedPortal ? null : <fieldset className="portalPick">
        <legend>Bạn muốn vào</legend>
        {([["admin", "Quản trị", "Admin Console", "Quản lý người dùng, ứng dụng, AI, bảo mật và chính sách."],
           ["builder", "Nhân viên", "Builder Studio", "Tạo và quản lý web/app bằng AI và tài nguyên chung của công ty."]] as const).map(([value, kicker, title, desc]) => (
          <label key={value} className={`portalCard${portal === value ? " selected" : ""}`}>
            <input type="radio" name="portal" value={value} checked={portal === value} onChange={() => choose(value)}/>
            <span className="portalKicker">{kicker}</span><b>{title}</b><span className="portalDesc">{desc}</span>
          </label>
        ))}
        <p className="hint">Lựa chọn này chỉ là nơi bạn muốn đến; quyền truy cập do hệ thống quyết định.</p>
      </fieldset>}
      {configError ? <ErrorState error={configError} title="Chưa tải được cách đăng nhập" retry={loadConfig}/> : null}
      {!config && !configError ? <div className="authCenter" role="status" aria-label="Đang tải"><div className="spinner"/></div> : null}
      {config?.needsSetup ? <p className="notice" role="status">Chưa có tài khoản quản trị. Vui lòng liên hệ người vận hành hệ thống để khởi tạo.</p> : null}
      {config?.oidc ? <a className="btn primary block" href={config.oidcLoginUrl} onClick={() => rememberPortal(portal)}>Tiếp tục với SSO công ty</a> : null}
      {config?.saml && config.samlLoginUrl ? <a className="btn block" href={config.samlLoginUrl} onClick={() => rememberPortal(portal)}>{config.samlLabel || "Đăng nhập SAML của công ty"}</a> : null}
      {config?.oidc ? <p className="hint center">Xác thực nhiều lớp (MFA) do nhà cung cấp danh tính của công ty quản lý.</p> : null}
      {config?.oidc && config.localLogin ? <div className="divider"><span>hoặc</span></div> : null}
      {config && config.localLogin !== false ? (
        <form className="authForm" onSubmit={(e) => void submit(e)}>
          <Field label="Tên đăng nhập"><input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required/></Field>
          {mode === "signup" ? <Field label="Tên hiển thị"><input autoComplete="name" maxLength={80} value={displayName} onChange={(e) => setDisplayName(e.target.value)}/></Field> : null}
          <Field label="Mật khẩu"><input type="password" autoComplete={mode === "signup" ? "new-password" : "current-password"} minLength={mode === "signup" ? 6 : undefined} value={password} onChange={(e) => setPassword(e.target.value)} required/></Field>
          {mode === "signup" ? <p className="hint">Tên đăng nhập 3–40 ký tự (a–z, 0–9, . _ -). Mật khẩu tối thiểu 6 ký tự, gồm chữ và số.</p> : null}
          {mode === "signup" && config?.signupInviteRequired ? <Field label="Mã mời"><input value={invite} onChange={(e) => setInvite(e.target.value)} required/></Field> : null}
          {error ? <p className="formError" role="alert">{error}</p> : null}
          <button className="btn primary block" disabled={busy || !username || !password}>{busy ? "Đang xử lý…" : mode === "signup" ? "Tạo tài khoản" : "Đăng nhập"}</button>
          {config?.signup ? <button type="button" className="btn ghost block" onClick={() => { setMode(mode === "login" ? "signup" : "login"); setError(null); }}>{mode === "login" ? "Chưa có tài khoản? Đăng ký" : "Đã có tài khoản? Đăng nhập"}</button> : null}
        </form>
      ) : (error ? <p className="formError" role="alert">{error}</p> : null)}
    </AuthFrame>
  );
}

/** Landing after sign-in (password or SSO): decides the destination once the session is known. */
export function SigningIn({ fixedPortal }: { fixedPortal?: PortalId } = {}) {
  const router = useRouter(); const params = useSearchParams(); const { me, loading, disabled } = useSession();
  useEffect(() => { if (!loading) router.replace(fixedPortal ? resolvePortalPostLogin({ me, disabled, portal: fixedPortal, next: params.get("next") }) : resolvePostLogin({ me, disabled, portal: rememberedPortal(), next: params.get("next") })); }, [loading, me, disabled, router, params, fixedPortal]);
  return <AuthFrame><div className="authCenter" role="status"><div className="spinner"/><p>Đang đăng nhập…</p></div></AuthFrame>;
}

export function NoAccess() {
  const params = useSearchParams(); const { me, logout } = useSession();
  const disabled = params.get("reason") === "disabled";
  const asked = params.get("portal"); const wanted: PortalId | null = asked === "platform" || asked === "admin" || asked === "studio" ? asked : null;
  const usable = disabled ? [] : accessiblePortals(me).filter((p) => p !== wanted);
  return (
    <AuthFrame>
      <div className="authCenter">
        <div className="stateIcon" aria-hidden="true"><Ban size={20}/></div>
        <h1>{disabled ? "Tài khoản đã bị vô hiệu hóa" : "Không có quyền truy cập"}</h1>
        <p className="authLead">{disabled ? "Liên hệ quản trị viên nếu bạn cho rằng đây là nhầm lẫn." : wanted ? `Bạn không có quyền truy cập ${PORTAL_LABEL[wanted]}.` : `Bạn không có quyền truy cập ${PORTAL_LABEL.admin}.`}</p>
        <div className="row">
          {wanted ? usable.map((p) => <a key={p} className="btn primary" href={portalHref(p)}>Vào {PORTAL_LABEL[p]}</a>) : null}
          {!wanted && !disabled && me && me.workspaces.length > 0 ? <Link className="btn primary" href={portalHref("studio")} onClick={() => rememberPortal("builder")}>Vào {PORTAL_LABEL.studio}</Link> : null}
          {me ? <button className="btn" onClick={() => void logout()}>Đăng xuất</button> : <Link className="btn" href="/login">Về trang đăng nhập</Link>}
        </div>
      </div>
    </AuthFrame>
  );
}

export function NoWorkspace() {
  const { me, logout } = useSession();
  return (
    <AuthFrame>
      <div className="authCenter">
        <div className="stateIcon" aria-hidden="true"><Inbox size={20}/></div>
        <h1>Bạn chưa thuộc workspace nào</h1>
        <p className="authLead">Nhờ quản trị viên thêm bạn vào một workspace để bắt đầu tạo ứng dụng.</p>
        <div className="row">
          {canAccessPortal(me, "admin") ? <Link className="btn primary" href={portalHref("admin")} onClick={() => rememberPortal("admin")}>Vào {PORTAL_LABEL.admin}</Link> : null}
          <button className="btn" onClick={() => void logout()}>Đăng xuất</button>
        </div>
      </div>
    </AuthFrame>
  );
}

export function SessionExpired() {
  const params = useSearchParams(); const next = safeNext(params.get("next"));
  return (
    <AuthFrame>
      <div className="authCenter">
        <div className="stateIcon" aria-hidden="true"><Clock size={20}/></div>
        <h1>Phiên đăng nhập đã hết hạn</h1>
        <p className="authLead">Đăng nhập lại để quay về đúng trang bạn đang làm việc.</p>
        <Link className="btn primary" href={`/login${next ? `?next=${encodeURIComponent(next)}` : ""}`}>Đăng nhập lại</Link>
      </div>
    </AuthFrame>
  );
}
