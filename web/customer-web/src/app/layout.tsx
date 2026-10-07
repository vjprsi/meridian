import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";
import { Providers } from "@/components/providers";
import { ServiceWorker } from "@/components/service-worker";
import { THEME_BOOT_SCRIPT } from "@/lib/theme";
import "./globals.css";

const inter = Inter({ variable: "--font-inter", subsets: ["latin"] });

export const metadata: Metadata = {
  title: { default: "Meridian Bank", template: "%s · Meridian Bank" },
  description: "Your accounts, payments, cards and loans with Meridian Bank",
  applicationName: "Meridian Bank",
  appleWebApp: { capable: true, title: "Meridian", statusBarStyle: "default" },
  icons: { icon: "/icon.svg", apple: "/icon-192.png" },
  formatDetection: { telephone: false },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#0b5cad" },
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
        <ServiceWorker />
      </body>
    </html>
  );
}
