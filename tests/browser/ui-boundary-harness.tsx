// @class: harness — HARNESS, NOT REAL BACKEND. The shared ErrorBoundary in the Admin CSS set: a component that throws on click, fallback, focus on its heading, retry, route reset.
import { createRoot } from "react-dom/client";
import { useState } from "react";
import { ErrorBoundary } from "../../packages/ui/src/ErrorBoundary";
import "./admin-css";

declare global { interface Window { __reported: string[] } }
window.__reported = [];
function Bomb({ armed }: { armed: boolean }) {
  if (armed) throw Object.assign(new Error("secret internal detail at Bomb.tsx:12"), { requestId: "req-7f3a" });
  return <p id="ok">Màn hình bình thường</p>;
}
function App() {
  const [armed, setArmed] = useState(false); const [route, setRoute] = useState("a");
  return (
    <div>
      <button id="route" onClick={() => { setArmed(false); setRoute("b"); }}>Đổi màn hình ({route})</button>
      <ErrorBoundary resetKeys={[route]} onReset={() => setArmed(false)} onError={(e) => window.__reported.push(e.message)} homeHref="/admin">
        <main><h1>Màn hình</h1><button id="arm" onClick={() => setArmed(true)}>Phát nổ</button><Bomb armed={armed}/></main>
      </ErrorBoundary>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App/>);
