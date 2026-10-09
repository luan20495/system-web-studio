/**
 * M-091: browser storage the Studio keeps per person (last workspace, chosen AI model, last mode per project). It must not outlive the
 * person: it is cleared on "Đăng xuất" and, whatever way the session ended (401, expiry, another tab), when a DIFFERENT user opens Studio
 * in this browser. Pure over injected Storage objects so it is unit-testable; every access is guarded (private mode / blocked storage).
 */
export const STUDIO_LOCAL_KEYS = ["studio-ws", "studio-ai-model"] as const;
export const STUDIO_SESSION_PREFIX = "ws-mode-";
export const STUDIO_OWNER_KEY = "studio-owner";

type Store = Pick<Storage, "getItem" | "setItem" | "removeItem" | "key" | "length">;
const safe = (f: () => void) => { try { f(); } catch { /* storage unavailable: nothing to clear */ } };
const browser = (): { local?: Store; session?: Store } => { try { return { local: window.localStorage, session: window.sessionStorage }; } catch { return {}; } };

export function clearStudioStorage(local: Store | undefined = browser().local, session: Store | undefined = browser().session): void {
  safe(() => { for (const k of [...STUDIO_LOCAL_KEYS, STUDIO_OWNER_KEY]) local?.removeItem(k); });
  safe(() => {
    if (!session) return;
    const keys: string[] = [];
    for (let i = 0; i < session.length; i++) { const k = session.key(i); if (k?.startsWith(STUDIO_SESSION_PREFIX)) keys.push(k); }
    keys.forEach((k) => session.removeItem(k));
  });
}

/** call before reading any Studio key: storage written by another user of this browser is dropped first */
export function claimStudioStorage(userId: string, local: Store | undefined = browser().local, session: Store | undefined = browser().session): void {
  let owner: string | null = null;
  safe(() => { owner = local?.getItem(STUDIO_OWNER_KEY) ?? null; });
  if (owner === userId) return;
  clearStudioStorage(local, session);
  safe(() => local?.setItem(STUDIO_OWNER_KEY, userId));
}
