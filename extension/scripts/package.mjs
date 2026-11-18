// Zips dist/ into fingernotes-extension.zip (upload to the Chrome Web Store or "Load unpacked" after extracting).
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { ZipWriter, Uint8ArrayReader, Uint8ArrayWriter, configure } from '@zip.js/zip.js';

configure({ useWebWorkers: false });
const walk = (d) => readdirSync(d).flatMap((f) => (statSync(join(d, f)).isDirectory() ? walk(join(d, f)) : [join(d, f)]));
const zip = new ZipWriter(new Uint8ArrayWriter());
for (const f of walk('dist')) await zip.add(relative('dist', f).split(sep).join('/'), new Uint8ArrayReader(readFileSync(f)));
const manifest = JSON.parse(readFileSync('dist/manifest.json', 'utf8'));
const out = `fingernotes-extension-${manifest.version}.zip`;
writeFileSync(out, await zip.close());
console.log('wrote', out);
