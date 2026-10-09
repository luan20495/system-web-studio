// @class: harness — HARNESS, NOT REAL BACKEND. The shell markup of AdminApp / StudioApp with the real useNavDrawer + MenuButton in the Admin CSS set; also carries a .grid2 page for the reflow check.
import { createRoot } from "react-dom/client";
import { MenuButton, useNavDrawer } from "../../packages/ui/src/NavDrawer";
import { BrandLockup } from "../../packages/ui/src/Brand";
import "./admin-css";

function App() {
  const nav = useNavDrawer();
  return (
    <div className="shell admin" data-nav={nav.open ? "open" : "closed"}>
      <aside className="sidebar dark" id="admin-sidebar" aria-label="Điều hướng quản trị">
        <div className="sideBrand"><BrandLockup portal="admin"/></div>
        <nav>{["Tổng quan", "Người dùng", "Ứng dụng", "AI"].map((l, i) => <a key={l} className="navLink" href={`#s${i}`}>{l}</a>)}</nav>
        <div className="sideFoot"><button className="btn sm" id="foot-btn">Hồ sơ</button></div>
      </aside>
      <div className="sideBackdrop" id="backdrop" onClick={nav.close} aria-hidden="true"/>
      <div className="shellMain">
        <header className="topHeader"><div className="row"><MenuButton open={nav.open} onClick={nav.toggle} buttonRef={nav.button} controls="admin-sidebar"/><div className="crumb">Quản trị công ty</div></div><button className="btn sm ghost" id="logout">Đăng xuất</button></header>
        <main className="page" id="main" tabIndex={0}>
          <div className="pageHead"><div><h1>Tổng quan</h1></div></div>
          <a href="#x" id="in-page">Liên kết trong trang</a>
          <div className="grid2"><section className="card"><h2>Hoạt động gần đây</h2><p>Nội dung</p></section><section className="card"><h2>AI tháng này</h2><p>Nội dung</p></section></div>
        </main>
      </div>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App/>);
