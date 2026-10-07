import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";
import { Providers } from "@/components/providers";
import { THEME_BOOT_SCRIPT } from "@/lib/theme";
import "./globals.css";

const inter = Inter({ variable: "--font-inter", subsets: ["latin"] });

export const metadata: Metadata = {
  title: { default: "Meridian Merchant", template: "%s · Meridian Merchant" },
  description: "Merchant app: QR sales, payment links, refunds, settlements and staff for your shops",
  applicationName: "Meridian Merchant",
  icons: { icon: "/icon.svg", apple: "/icons/icon-192.png" },
  appleWebApp: { capable: true, title: "Merchant", statusBarStyle: "default" },
  formatDetection: { telephone: false },
  referrer: "no-referrer",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#0f5f59" },
    { media: "(prefers-color-scheme: dark)", color: "#0b1017" },
  ],
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${inter.variable} h-full antialiased`} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_BOOT_SCRIPT }} />
      </head>
      <body className="min-h-full font-sans">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
