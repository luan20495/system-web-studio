import type { Metadata } from "next";
import { connection } from "next/server";
import "./globals.css";
import "./responsive.css";
import "./http.css";

export const metadata: Metadata = {
  title: "System Web Studio",
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
