// @class: harness — HARNESS, NOT REAL BACKEND. The XWEB brand system (C5-S3) in the Admin CSS set: logo family, the three portal sidebar lockups, the auth card on its
// background, the three banner templates, the shared backgrounds, the semantic colour tokens and representative components. `?dark` renders the same page in [data-theme=dark].
import { createRoot } from "react-dom/client";
import { BrandLogo, BrandMark, BrandLockup } from "../../packages/ui/src/Brand";
import { Banner } from "../../packages/ui/src/Banner";
import { Pill } from "../../packages/ui/src/Pill";
import { FileText, Rocket } from "../../packages/ui/src/icons";
import "./admin-css";

const dark = new URLSearchParams(location.search).has("dark");
const TOKENS = ["primary", "primary-hover", "primary-soft", "secondary", "accent", "bg", "bg-subtle", "surface", "surface-raised", "border", "border-strong", "text", "text-secondary", "text-muted",
  "success", "warning", "danger", "info", "focus", "selected", "hover", "disabled-bg"];

function App() {
  return (
    <div className="xp-bg-dashboard" data-theme={dark ? "dark" : undefined} style={{ color: "var(--color-text)", padding: 16, minHeight: "100vh", font: "var(--text-body)" }}>
      <h1 style={{ font: "var(--text-h1)", margin: "0 0 16px" }}>Hệ thống nhận diện XWEB</h1>

      <section aria-label="Logo" id="logos" style={{ display: "flex", flexWrap: "wrap", gap: 24, alignItems: "center", marginBottom: 24 }}>
        <BrandLogo height={32}/><BrandLogo height={24}/><BrandLogo height={24} mono title="Xweb (đơn sắc)"/>
        <BrandMark size={48} title="Xweb"/><BrandMark size={32}/><BrandMark size={24}/><BrandMark size={16}/><span style={{ color: "var(--color-text)" }}><BrandMark size={32} mono/></span>
      </section>

      <section aria-label="Thanh bên ba cổng" id="lockups" style={{ display: "flex", flexWrap: "wrap", gap: 12, marginBottom: 24 }}>
        {(["platform", "admin", "studio"] as const).map((p) => (
          <aside key={p} className="sidebar dark" style={{ inlineSize: 220, borderRadius: 10 }} aria-label={`Thanh bên ${p}`}>
            <div className="sideBrand"><BrandLockup portal={p}/></div>
            <nav><a className="navLink active" href={`#${p}`} aria-current="page">Tổng quan</a><a className="navLink" href={`#${p}-2`}>Ứng dụng</a></nav>
          </aside>))}
      </section>

      <main className="authPage xp-bg-auth" style={{ height: "auto", minHeight: 0, borderRadius: 16, marginBottom: 24 }} id="auth">
        <div className="authPanel">
          <div className="authBrand"><BrandLogo height={28}/></div>
          <h2 style={{ margin: 0, font: "var(--text-h1)" }}>Chào mừng trở lại</h2>
          <p className="authLead">Đăng nhập vào Xweb Studio bằng tài khoản công ty</p>
          <label className="field" style={{ display: "grid", gap: 4 }}>Tên đăng nhập<input id="auth-user" defaultValue="an.nguyen"/></label>
          <p><button className="btn primary block" id="auth-submit">Đăng nhập</button></p>
        </div>
        <p className="authFoot">Nền tảng nội bộ · truy cập được kiểm soát và ghi nhật ký</p>
      </main>

      <Banner variant="intro" eyebrow="Xweb Studio" title="Tạo ứng dụng doanh nghiệp từ mô tả" actions={<><button className="btn primary">Tạo ứng dụng</button><button className="btn">Xem mẫu</button></>}>
        Mô tả nghiệp vụ, Xweb dựng giao diện, dữ liệu và quy trình; bạn duyệt và xuất bản.
      </Banner>
      <Banner variant="docs" eyebrow="Tài liệu" title="Kết nối nguồn dữ liệu nội bộ" media={<FileText size={72} aria-hidden="true"/>} actions={<a className="btn" href="#docs">Đọc hướng dẫn</a>}>
        Kết nối qua Connector Proxy; thông tin xác thực chỉ nằm trên máy chủ.
      </Banner>
      <Banner variant="onboarding" eyebrow="Bắt đầu" title="Chào mừng Công ty TNHH Một Thành Viên Rất Dài Tên Phát Triển Phần Mềm" media={<Rocket size={72} aria-hidden="true"/>} actions={<button className="btn primary">Bắt đầu</button>}>
        Ba bước: mời đồng nghiệp, kết nối dữ liệu, tạo ứng dụng đầu tiên.
      </Banner>

      <section aria-label="Nền dùng chung" id="backgrounds" style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(150px,1fr))", gap: 12, marginBottom: 24 }}>
        {["xp-bg-auth", "xp-bg-dashboard", "xp-bg-hero", "xp-bg-pattern"].map((c) => <div key={c} className={c} style={{ blockSize: 96, borderRadius: 10, border: "1px solid var(--color-border)", padding: 8 }}><code>.{c}</code></div>)}
      </section>

      <section aria-label="Token màu" id="tokens" style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(150px,1fr))", gap: 8, marginBottom: 24 }}>
        {TOKENS.map((t) => <div key={t} style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 12 }}><span style={{ inlineSize: 24, blockSize: 24, borderRadius: 6, background: `var(--color-${t})`, border: "1px solid var(--color-border-strong)", flex: "none" }}/><code>{t}</code></div>)}
      </section>

      <section aria-label="Thành phần" id="components" className={dark ? "studio" : "shell"} style={{ display: "block", height: "auto", padding: 16, borderRadius: 10, background: "var(--color-surface)", border: "1px solid var(--color-border)" }}>
        <p className="row"><button className="btn primary">Lưu</button><button className="btn">Hủy</button><button className="btn danger">Xóa</button><button className="btn" disabled>Không khả dụng</button><a href="#link">Liên kết</a></p>
        <p className="row"><Pill value="ACTIVE" label="Hoạt động"/><Pill value="AWAITING_REVIEW" label="Chờ duyệt"/><Pill value="HIGH_RISK" label="Rủi ro cao"/><Pill value="x" tone="info" label="Thông tin"/></p>
        {dark ? null : <p className="row"><input aria-label="Ô nhập" defaultValue="Giá trị"/><select aria-label="Chọn"><option>Lựa chọn</option></select><label><input type="checkbox" defaultChecked/> Ghi nhớ</label></p>}
      </section>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App/>);
