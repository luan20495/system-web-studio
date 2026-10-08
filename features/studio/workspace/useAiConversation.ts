"use client";
/**
 * The AI conversation of the project workspace (extracted from `ProjectWorkspace`, M-048; behaviour unchanged): the model choice, the messages, a streamed
 * answer in progress (`live`, cancel), sending a prompt (streaming for real models, one call for the simulator), the prompt handed over from Studio Home, and
 * the scroll follow of the chat (M-004).
 */
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { api } from "@/lib/http-api";
import type { AiStatus, ApiProject, PageSchema } from "@/lib/http-types";
import type { AiLive } from "../aiProgressModel";
import { usageChip, type Msg } from "./runFailure";

type Run = <T>(label: string, fn: () => Promise<T>, fallback: string) => Promise<T | undefined>;

export function useAiConversation(o: {
  ws: string; projectId: string; project: ApiProject | null; schema: PageSchema | null; revision: number; readOnly: boolean; busy: string | null; mode: string;
  run: Run; setSchema: (s: PageSchema) => void; setRevision: (r: number) => void; refreshVersions: () => Promise<void>; label: (type: string) => string;
  /** the prompt of `?prompt=` and how to drop it from the URL */
  handOver: { prompt: string | null; clear: () => void };
}) {
  const { ws, projectId, project, schema, revision, readOnly, busy, mode, run, setSchema, setRevision, refreshVersions, label, handOver } = o;
  const [ai, setAi] = useState<AiStatus | null>(null);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [prompt, setPrompt] = useState("");
  /** a streamed AI answer in progress: stream id (for cancel), raw output so far, last status */
  const [live, setLive] = useState<AiLive | null>(null);
  /** aborts the request while the server has not yet answered `start` (a stream id exists only after that) */
  const streamAbort = useRef<AbortController | null>(null);
  const [model, setModel] = useState<string>(() => { try { return localStorage.getItem("studio-ai-model") ?? ""; } catch { return ""; } });
  const promptRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => { try { localStorage.setItem("studio-ai-model", model); } catch { /* ignore */ } }, [model]);
  const valid = (m: string) => m === "auto" || m === "mock" || (ai?.models ?? []).some((x) => x.id === m);
  const effectiveModel = ai?.configured ? (model && valid(model) ? model : valid(ai.defaultModel) ? ai.defaultModel : "auto") : "mock";

  // M-004: the conversation follows the newest message. Your own message always scrolls; a reply (or the progress block) only follows while you are at the bottom,
  // otherwise a "Tin mới" button offers the jump, so reading older messages is never interrupted.
  const convRef = useRef<HTMLDivElement>(null); const stick = useRef(true); const [behind, setBehind] = useState(false);
  const onConversationScroll = () => { const el = convRef.current; if (!el) return; stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80; if (stick.current) setBehind(false); };
  const scrollToNewest = (behavior: ScrollBehavior = "instant") => { const el = convRef.current; if (el) el.scrollTo({ top: el.scrollHeight, behavior }); };
  const jumpToNewest = () => { stick.current = true; setBehind(false); scrollToNewest("smooth"); };
  const working = busy === "prompt";
  useLayoutEffect(() => {
    if (stick.current || messages[messages.length - 1]?.role === "user") { stick.current = true; setBehind(false); scrollToNewest(); } else setBehind(true);
  }, [messages.length, working, !!live, mode]); // eslint-disable-line react-hooks/exhaustive-deps

  async function submitPrompt(text = prompt.trim()) {
    if (!text || busy || readOnly || !project) return;
    setMessages((m) => [...m, { id: `local-${Date.now()}`, role: "user", content: text }]); setPrompt("");
    const real = ai?.configured === true && effectiveModel !== "mock";
    // real models stream (partial output, cancel, deadline); the simulator answers at once
    const r = await run("prompt", () => {
      if (!real) return api.sendPrompt(ws, projectId, text, revision, ai?.configured ? effectiveModel : undefined);
      const ctl = new AbortController(); streamAbort.current = ctl;
      const t0 = Date.now(); setLive({ id: null, text: "", status: "", startedAt: t0, lastAt: t0, deadline: null });
      const touch = (f: (l: AiLive) => AiLive) => setLive((l) => (l ? f({ ...l, lastAt: Date.now() }) : l));
      return api.streamPrompt(ws, projectId, text, revision, effectiveModel, {
          onStart: (id, deadline) => touch((l) => ({ ...l, id, deadline: deadline ?? null })),
          onDelta: (t) => touch((l) => ({ ...l, text: l.text + t })),
          onStatus: (st) => touch((l) => ({ ...l, status: st })) }, ctl.signal).finally(() => { streamAbort.current = null; setLive(null); });
    }, "Không thể cập nhật website.");
    if (!r) return;
    setSchema(r.pageSchema); setRevision(r.revision);
    const changed = Array.from(new Set(r.schemaPatch.map((op) => op.sectionType ?? r.pageSchema.sections.find((s) => s.id === op.sectionId)?.type ?? schema?.sections.find((s) => s.id === op.sectionId)?.type).filter(Boolean) as string[]));
    const used = Array.from(new Set(r.pageSchema.sections.map((s) => s.type)));
    const reuse = r.reuseSources;
    const meta = [r.outcome, r.model && r.model !== "mock" ? r.model : "Chế độ thử nghiệm", ...(r.version ? [`Phiên bản ${r.version.versionNumber}`] : []),
      usageChip(r.usage?.attempts ?? 0, r.usage?.totalTokens ?? null, r.usage?.costUsd ?? null),
      ...(reuse && (reuse.blocks.length || reuse.templates.length) ? [`Tái sử dụng: ${reuse.blocks.length} khối, ${reuse.templates.length} template`] : [])];
    const detail = r.outcome === "UPDATED" ? `Đã đổi: ${changed.map(label).join(", ") || "—"} · Component đang dùng: ${used.map(label).join(", ")}` : undefined;
    setMessages((m) => [...m, { id: r.promptId, role: "assistant", content: r.message.content, meta, detail }]);
    if (r.version) void refreshVersions();
  }
  /** cancel the running request: by stream id once the server answered `start`, by aborting the fetch before that */
  const cancel = () => { if (live?.id) void api.cancelStream(live.id).catch(() => undefined); else streamAbort.current?.abort(); };

  // a prompt handed over from Studio Home is sent once, then removed from the URL
  const handedOver = useRef(false);
  useEffect(() => {
    const p = handOver.prompt;
    if (p && project && schema && !handedOver.current) { handedOver.current = true; handOver.clear(); void submitPrompt(p); }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [handOver.prompt, project, schema]);

  return { ai, setAi, messages, setMessages, prompt, setPrompt, live, model, setModel, effectiveModel, promptRef, submitPrompt, cancel, convRef, behind, onConversationScroll, jumpToNewest };
}
