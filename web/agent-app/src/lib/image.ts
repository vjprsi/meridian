"use client";

/**
 * Shrinks a camera photo before it is stored or uploaded (low bandwidth, small encrypted outbox): longest side
 * 1280 px, JPEG quality 0.7, typically 100–250 KB. EXIF (including GPS) is dropped by the re-encode.
 */
export async function compressPhoto(file: File, maxSide = 1280, quality = 0.7): Promise<Blob> {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
  const w = Math.round(bitmap.width * scale);
  const h = Math.round(bitmap.height * scale);
  const canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Canvas unavailable");
  ctx.drawImage(bitmap, 0, 0, w, h);
  bitmap.close();
  return new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("Could not encode the photo"))), "image/jpeg", quality));
}
