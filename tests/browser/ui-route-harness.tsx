// @class: harness — HARNESS, NOT REAL BACKEND. The portal shell with the real SkipLink + useMain (route focus, main tab stop only while it scrolls) in the Admin CSS set.
import { createRoot } from "react-dom/client";
import { useEffect, useState } from "react";
import { SkipLink } from "../../packages/ui/src/SkipLink";
import { useMain } from "../../packages/ui/src/useMain";
import "./admin-css";

const NAV = ["Tổng quan", "Người dùng", "Ứng dụng", "Nhật ký", "Cài đặt"];
function Page({ route }: { route: number }) {
  const [loaded, setLoaded] = useState(-1);                        // route 3 renders its heading late (a loading state first)
  useEffect(() => { if (route === 3) { const t = setTimeout(() => setLoaded(3), 120); return () => clearTimeout(t); } setLoaded(-1); }, [route]);
  if (route === 3 && loaded !== 3) return <p id="loading">Đang tải…</p>;
  return <><h1>{NAV[route]}</h1><p>Nội dung của {NAV[route]}</p>{route === 1 ? <div id="tall" style={{ height: 1800 }}>Trang dài</div> : null}</>;
}
function App() {
  const [route, setRoute] = useState(0);
  const main = useMain(String(route));
  return (
    <div className="shell admin" data-nav="closed">
      <SkipLink/>
      <aside className="sidebar dark" aria-label="Điều hướng"><nav>{NAV.map((l, i) => <button key={l} type="button" className="navLink" style={{ background: "transparent", border: 0, textAlign: "left" }} id={`nav-${i}`} aria-current={route === i ? "page" : undefined} onClick={() => setRoute(i)}>{l}</button>)}</nav></aside>
      <div className="shellMain"><header className="topHeader"><div className="crumb">Quản trị</div><button className="btn sm" id="logout">Đăng xuất</button></header>
        <main className="page" {...main}><Page route={route}/></main></div>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App/>);
