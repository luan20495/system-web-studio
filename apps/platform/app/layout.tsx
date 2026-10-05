import type { Metadata } from "next";
import { connection } from "next/server";
import "@xweb/ui/styles/globals.css";
import "@xweb/ui/styles/responsive.css";
import "@xweb/ui/styles/http.css";
import "@xweb/ui/styles/factory.css";

export const metadata: Metadata = { title: "Xweb Platform", description: "Quản trị nền tảng Xweb" };

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  // per-request rendering so Next can stamp the CSP nonce on its inline scripts
  await connection();
  return (
    <html lang="vi">
      <body>{children}</body>
    </html>
  );
}
