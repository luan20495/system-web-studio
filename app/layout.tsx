import type { Metadata } from "next";
import "./globals.css";
import "./responsive.css";
import "./http.css";

export const metadata: Metadata = {
  title: "System Web Studio",
  description: "AI-first web studio prototype",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi">
      <body>{children}</body>
    </html>
  );
}
