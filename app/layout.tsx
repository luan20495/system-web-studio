import type { Metadata } from "next";
import { connection } from "next/server";
import "../packages/ui/src/styles/globals.css";
import "../packages/ui/src/styles/responsive.css";
import "../packages/ui/src/styles/http.css";
import "../packages/ui/src/styles/factory.css";

export const metadata: Metadata = {
  title: "AI Software Factory",
  description: "AI-first web studio prototype",
};

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  // Per-request rendering lets Next stamp the CSP nonce on its inline scripts. Not possible (or needed) in the static export.
  if (process.env.NEXT_PUBLIC_API_MODE === "http") await connection();
  return (
    <html lang="vi">
      <body>{children}</body>
    </html>
  );
}
