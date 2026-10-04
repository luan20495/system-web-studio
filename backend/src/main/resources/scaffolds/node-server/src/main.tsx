import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { FactoryApp } from "@company/app-sdk";
import { ToastProvider } from "@company/ui";
import "@company/ui/styles.css";
import { App } from "./App";
import "./styles.css";

createRoot(document.getElementById("root")!).render(<StrictMode><FactoryApp><ToastProvider><App /></ToastProvider></FactoryApp></StrictMode>);
