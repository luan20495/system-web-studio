// Render worker (ADR 0009): turns a page schema into the static HTML of a published site with the SAME renderer as the Studio
// preview (lib/schema-preview.ts). Pure function over data; listens on 127.0.0.1 only; the API calls it during the BUILDING step.
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { renderSchemaDocument } from "../../lib/schema-preview";
import type { PageSchema } from "../../lib/http-types";
import { edit as astEdit, tree as astTree } from "./ast";
import { previewAvailable, screenshot } from "./preview";

const PORT = Number(process.env.RENDER_PORT ?? 18095);
const HOST = process.env.RENDER_HOST ?? "127.0.0.1";
const TOKEN = process.env.RENDER_TOKEN ?? "";
const MAX_BODY = 1024 * 1024;

function authorized(req: IncomingMessage): boolean {
  if (!TOKEN) return true;                                   // local development without a token
  const got = Buffer.from(String(req.headers["x-render-token"] ?? ""));
  const want = Buffer.from(TOKEN);
  return got.length === want.length && timingSafeEqual(got, want);
}

function send(res: ServerResponse, status: number, body: string, type = "text/plain; charset=utf-8") {
  res.writeHead(status, { "Content-Type": type, "Cache-Control": "no-store" }); res.end(body);
}

createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") return send(res, 200, "ok");
  if (req.method !== "POST" || !["/render", "/preview", "/ast/tree", "/ast/edit"].includes(req.url ?? "")) return send(res, 404, "not found");
  if (!authorized(req)) return send(res, 401, "unauthorized");
  const chunks: Buffer[] = []; let size = 0;
  req.on("data", (c: Buffer) => { size += c.length; if (size > MAX_BODY) { send(res, 413, "too large"); req.destroy(); } else chunks.push(c); });
  req.on("end", () => {
    if (res.headersSent) return;
    try {
      const raw = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      if (req.url === "/ast/tree") return send(res, 200, JSON.stringify(astTree(String(raw.source ?? ""))), "application/json");
      if (req.url === "/ast/edit") {
        try { return send(res, 200, JSON.stringify({ source: astEdit(String(raw.source ?? ""), raw.edit) }), "application/json"); }
        catch (e) { return send(res, 422, JSON.stringify({ error: (e as Error).message }), "application/json"); }
      }
      const body = raw as { schema?: PageSchema; assets?: Record<string, string> };
      if (req.url === "/preview") {
        if (!previewAvailable()) return send(res, 501, "preview renderer not configured");
        if (!body.schema || !Array.isArray(body.schema.sections)) return send(res, 400, "schema required");
        const html = renderSchemaDocument(body.schema, { selectedId: null, interactive: false, assets: {} });
        if (/<\s*script/i.test(html)) return send(res, 422, "rendered markup contains a script");
        screenshot(html).then((png) => { res.writeHead(200, { "Content-Type": "image/png", "Cache-Control": "no-store" }); res.end(png); })
          .catch(() => send(res, 500, "preview failed"));
        return;
      }
      if (!body.schema || !Array.isArray(body.schema.sections)) return send(res, 400, "schema required");
      const assets: Record<string, string> = {};
      // only relative paths inside the artifact are accepted as image URLs
      for (const [id, path] of Object.entries(body.assets ?? {})) if (/^assets\/[0-9a-f-]{36}\.[a-z0-9]{2,5}$/.test(path)) assets[id] = path;
      send(res, 200, renderSchemaDocument(body.schema, { selectedId: null, interactive: false, assets }), "text/html; charset=utf-8");
    } catch {
      send(res, 400, "invalid request");
    }
  });
}).listen(PORT, HOST, () => console.log(`render worker on http://${HOST}:${PORT}`));
