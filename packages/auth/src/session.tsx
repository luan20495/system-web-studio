"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { api, ApiError, onUnauthorized } from "@xweb/api-client";
import type { Me } from "@xweb/types";

type SessionState = { me: Me | null; loading: boolean; disabled: boolean; refresh: () => Promise<Me | null>; logout: () => Promise<void>; setMe: (m: Me | null) => void };
const SessionContext = createContext<SessionState | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const [disabled, setDisabled] = useState(false);
  const router = useRouter();
  const pathname = usePathname();

  const refresh = useCallback(async () => {
    try { const m = await api.me(); setMe(m); setDisabled(false); return m; }
    catch (e) { if (e instanceof ApiError && e.code === "ACCOUNT_DISABLED") setDisabled(true); setMe(null); return null; }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);

  // Any API call that comes back 401 while the user was inside the app: keep where they were and ask them to sign in again.
  useEffect(() => {
    onUnauthorized((code) => {
      setMe(null);
      if (code === "ACCOUNT_DISABLED") { setDisabled(true); router.replace("/auth/no-access?reason=disabled"); return; }
      if (!window.location.pathname.startsWith("/auth") && window.location.pathname !== "/login")
        router.replace(`/auth/session-expired?next=${encodeURIComponent(window.location.pathname + window.location.search)}`);
    });
    return () => onUnauthorized(null);
  }, [router, pathname]);

  // SSO users are also signed out at the identity provider (RP-initiated logout), which then sends them back to /login
  const logout = useCallback(async () => {
    const idp = await api.logout().catch(() => null); setMe(null);
    if (idp) window.location.assign(idp); else router.replace("/login");
  }, [router]);
  const value = useMemo(() => ({ me, loading, disabled, refresh, logout, setMe }), [me, loading, disabled, refresh, logout]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  const s = useContext(SessionContext);
  if (!s) throw new Error("useSession outside SessionProvider");
  return s;
}
