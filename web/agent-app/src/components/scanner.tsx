"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui";

interface Detector { detect(src: CanvasImageSource): Promise<{ rawValue: string }[]> }
type DetectorCtor = new (o: { formats: string[] }) => Detector;

/** True when the browser has a native barcode/QR detector (no JS decoder is shipped: keeps the bundle small). */
export function canScan(): boolean {
  return typeof window !== "undefined" && "BarcodeDetector" in window && !!navigator.mediaDevices?.getUserMedia;
}

/** Camera QR / barcode scanner using the native BarcodeDetector. */
export function Scanner({ onResult, onClose }: { onResult: (v: string) => void; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let stream: MediaStream | null = null;
    let stop = false;
    const Ctor = (window as unknown as { BarcodeDetector: DetectorCtor }).BarcodeDetector;
    const detector = new Ctor({ formats: ["qr_code", "code_128", "code_39"] });
    (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
        if (!video.current) return;
        video.current.srcObject = stream;
        await video.current.play();
        while (!stop) {
          const codes = await detector.detect(video.current).catch(() => []);
          if (codes[0]?.rawValue) { onResult(codes[0].rawValue.trim()); return; }
          await new Promise((r) => setTimeout(r, 250));
        }
      } catch (e) {
        setErr(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { stop = true; stream?.getTracks().forEach((t) => t.stop()); };
  }, [onResult]);

  return (
    <div className="flex flex-col gap-2">
      <video ref={video} playsInline muted className="aspect-square w-full rounded-[var(--radius)] bg-black object-cover" />
      {err && <p role="alert" className="text-bad">{err}</p>}
      <Button variant="secondary" onClick={onClose}>✕</Button>
    </div>
  );
}
