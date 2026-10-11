"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { api, ApiError, onUnauthorized } from "@xweb/api-client";
import type { Me } from "@xweb/types";

type SessionState = { me: Me | null; loading: boolean; disabled: boolean; refresh: () => Promise<Me | null>; logout: () => Promise<void>; setMe: (m: Me | null) => void };
const SessionContext = createContext<SessionState | null>(null);

/**
 * ONE session per web app. It is mounted in the app's ROOT LAYOUT, which survives navigation: under the `[[...slug]]` route Next gives every path its own page key, so a provider mounted
 * inside the page was REMOUNTED by every navigation (login -> portal, every sidebar link) and asked `/auth/me` again behind a full-screen splash (FQ-PERF-01).
 * A provider that finds an outer one renders its children unchanged (PortalApp, the harnesses and the legacy app keep working).
 *
 * Authorization freshness is NOT cached longer than before: `/auth/me` is still read again on every navigation that changes the path while a session is held (revoked permission, disabled
 * user, suspended tenant, changed memberships become effective at the next navigation), but now in the BACKGROUND (the screen is not replaced by a splash) and single-flight (one request
 * serves every caller in the same moment, so a double effect or a login that has just read it never sends a second one). A 401 clears the session; ACCOUNT_DISABLED blocks; a transient
 * failure of a background re-read keeps what the server last said (the server still authorises every call).
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const outer = useContext(SessionContext);
  return outer ? <>{children}</> : <OwnSession>{children}</OwnSession>;
}

function OwnSession({ children }: { children: ReactNode }) {
  const [me, setMeState] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const [disabled, setDisabled] = useState(false);
  const router = useRouter();
  const pathname = usePathname();
  const meRef = useRef<Me | null>(null);
  const inflight = useRef<Promise<Me | null> | null>(null);
  const skipNavUntil = useRef(0);
  const generation = useRef(0);   // bumped whenever the session is replaced / cleared: an answer to a read that started before that is dropped (a late 200 must not bring a signed-out person back)
  const adopt = useCallback((m: Me | null) => { generation.current++; meRef.current = m; setMeState(m); }, []);

  const refresh = useCallback((): Promise<Me | null> => {
    if (inflight.current) return inflight.current;
    const held = meRef.current !== null;   // a re-read while a session is held is a background revalidation
    const gen = generation.current;
    const p = (async () => {
      try { const m = await api.me(); if (gen !== generation.current) return meRef.current; adopt(m); setDisabled(false); return m; }
      catch (e) {
        if (gen !== generation.current) return meRef.current;
        if (e instanceof ApiError && e.code === "ACCOUNT_DISABLED") { setDisabled(true); adopt(null); return null; }
        if (held && !(e instanceof ApiError && e.status === 401)) return meRef.current;   // transient (network / 5xx): keep the last answer, do not sign the person out
        adopt(null); return null;
      } finally { setLoading(false); inflight.current = null; }
    })();
    inflight.current = p; return p;
  }, [adopt]);
  useEffect(() => { void refresh(); }, [refresh]);

  // a path change while a session is held re-reads /auth/me (explicit revalidation, in the background); the first path change after a sign-in that just read it is not a reason to read it again
  const firstPath = useRef(true);
  useEffect(() => {
    if (firstPath.current) { firstPath.current = false; return; }
    if (Date.now() < skipNavUntil.current) { skipNavUntil.current = 0; return; }
    if (meRef.current) void refresh();
  }, [pathname, refresh]);
  // the page that just signed in (api.login read /auth/me) hands the answer over: no second read for the navigation it triggers
  const setMe = useCallback((m: Me | null) => { if (m) skipNavUntil.current = Date.now() + 5000; adopt(m); }, [adopt]);

  // Any API call that comes back 401 while the user was inside the app: keep where they were and ask them to sign in again.
  useEffect(() => {
    onUnauthorized((code) => {
      adopt(null);
      if (code === "ACCOUNT_DISABLED") { setDisabled(true); router.replace("/auth/no-access?reason=disabled"); return; }
      if (!window.location.pathname.startsWith("/auth") && window.location.pathname !== "/login")
        router.replace(`/auth/session-expired?next=${encodeURIComponent(window.location.pathname + window.location.search)}`);
    });
    return () => onUnauthorized(null);
  }, [router, pathname, adopt]);

  // SSO users are also signed out at the identity provider (RP-initiated logout), which then sends them back to /login
  const logout = useCallback(async () => {
    const idp = await api.logout().catch(() => null); adopt(null);
    if (idp) window.location.assign(idp); else router.replace("/login");
  }, [router, adopt]);
  const value = useMemo(() => ({ me, loading, disabled, refresh, logout, setMe }), [me, loading, disabled, refresh, logout, setMe]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  const s = useContext(SessionContext);
  if (!s) throw new Error("useSession outside SessionProvider");
  return s;
}
