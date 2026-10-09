import { escapeHtml as e, previewStyles } from "./preview-document";
import type { NavLink, PageSchema, Section, Seo } from "./http-types";

const str = (v: unknown) => (typeof v === "string" ? v : "");
const arr = (v: unknown): Record<string, unknown>[] => (Array.isArray(v) ? v.filter((x): x is Record<string, unknown> => typeof x === "object" && x !== null) : []);
// Only same-page anchors are rendered as links; the frame is also sandboxed with no scripts.
const href = (v: unknown) => (typeof v === "string" && /^#[\w-]*$/.test(v) ? v : "#");

export type RenderOptions = {
  selectedId?: string | null;
  /** Adds the click-to-select script (design mode). Needs the page's CSP nonce because srcdoc inherits the parent policy. */
  interactive?: boolean; nonce?: string;
  /** M-089: the editor's origin; the click-to-select script posts to this origin only (default "*": the receiver already checks the source window) */
  parentOrigin?: string;
  /** asset id -> short-lived signed URL (preview) or "assets/<id>.<ext>" (published artifact). Unknown references render without an image. */
  assets?: Record<string, string>;
  /** which page of a multi-page site to render (default: home) */
  pageId?: string;
  /** published build: contact forms post to the site's form endpoint; otherwise (preview) they are shown inert */
  published?: boolean;
  /** published build only: the data bindings of the page (resolved by the render worker); they annotate the markup for the client runtime */
  bindings?: RuntimeBinding[];
};
/** One bound prop: [query] is the LOCAL id of the query the page calls; [kind] "text" = one element, "list" = a container with a row template. */
export type RuntimeBinding = { id: string; sectionId: string; prop: string; query: string; kind: "text" | "list" };
let ctx: RenderOptions = {};
/** M-089: targetOrigin of the editor script's postMessage: the editor origin when it is a plain http(s) origin (strict pattern, so nothing can break out of the script), else "*" */
const postTarget = () => { const o = ctx.parentOrigin; return o && /^https?:\/\/[A-Za-z0-9.-]+(:\d{1,5})?$/.test(o) ? JSON.stringify(o) : '"*"'; };
// Data binding annotations (published builds with bindings only; every helper is "" otherwise, so the markup is unchanged).
const bindingFor = (sectionId: string, prop: string, kind: "text" | "list") => ctx.published ? ctx.bindings?.find((b) => b.sectionId === sectionId && b.prop === prop && b.kind === kind) : undefined;
const textBind = (s: Section, prop: string) => { const b = bindingFor(s.id, prop, "text"); return b ? ` data-xw-bind="${e(b.id)}" data-xw-q="${e(b.query)}" data-xw-prop="${e(prop)}"` : ""; };
const listBind = (s: Section, prop: string) => { const b = bindingFor(s.id, prop, "list"); return b ? ` data-xw-list="${e(b.id)}" data-xw-q="${e(b.query)}"` : ""; };
const rowTemplate = (s: Section, prop: string, row: string) => { const b = bindingFor(s.id, prop, "list"); return b ? `<template data-xw-row="${e(b.id)}">${row}</template>` : ""; };
/** relative path from the page being rendered to the site root ("./" on home, "../" on /<slug>/) */
let up = "./";
let site: PageSchema | null = null;
const img = (v: unknown): string | null => {
  if (typeof v !== "string" || !v.startsWith("asset://")) return null;
  const url = ctx.assets?.[v.slice("asset://".length)];
  // signed http(s) URLs in the preview; relative files inside a published artifact (ADR 0009)
  return url && (/^https?:\/\//.test(url) || /^assets\/[0-9a-f-]{36}\.[a-z0-9]{2,5}$/.test(url)) ? url : null;
};

/** A navigation link of the site: another page (relative URL), an anchor, or an https URL on an approved host (checked by the API). */
function navLink(l: NavLink): string {
  if (l.pageId) {
    const target = l.pageId === "home" ? "" : site?.pages?.find((x) => x.id === l.pageId)?.slug;
    if (target === undefined) return "";
    return `<a href="${e(target ? `${up}${target}/` : up)}">${e(l.label)}</a>`;
  }
  if (l.anchor && /^#[a-z0-9][a-z0-9-]{0,63}$/.test(l.anchor)) return `<a href="${e(l.anchor)}">${e(l.label)}</a>`;
  if (l.url && /^https:\/\/[^\s"'<>\\]+$/.test(l.url)) return `<a href="${e(l.url)}" rel="noopener noreferrer external" target="_blank">${e(l.label)}</a>`;
  return "";
}

function render(s: Section): string {
  const p = s.props;
  switch (s.type) {
    case "Navbar": {
      const nav = site?.site?.navigation;
      const links = nav && nav.length ? nav.map(navLink).join("") : arr(p.links).map((l) => `<a href="${href(l.href)}">${e(str(l.label))}</a>`).join("");
      return `<nav class="nav"><a class="logo" href="${nav && nav.length ? e(up) : "#top"}"${textBind(s, "brand")}>${e(str(p.brand))}</a><div class="links">${links}</div></nav>`;
    }
    case "Hero":
      return `<section class="hero" id="top"><div class="hero-copy"><p class="eyebrow"${textBind(s, "eyebrow")}>${e(str(p.eyebrow))}</p><h1${textBind(s, "title")}>${e(str(p.title))}</h1><p${textBind(s, "description")}>${e(str(p.description))}</p>${p.ctaLabel ? `<a class="cta" href="#products">${e(str(p.ctaLabel))}</a><a class="cta ghost" href="#contact">Nhận tư vấn</a>` : ""}</div>${img(p.image) ? `<div class="visual photo"><img src="${e(img(p.image)!)}" alt=""></div>` : `<div class="visual" aria-hidden="true"><div class="machine"></div></div>`}</section>`;
    case "ProductGrid":
      return `<section class="section" id="products"><h2${textBind(s, "heading")}>${e(str(p.heading))}</h2><div class="product-grid"${listBind(s, "items")}>${arr(p.items).map((i) => `<article class="product-card">${img(i.image) ? `<div class="product-art photo"><img src="${e(img(i.image)!)}" alt=""></div>` : `<div class="product-art" aria-hidden="true"><span></span></div>`}<div class="product-copy"><h3>${e(str(i.name))}</h3><p>${e(str(i.description))}</p></div></article>`).join("")}</div>${rowTemplate(s, "items", `<article class="product-card"><div class="product-art" aria-hidden="true"><span></span></div><div class="product-copy"><h3 data-xw-field="name"></h3><p data-xw-field="description"></p></div></article>`)}</section>`;
    case "TechnologySection":
      return `<section class="section" id="technology"><h2${textBind(s, "heading")}>${e(str(p.heading))}</h2><p${textBind(s, "body")}>${e(str(p.body))}</p></section>`;
    case "ComparisonBlock": {
      const cols = Array.isArray(p.columns) ? p.columns.map(String) : [];
      return `<section class="section comparison" id="comparison"><h2${textBind(s, "heading")}>${e(str(p.heading))}</h2><div class="cmp"><table><thead><tr><th></th>${cols.map((c) => `<th>${e(c)}</th>`).join("")}</tr></thead><tbody>${arr(p.rows).map((r) => `<tr><th>${e(str(r.label))}</th>${(Array.isArray(r.values) ? r.values : []).map((v) => `<td>${e(String(v))}</td>`).join("")}</tr>`).join("")}</tbody></table></div></section>`;
    }
    case "Testimonials":
      if (p.visible === false) return "";
      return `<section class="section testimonials" id="testimonials"><h2${textBind(s, "heading")}>${e(str(p.heading))}</h2><div class="testimonial-grid"${listBind(s, "items")}>${arr(p.items).map((i) => `<article><div class="stars">${"★".repeat(Math.max(0, Math.min(5, Number(i.rating) || 0)))}</div><p>“${e(str(i.quote))}”</p><strong>${e(str(i.author))}${i.location ? ` · ${e(str(i.location))}` : ""}</strong></article>`).join("")}</div>${rowTemplate(s, "items", `<article><div class="stars" data-xw-field="rating" data-xw-kind="stars"></div><p>“<span data-xw-field="quote"></span>”</p><strong><span data-xw-field="author"></span><span data-xw-field="location" data-xw-prefix=" · "></span></strong></article>`)}</section>`;
    case "ContactForm":
      // a plain HTML form (no script): posts to the site's form endpoint, which validates, filters spam and stores the submission
      if (ctx.published) return `<section class="section contact" id="contact"><div><h2${textBind(s, "heading")}>${e(str(p.heading))}</h2></div><form class="lead-form" method="post" action="${e(`${up}_forms/${s.id}`)}">`
        + `<input type="hidden" name="_page" value="${e(ctx.pageId ?? "home")}">`
        + `<label>Họ tên<input name="name" required maxlength="100" autocomplete="name"></label><label>Email<input name="email" type="email" required maxlength="200" autocomplete="email"></label>`
        + `<label>Điện thoại<input name="phone" type="tel" maxlength="30" autocomplete="tel"></label><label class="wide">Nội dung<textarea name="message" required maxlength="2000" rows="4"></textarea></label>`
        + `<label class="hp" aria-hidden="true">Website<input name="website" tabindex="-1" autocomplete="off"></label>`
        + `<button type="submit">${e(str(p.submitLabel) || "Gửi")}</button><p class="form-privacy">Thông tin chỉ dùng để liên hệ lại với bạn.</p></form></section>`;
      return `<section class="section contact" id="contact"><div><h2${textBind(s, "heading")}>${e(str(p.heading))}</h2></div><div class="form-note">Form hoạt động trên trang đã xuất bản: tin gửi được lưu cho thành viên ứng dụng xem. Bản xem trước không gửi gì.</div></section>`;
    case "Footer":
      return `<footer class="footer"><strong${textBind(s, "text")}>${e(str(p.text))}</strong><span>Website preview</span></footer>`;
    default:
      return "";
  }
}

const formStyles = ".lead-form{display:grid;grid-template-columns:1fr 1fr;gap:12px}.lead-form label{display:grid;gap:6px;font-size:13px;color:#46636b}.lead-form .wide{grid-column:1/-1}.lead-form input,.lead-form textarea{font:inherit;padding:11px 12px;border:1px solid #c9d9dd;border-radius:10px;background:#fff}.lead-form button{justify-self:start;min-height:44px;padding:0 22px;border:0;border-radius:999px;background:#0b6f63;color:#fff;font-weight:700}.lead-form .hp{position:absolute;left:-10000px;width:1px;height:1px;overflow:hidden}.form-privacy{grid-column:1/-1;margin:0;font-size:12px;color:#6a8288}.notfound{padding:96px clamp(20px,5vw,72px);text-align:center}.notfound a{color:#0b6f63}@media(max-width:700px){.lead-form{grid-template-columns:1fr}}";

/** the sections, title and SEO of one page of the site */
function pageOf(schema: PageSchema, pageId?: string): { sections: Section[]; title: string; seo: Seo } {
  const hero = (secs: Section[]) => str(secs.find((x) => x.type === "Hero")?.props.title);
  if (pageId && pageId !== "home") {
    const pg = schema.pages?.find((x) => x.id === pageId);
    if (pg) return { sections: pg.sections, title: pg.title || hero(pg.sections), seo: pg.seo ?? {} };
  }
  return { sections: schema.sections, title: schema.site?.home?.title || schema.site?.title || hero(schema.sections), seo: schema.site?.home?.seo ?? {} };
}

function head(title: string, seo: Seo, extra = ""): string {
  const t = seo.title || title || "Website";
  return `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${e(t)}</title>`
    + (seo.description ? `<meta name="description" content="${e(seo.description)}">` : "") + (seo.noindex ? `<meta name="robots" content="noindex">` : "")
    + `<style>${previewStyles}${formStyles}${extra}</style></head>`;
}

export function renderSchemaDocument(schema: PageSchema, selectedOrOptions?: string | null | RenderOptions): string {
  ctx = typeof selectedOrOptions === "object" && selectedOrOptions !== null ? selectedOrOptions : { selectedId: selectedOrOptions ?? null };
  site = schema; up = ctx.pageId && ctx.pageId !== "home" ? "../" : "./";
  const selectedId = ctx.selectedId;
  const pg = pageOf(schema, ctx.pageId);
  const extra = `.__sel{outline:2px solid #2c7cff;outline-offset:-2px;position:relative}.__sel:before{content:"Đang chỉnh sửa";position:absolute;z-index:3;top:8px;left:8px;background:#2c7cff;color:#fff;font:600 11px system-ui;padding:3px 8px;border-radius:999px}.visual.photo,.product-art.photo{background:#eef4f6;overflow:hidden}.visual.photo img,.product-art.photo img{width:100%;height:100%;object-fit:cover;display:block}.visual.photo{padding:0;min-height:300px}${ctx.interactive ? ".__sec{cursor:pointer}.__sec:hover{outline:2px dashed #93c5fd;outline-offset:-2px}" : ""}.cmp{overflow-x:auto}.cmp table{width:100%;border-collapse:collapse;background:#fff}.cmp th,.cmp td{padding:12px;border:1px solid #dfe9ec;text-align:left;font-size:14px}`;
  return `${head(pg.title, pg.seo, extra)}<body><main class="site"${ctx.published && ctx.bindings?.length ? ` data-xw-runtime data-xw-root="${e(up)}"` : ""}>${pg.sections.map((sec) => { const html = render(sec); return html ? `<div class="__sec${sec.id === selectedId ? " __sel" : ""}" data-sid="${e(sec.id)}">${html}</div>` : ""; }).join("")}</main>${ctx.published && ctx.bindings?.length ? `<script src="${e(up)}_runtime/page-runtime.js" defer></script>` : ""}${ctx.interactive ? `<script${ctx.nonce ? ` nonce="${e(ctx.nonce)}"` : ""}>document.addEventListener("click",function(ev){var t=ev.target;var el=t&&t.closest?t.closest("[data-sid]"):null;ev.preventDefault();if(el){parent.postMessage({type:"studio:select",sectionId:el.getAttribute("data-sid")},${postTarget()});}},true);(function(){var t=0;function send(){var out=[];var els=document.querySelectorAll("[data-sid]");for(var i=0;i<els.length;i++){var r=els[i].getBoundingClientRect();out.push({id:els[i].getAttribute("data-sid"),top:r.top,height:r.height});}parent.postMessage({type:"studio:layout",rects:out},${postTarget()});}function sched(){if(t)return;t=requestAnimationFrame(function(){t=0;send();});}addEventListener("scroll",sched,true);addEventListener("resize",sched);addEventListener("load",sched);if(window.ResizeObserver){new ResizeObserver(sched).observe(document.body);}sched();})();addEventListener("message",function(ev){if(ev.source!==parent)return;var d=ev.data;if(!d||d.type!=="studio:selected")return;var all=document.querySelectorAll("[data-sid]");for(var i=0;i<all.length;i++){all[i].classList.toggle("__sel",all[i].getAttribute("data-sid")===d.sectionId);}});</script>` : ""}</body></html>`;
}

/** Token in 404.html that the server replaces with the site's root path when it serves the page (any depth, gateway or custom domain). */
export const SITE_ROOT_TOKEN = "__SITE_ROOT__";

/**
 * Every file of a published multi-page site: index.html (home), <slug>/index.html per page and 404.html. One build of one schema version,
 * so a deployment is an atomic snapshot of all pages.
 */
export function renderSitePages(schema: PageSchema, assets: Record<string, string>, bindings: RuntimeBinding[] = []): Record<string, string> {
  const files: Record<string, string> = {};
  files["index.html"] = renderSchemaDocument(schema, { selectedId: null, interactive: false, assets, published: true, bindings });
  for (const pg of schema.pages ?? []) {
    // asset paths are relative to the artifact root; pages live one level down
    const html = renderSchemaDocument(schema, { selectedId: null, interactive: false, assets, published: true, pageId: pg.id, bindings });
    files[`${pg.slug}/index.html`] = html.replace(/src="assets\//g, 'src="../assets/');
  }
  const nf = schema.site?.notFound;
  site = schema;
  files["404.html"] = `${head(nf?.title || "Không tìm thấy trang", { noindex: true })}<body><main class="site"><section class="notfound"><h1>${e(nf?.title || "Không tìm thấy trang")}</h1>`
    + `<p>${e(nf?.message || "Trang bạn tìm không tồn tại hoặc đã được chuyển đi.")}</p><p><a href="${SITE_ROOT_TOKEN}">Về trang chủ</a></p></section></main></body></html>`;
  return files;
}
