"use client";

import QRCode from "qrcode";
import { useEffect, useState } from "react";

/**
 * Renders an EMVCo merchant-presented QR payload as an SVG, entirely in the browser (the payload never leaves the
 * device for rendering). Error correction M, quiet zone of 4 modules as EMVCo recommends.
 */
export function QrCode({ payload, label, size = 280 }: { payload: string; label: string; size?: number }) {
  const [svg, setSvg] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    QRCode.toString(payload, { type: "svg", errorCorrectionLevel: "M", margin: 4, color: { dark: "#000000", light: "#ffffff" } })
      .then((s) => { if (live) setSvg(s); })
      .catch(() => { if (live) setSvg(null); });
    return () => { live = false; };
  }, [payload]);
  return (
    <figure className="mx-auto flex flex-col items-center gap-2">
      <div role="img" aria-label={label} data-testid="sale-qr" data-payload={payload}
        className="rounded-[var(--radius)] bg-white p-2 shadow-sm [&>svg]:h-full [&>svg]:w-full"
        style={{ width: size, height: size }}
        // The SVG is generated locally by the qrcode library from the payload string (no user HTML).
        dangerouslySetInnerHTML={svg ? { __html: svg } : undefined} />
    </figure>
  );
}
