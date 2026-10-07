import type { Metadata, Viewport } from "next";
import { Providers } from "@/components/providers";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "Meridian Agent", template: "%s · Meridian Agent" },
  description: "Cash-in, cash-out, bills, airtime, onboarding and float for Meridian agent outlets; works offline",
  applicationName: "Meridian Agent",
  appleWebApp: { capable: true, title: "Meridian Agent", statusBarStyle: "default" },
  icons: { icon: "/icons/icon-192.png", apple: "/icons/icon-192.png" },
};

export const viewport: Viewport = {
  themeColor: "#1740b8",
  width: "device-width",
  initialScale: 1,
};

// System fonts only (no web-font download on metered connections).
export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className="h-full antialiased">
      <body className="min-h-full font-sans">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
