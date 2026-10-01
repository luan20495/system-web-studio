import type { PageContent } from "./types";

export function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;"
  })[character] ?? character);
}

export const previewStyles = `*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;color:#173b49;background:#fff;font:16px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif}
a{color:inherit}.site{max-width:1440px;margin:0 auto}.nav{height:64px;padding:0 clamp(20px,5vw,72px);display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #e5edf0}.logo{font-weight:800;color:#078675}.links{display:flex;gap:28px;color:#58717a;font-size:14px}.links a{text-decoration:none}.hero{min-height:380px;padding:clamp(32px,6vw,80px);display:grid;grid-template-columns:1.1fr .9fr;gap:32px;align-items:center;background:#e5f5ef}.eyebrow{margin:0 0 10px;color:#078675;font-size:12px;font-weight:800;letter-spacing:.08em;text-transform:uppercase}.hero h1{max-width:650px;margin:0;font-size:clamp(32px,5vw,58px);line-height:1.04}.hero-copy>p:not(.eyebrow){max-width:580px;color:#55727c}.cta{display:inline-flex;min-height:44px;align-items:center;margin-top:12px;padding:0 18px;border-radius:6px;background:#087d70;color:#fff;text-decoration:none;font-weight:700}.visual{min-height:230px;display:grid;place-items:center;border-radius:18px;background:#c6e6dc}.machine{width:92px;height:180px;position:relative;border:1px solid #cbd8dc;border-radius:14px;background:linear-gradient(110deg,#fff,#e8f0f2);box-shadow:0 18px 34px #48726522}.machine:before{content:"";position:absolute;top:22px;left:50%;width:17px;height:17px;transform:translateX(-50%);border-radius:50%;background:#204554}.section{padding:64px clamp(20px,5vw,72px)}.section h2{margin:0 0 24px;font-size:clamp(24px,3vw,34px);line-height:1.2}.product-grid,.comparison-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.product-card,.comparison-grid article,.testimonial-grid article{overflow:hidden;border:1px solid #dfe9ec;border-radius:8px;background:#fff}.product-art{height:160px;display:grid;place-items:center;background:linear-gradient(135deg,#edf5f7,#d5e9e6)}.product-art span{width:64px;height:105px;border-radius:12px;background:linear-gradient(120deg,#fff,#dae7e9);box-shadow:0 10px 22px #315b5b22}.product-copy,.comparison-grid article,.testimonial-grid article{padding:18px}.product-copy h3,.comparison-grid h3{margin:0 0 6px;font-size:17px}.product-copy p,.comparison-grid p{margin:0;color:#5d7780;font-size:14px}.comparison{background:#f6faf9}.testimonial-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.testimonial-grid p{color:#557078}.stars{color:#df9e2f}.testimonial-grid strong{font-size:13px}.contact{display:grid;grid-template-columns:.8fr 1.2fr;gap:36px;background:#f2f7f8}.form-note{padding:20px;border:1px dashed #9bb6bb;border-radius:8px;color:#58727a}.footer{padding:26px clamp(20px,5vw,72px);display:flex;justify-content:space-between;gap:16px;background:#173b49;color:#e9f4f1;font-size:13px}
@media(max-width:700px){.nav{height:56px}.links{gap:14px;font-size:12px}.hero{min-height:0;grid-template-columns:1fr;padding:34px 22px}.visual{min-height:180px}.section{padding:42px 22px}.product-grid,.comparison-grid,.testimonial-grid,.contact{grid-template-columns:1fr}.product-art{height:130px}.footer{flex-direction:column}}`;

export function renderPreviewDocument(content: PageContent): string {
  const products = content.products.map((product) => `
    <article class="product-card">
      <div class="product-art" aria-hidden="true"><span></span></div>
      <div class="product-copy"><h3>${escapeHtml(product.name)}</h3><p>${escapeHtml(product.description)}</p></div>
    </article>`).join("");
  const comparison = content.showComparison ? `
    <section class="section comparison" id="comparison">
      <p class="eyebrow">So sánh nhanh</p><h2>Chọn model phù hợp với nhu cầu</h2>
      <div class="comparison-grid">${content.products.slice(0, 3).map((product) => `
        <article><h3>${escapeHtml(product.name)}</h3><p>${escapeHtml(product.description)}</p></article>`).join("")}</div>
    </section>` : "";
  const testimonials = content.showTestimonials ? `
    <section class="section testimonials" id="testimonials">
      <p class="eyebrow">Khách hàng</p><h2>Trải nghiệm thực tế</h2>
      <div class="testimonial-grid">${content.testimonials.map((item) => `
        <article><div class="stars" aria-label="${item.rating} trên 5 sao">${"★".repeat(Math.max(0, Math.min(5, item.rating)))}</div>
          <p>“${escapeHtml(item.quote)}”</p><strong>${escapeHtml(item.author)} · ${escapeHtml(item.location)}</strong></article>`).join("")}</div>
    </section>` : "";

  return `<!doctype html>
<html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(content.heroTitle)}</title><style>
${previewStyles}
</style></head><body><main class="site">
<nav class="nav"><a class="logo" href="#top">Pure Living</a><div class="links"><a href="#products">Sản phẩm</a>${content.showComparison ? '<a href="#comparison">So sánh</a>' : ""}${content.showTestimonials ? '<a href="#testimonials">Đánh giá</a>' : ""}<a href="#contact">Liên hệ</a></div></nav>
<section class="hero" id="top"><div class="hero-copy"><p class="eyebrow">${escapeHtml(content.heroEyebrow)}</p><h1>${escapeHtml(content.heroTitle)}</h1><p>${escapeHtml(content.heroDescription)}</p><a class="cta" href="#products">Khám phá sản phẩm</a></div><div class="visual" aria-hidden="true"><div class="machine"></div></div></section>
<section class="section" id="products"><p class="eyebrow">Sản phẩm nổi bật</p><h2>Chọn giải pháp phù hợp với gia đình bạn</h2><div class="product-grid">${products}</div></section>
${comparison}${testimonials}
<section class="section contact" id="contact"><div><p class="eyebrow">Tư vấn sản phẩm</p><h2>Để lại thông tin, chúng tôi sẽ liên hệ.</h2><p>Nhận tư vấn theo nhu cầu sử dụng, không gian và ngân sách.</p></div><div class="form-note">Form liên hệ chưa được kết nối. Không có thông tin nào được gửi từ bản xem trước.</div></section>
<footer class="footer"><strong>Pure Living · Nước sạch cho gia đình</strong><span>Website preview · Chưa xuất bản</span></footer>
</main></body></html>`;
}