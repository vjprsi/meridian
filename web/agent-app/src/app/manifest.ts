import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    id: "/",
    name: "Meridian Agent",
    short_name: "Agent",
    description: "Cash-in, cash-out, bills, airtime, onboarding and float for Meridian agent outlets",
    start_url: "/",
    scope: "/",
    display: "standalone",
    orientation: "portrait",
    background_color: "#f4f6f9",
    theme_color: "#1740b8",
    categories: ["finance", "business"],
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/icons/maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
    shortcuts: [
      { name: "Cash-in", url: "/cash-in" },
      { name: "Cash-out", url: "/cash-out" },
      { name: "Sync", url: "/sync" },
    ],
  };
}
