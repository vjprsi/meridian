// Renders the app icons (public/icon-192.png, public/icon-512.png) without image tooling: a full-bleed blue square
// (safe for maskable crops) with a white "M" drawn inside the central safe zone. Run once: node scripts/make-icons.mjs
import { writeFileSync } from "node:fs";
import { deflateSync } from "node:zlib";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const out = join(dirname(fileURLToPath(import.meta.url)), "..", "public");

// "M" outline in a 100x100 box (same shape as public/icon.svg).
const M = [[28, 72], [28, 30], [38, 30], [50, 50], [62, 30], [72, 30], [72, 72], [63, 72], [63, 46], [52, 64], [48, 64],
  [37, 46], [37, 72]];

function inside(x, y, poly) {
  let c = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i];
    const [xj, yj] = poly[j];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) c = !c;
  }
  return c;
}

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
  const td = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

function png(size) {
  const raw = Buffer.alloc(size * (size * 4 + 1));
  const ss = 4; // 4x4 supersampling for smooth edges
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      let hits = 0;
      for (let sy = 0; sy < ss; sy++) for (let sx = 0; sx < ss; sx++) {
        if (inside(((x + (sx + 0.5) / ss) / size) * 100, ((y + (sy + 0.5) / ss) / size) * 100, M)) hits++;
      }
      const a = hits / (ss * ss);
      const o = y * (size * 4 + 1) + 1 + x * 4;
      raw[o] = Math.round(0x0b + (255 - 0x0b) * a);
      raw[o + 1] = Math.round(0x5c + (255 - 0x5c) * a);
      raw[o + 2] = Math.round(0xad + (255 - 0xad) * a);
      raw[o + 3] = 255;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

for (const s of [192, 512]) writeFileSync(join(out, `icon-${s}.png`), png(s));
