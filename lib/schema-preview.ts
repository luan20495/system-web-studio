import { escapeHtml as e, previewStyles } from "./preview-document";
import type { PageSchema, Section } from "./http-types";

const str = (v: unknown) => (typeof v === "string" ? v : "");
const arr = (v: unknown): Record<string, unknown>[] => (Array.isArray(v) ? v.filter((x): x is Record<string, unknown> => typeof x === "object" && x !== null) : []);
// Only same-page anchors are rendered as links; the frame is also sandboxed with no scripts.
const href = (v: unknown) => (typeof v === "string" && /^#[\w-]*$/.test(v) ? v : "#");

function render(s: Section): string {
  const p = s.props;
  switch (s.type) {
    case "Navbar":
      return `<nav class="nav"><a class="logo" href="#top">${e(str(p.brand))}</a><div class="links">${arr(p.links).map((l) => `<a href="${href(l.href)}">${e(str(l.label))}</a>`).join("")}</div></nav>`;
    case "Hero":
      return `<section class="hero" id="top"><div class="hero-copy"><p class="eyebrow">${e(str(p.eyebrow))}</p><h1>${e(str(p.title))}</h1><p>${e(str(p.description))}</p>${p.ctaLabel ? `<a class="cta" href="#products">${e(str(p.ctaLabel))}</a>` : ""}</div><div class="visual" aria-hidden="true"><div class="machine"></div></div></section>`;
    case "ProductGrid":
      return `<section class="section" id="products"><h2>${e(str(p.heading))}</h2><div class="product-grid">${arr(p.items).map((i) => `<article class="product-card"><div class="product-art" aria-hidden="true"><span></span></div><div class="product-copy"><h3>${e(str(i.name))}</h3><p>${e(str(i.description))}</p></div></article>`).join("")}</div></section>`;
    case "TechnologySection":
      return `<section class="section" id="technology"><h2>${e(str(p.heading))}</h2><p>${e(str(p.body))}</p></section>`;
    case "ComparisonBlock": {
      const cols = Array.isArray(p.columns) ? p.columns.map(String) : [];
      return `<section class="section comparison" id="comparison"><h2>${e(str(p.heading))}</h2><div class="cmp"><table><thead><tr><th></th>${cols.map((c) => `<th>${e(c)}</th>`).join("")}</tr></thead><tbody>${arr(p.rows).map((r) => `<tr><th>${e(str(r.label))}</th>${(Array.isArray(r.values) ? r.values : []).map((v) => `<td>${e(String(v))}</td>`).join("")}</tr>`).join("")}</tbody></table></div></section>`;
    }
    case "Testimonials":
      if (p.visible === false) return "";
      return `<section class="section testimonials" id="testimonials"><h2>${e(str(p.heading))}</h2><div class="testimonial-grid">${arr(p.items).map((i) => `<article><div class="stars">${"★".repeat(Math.max(0, Math.min(5, Number(i.rating) || 0)))}</div><p>“${e(str(i.quote))}”</p><strong>${e(str(i.author))}${i.location ? ` · ${e(str(i.location))}` : ""}</strong></article>`).join("")}</div></section>`;
    case "ContactForm":
      return `<section class="section contact" id="contact"><div><h2>${e(str(p.heading))}</h2></div><div class="form-note">Form liên hệ chưa được kết nối. Không có thông tin nào được gửi từ bản xem trước.</div></section>`;
    case "Footer":
      return `<footer class="footer"><strong>${e(str(p.text))}</strong><span>Website preview</span></footer>`;
    default:
      return "";
  }
}

export function renderSchemaDocument(schema: PageSchema): string {
  const hero = schema.sections.find((s) => s.type === "Hero");
  return `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${e(str(hero?.props.title) || "Preview")}</title><style>${previewStyles}.cmp{overflow-x:auto}.cmp table{width:100%;border-collapse:collapse;background:#fff}.cmp th,.cmp td{padding:12px;border:1px solid #dfe9ec;text-align:left;font-size:14px}</style></head><body><main class="site">${schema.sections.map(render).join("")}</main></body></html>`;
}
