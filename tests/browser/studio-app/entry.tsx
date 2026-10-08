// @class: harness — test-only host for the REAL <StudioApp>; NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
// Mounts the real portal entry of apps/studio (PortalApp -> StudioApp) with next/* replaced by the stubs; /api/v1 is answered by fake-api.mjs inside Playwright.
import { createRoot } from "react-dom/client";
import { PortalApp } from "@xweb/auth";
import { StudioApp } from "@/features/studio/StudioApp";
import "@/packages/ui/src/styles/globals.css";
import "@/packages/ui/src/styles/responsive.css";
import "@/packages/ui/src/styles/http.css";
import "@/packages/ui/src/styles/factory.css";
import "@/packages/ui/src/styles/builder.css";
createRoot(document.getElementById("root")!).render(<PortalApp portal="studio" render={(seg) => <StudioApp seg={seg} dedicated/>}/>);
