// Generates the extension PNG icons (amber rounded square with a white "fingerprint" ring motif).
import { deflateSync } from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
const crc32 = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
};
function png(size) {
  const raw = Buffer.alloc((size * 4 + 1) * size);
  const r = size / 2, radius = size * 0.22;
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      const o = y * (size * 4 + 1) + 1 + x * 4;
      const dx = Math.max(Math.abs(x + 0.5 - r) - (r - radius), 0), dy = Math.max(Math.abs(y + 0.5 - r) - (r - radius), 0);
      const inside = Math.hypot(dx, dy) <= radius;
      const d = Math.hypot(x + 0.5 - r, y + 0.5 - r) / size;
      const ring = [0.12, 0.2, 0.28].some((rr) => Math.abs(d - rr) < 0.028) && y < size * 0.8;
      const [cr, cg, cb] = ring ? [255, 255, 255] : [245, 166, 35];
      raw[o] = cr; raw[o + 1] = cg; raw[o + 2] = cb; raw[o + 3] = inside ? 255 : 0;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
mkdirSync('public/icons', { recursive: true });
for (const s of [16, 48, 128]) writeFileSync(`public/icons/icon${s}.png`, png(s));
console.log('icons written');
