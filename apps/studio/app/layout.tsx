import type { Metadata } from "next";
import { connection } from "next/server";
import { I18nProvider, htmlAttrs } from "@xweb/i18n";
import { SessionProvider } from "@xweb/auth";
import "@xweb/ui/styles/globals.css";
import "@xweb/ui/styles/responsive.css";
import "@xweb/ui/styles/http.css";
import "@xweb/ui/styles/factory.css";
import "@xweb/ui/styles/builder.css";
import "@xweb/ui/styles/ui.css";

export const metadata: Metadata = { title: "Xweb Studio", description: "Tạo và xuất bản ứng dụng trên Xweb" };

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  // the session lives in the layout, which survives navigation (a provider inside the page is remounted by every path change: FQ-PERF-01)
  // per-request rendering so Next can stamp the CSP nonce on its inline scripts
  await connection();
  return (
    <html {...htmlAttrs()}>
      <body><I18nProvider><SessionProvider>{children}</SessionProvider></I18nProvider></body>
    </html>
  );
}
