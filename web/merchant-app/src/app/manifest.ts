import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    id: "/",
    name: "Meridian Merchant",
    short_name: "Merchant",
    description: "Take QR payments, payment links and refunds, and follow settlements for your shops",
    start_url: "/",
    scope: "/",
    display: "standalone",
    orientation: "portrait",
    background_color: "#f5f7fa",
    theme_color: "#0f5f59",
    categories: ["finance", "business"],
    lang: "en",
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/icons/maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
    shortcuts: [
      { name: "New sale", url: "/sale" },
      { name: "Sales", url: "/sales" },
    ],
  };
}
