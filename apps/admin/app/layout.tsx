import type { Metadata } from "next";
import { connection } from "next/server";
import { I18nProvider, htmlAttrs } from "@xweb/i18n";
import "@xweb/ui/styles/globals.css";
import "@xweb/ui/styles/responsive.css";
import "@xweb/ui/styles/http.css";
import "@xweb/ui/styles/factory.css";
import "@xweb/ui/styles/ui.css";

export const metadata: Metadata = { title: "Xweb Admin", description: "Quản trị công ty trên Xweb" };

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  // per-request rendering so Next can stamp the CSP nonce on its inline scripts
  await connection();
  return (
    <html {...htmlAttrs()}>
      <body><I18nProvider>{children}</I18nProvider></body>
    </html>
  );
}
