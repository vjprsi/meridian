// Generates the PWA icons (PNG) without image libraries: a blue rounded tile with a white "M".
// Run: node scripts/gen-icons.mjs  (outputs to public/icons; the PNGs are committed).
import { deflateSync } from "node:zlib";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const out = join(dirname(fileURLToPath(import.meta.url)), "..", "public", "icons");
mkdirSync(out, { recursive: true });

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function png(size, pixel) {
  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      const [r, g, b, a] = pixel(x + 0.5, y + 0.5);
      const o = y * (size * 4 + 1) + 1 + x * 4;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b; raw[o + 3] = a;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

function segDist(px, py, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay;
  const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

const BLUE = [23, 64, 184, 255];
const WHITE = [255, 255, 255, 255];
const CLEAR = [0, 0, 0, 0];

function icon(size, maskable) {
  const s = size;
  const r = maskable ? 0 : s * 0.22;
  const g = maskable ? 0.6 : 0.72; // glyph box relative to the tile (maskable keeps it in the safe zone)
  const x0 = s * (1 - g) / 2, x1 = s - x0, y0 = s * (1 - g) / 2 + s * 0.04, y1 = s - y0 + s * 0.04;
  const w = s * 0.11 * (g / 0.72);
  const segs = [[x0, y1, x0, y0], [x0, y0, s / 2, s * 0.58], [s / 2, s * 0.58, x1, y0], [x1, y0, x1, y1]];
  return png(size, (x, y) => {
    if (!maskable) {
      const cx = Math.min(Math.max(x, r), s - r), cy = Math.min(Math.max(y, r), s - r);
      if (Math.hypot(x - cx, y - cy) > r) return CLEAR;
    }
    for (const [ax, ay, bx, by] of segs) if (segDist(x, y, ax, ay, bx, by) < w / 2) return WHITE;
    return BLUE;
  });
}

writeFileSync(join(out, "icon-192.png"), icon(192, false));
writeFileSync(join(out, "icon-512.png"), icon(512, false));
writeFileSync(join(out, "maskable-512.png"), icon(512, true));
console.log("icons written to", out);
